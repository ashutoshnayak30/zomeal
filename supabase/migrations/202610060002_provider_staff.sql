-- Private staff bookkeeping; no bank transfers, wallet changes or statutory payroll.
create table public.provider_staff (
 id uuid primary key, provider_id uuid not null references providers(id),
 name text not null check(length(trim(name)) between 1 and 80),
 position text not null check(length(trim(position)) between 1 and 60),
 phone text not null default '' check(length(phone)<=20),
 monthly_salary_paise bigint not null check(monthly_salary_paise between 1 and 100000000),
 joined_on date not null, left_on date, revision integer not null default 1,
 payload jsonb not null, check(left_on is null or left_on>=joined_on)
);
create table public.provider_staff_attendance (
 staff_id uuid not null references provider_staff(id), day date not null,
 status text not null check(status in('PRESENT','ABSENT','HALF_DAY','LEAVE')),
 primary key(staff_id,day), updated_at timestamptz not null default now()
);
create table public.provider_staff_payroll (
 id uuid primary key,staff_id uuid not null references provider_staff(id),
 month date not null check(month=date_trunc('month',month)::date),
 amount_paise bigint not null check(amount_paise between 1 and 100000000),
 expense_id uuid not null unique references provider_kitchen_entries(id),
 accrual_start date not null,accrual_end date not null,check(accrual_end>=accrual_start),
 note text not null default '' check(length(note)<=300),payload jsonb not null,
 unique(staff_id,month)
);
create table public.provider_staff_payments (
 id uuid primary key,payroll_id uuid not null references provider_staff_payroll(id),
 day date not null,amount_paise bigint not null check(amount_paise between 1 and 100000000),
 kind text not null check(kind in('ADVANCE','PAYMENT')),
 note text not null default '' check(length(note)<=300),payload jsonb not null,
 created_at timestamptz not null default now()
);
alter table provider_staff enable row level security;
alter table provider_staff_attendance enable row level security;
alter table provider_staff_payroll enable row level security;
alter table provider_staff_payments enable row level security;
revoke all on provider_staff,provider_staff_attendance,provider_staff_payroll,provider_staff_payments from public,anon,authenticated;
create index provider_staff_owner on provider_staff(provider_id);

create function public.provider_staff_save(payload jsonb) returns jsonb language plpgsql security definer set search_path=public as $$
#variable_conflict use_variable
declare p uuid:=kitchen_book_provider(); k uuid:=(payload->>'id')::uuid; existing provider_staff; begin
 perform 1 from providers where id=p for update;
 select * into existing from provider_staff where id=k;
 if existing.id is not null and existing.provider_id<>p then raise exception 'Staff member unavailable'; end if;
 if existing.id is not null and existing.payload=payload then return jsonb_build_object('saved',true);end if;
 if k is null or coalesce(existing.revision,0)<>coalesce((payload->>'revision')::integer,0) then raise exception 'Staff changed. Refresh before saving'; end if;
 if (payload->>'joined_on')::date<'2020-01-01' or (payload->>'joined_on')::date>(now() at time zone 'Asia/Kolkata')::date then raise exception 'Check joining date';end if;
 insert into provider_staff(id,provider_id,name,position,phone,monthly_salary_paise,joined_on,left_on,payload)
 values(k,p,trim(payload->>'name'),trim(payload->>'position'),coalesce(payload->>'phone',''),(payload->>'salary')::bigint,
 (payload->>'joined_on')::date,nullif(payload->>'left_on','')::date,payload)
 on conflict(id) do update set name=excluded.name,position=excluded.position,phone=excluded.phone,monthly_salary_paise=excluded.monthly_salary_paise,
 joined_on=excluded.joined_on,left_on=excluded.left_on,payload=excluded.payload,revision=provider_staff.revision+1;
 insert into audit_logs(actor_id,action,entity_type,entity_id) values(auth.uid(),'STAFF_SAVED','provider_staff',k::text);
 return jsonb_build_object('saved',true);
end $$;

create function public.provider_staff_attend(payload jsonb) returns jsonb language plpgsql security definer set search_path=public as $$
#variable_conflict use_variable
declare p uuid:=kitchen_book_provider(); s provider_staff; d date:=(payload->>'day')::date; begin
 perform 1 from providers where id=p for update;
 select * into s from provider_staff where id=(payload->>'staff_id')::uuid and provider_id=p;
 if s.id is null or d is null or d<s.joined_on or d>coalesce(s.left_on,d) or d>(now() at time zone 'Asia/Kolkata')::date then raise exception 'Check staff and attendance date';end if;
 insert into provider_staff_attendance(staff_id,day,status) values(s.id,d,payload->>'status')
 on conflict(staff_id,day) do update set status=excluded.status,updated_at=now();
 insert into audit_logs(actor_id,action,entity_type,entity_id,after_data) values(auth.uid(),'STAFF_ATTENDANCE','provider_staff',s.id::text,payload);
 return jsonb_build_object('saved',true);
end $$;

create function public.provider_staff_approve(payload jsonb) returns jsonb language plpgsql security definer set search_path=public as $$
#variable_conflict use_variable
declare p uuid:=kitchen_book_provider(); s provider_staff; existing provider_staff_payroll;
 k uuid:=(payload->>'id')::uuid; m date:=(payload->>'month')::date; amount bigint:=(payload->>'amount')::bigint;
 expense uuid:=gen_random_uuid(); begin
 perform 1 from providers where id=p for update;
 select * into s from provider_staff where id=(payload->>'staff_id')::uuid and provider_id=p;
 if s.id is null or k is null or m is null or m<>date_trunc('month',m)::date or m<'2020-01-01' or m>date_trunc('month',now() at time zone 'Asia/Kolkata')::date
 or s.joined_on>=m+interval '1 month' or s.left_on<m then raise exception 'Check staff and salary month';end if;
 select * into existing from provider_staff_payroll where id=k;
 if existing.id is not null then
  if existing.staff_id=s.id and existing.payload=payload then return jsonb_build_object('saved',true);end if;
  raise exception 'Salary approval changed';
 end if;
 if exists(select 1 from provider_staff_payroll where staff_id=s.id and month=m) then raise exception 'This salary month is already approved';end if;
 perform kitchen_ensure_month(p,m);
 -- Block ambiguous manual salary rows rather than silently counting salaries twice.
 if exists(select 1 from provider_kitchen_entries e where e.provider_id=p and e.category='Staff' and e.kind='EXPENSE' and e.voided_at is null
 and e.entry_date>=m and e.entry_date<m+interval '1 month' and not exists(select 1 from provider_staff_payroll r where r.expense_id=e.id)) then
 raise exception 'Existing manual Staff expenses found for this month. Reconcile and remove overlapping salary entries in Costs before approving staff salaries';end if;
 insert into provider_kitchen_entries(id,provider_id,entry_date,kind,category,item,amount_paise,cost_type,paid,created_by,note)
 values(expense,p,m,'EXPENSE','Staff','Salary: '||s.name,amount,'FIXED',false,auth.uid(),'Managed in Staff & salaries; do not enter this salary again');
 -- Each month requires salary review. Never create a second automatic salary copy.
 update provider_kitchen_entries set recurring=false where id=expense;
 insert into provider_staff_payroll(id,staff_id,month,amount_paise,expense_id,accrual_start,accrual_end,note,payload)
 values(k,s.id,m,amount,expense,greatest(m,s.joined_on),least((m+interval '1 month - 1 day')::date,coalesce(s.left_on,(m+interval '1 month - 1 day')::date)),coalesce(payload->>'note',''),payload);
 insert into audit_logs(actor_id,action,entity_type,entity_id,after_data) values(auth.uid(),'STAFF_SALARY_APPROVED','provider_staff_payroll',k::text,payload);
 return jsonb_build_object('saved',true);
end $$;

create function public.provider_staff_pay(payload jsonb) returns jsonb language plpgsql security definer set search_path=public as $$
#variable_conflict use_variable
declare p uuid:=kitchen_book_provider(); r provider_staff_payroll; existing provider_staff_payments;
 k uuid:=(payload->>'id')::uuid; d date:=(payload->>'day')::date; amount bigint:=(payload->>'amount')::bigint; total bigint; begin
 perform 1 from providers where id=p for update;
 select r0.* into r from provider_staff_payroll r0 join provider_staff s on s.id=r0.staff_id where r0.id=(payload->>'payroll_id')::uuid and s.provider_id=p;
 if r.id is null or k is null or d is null or d<r.month or d>(now() at time zone 'Asia/Kolkata')::date then raise exception 'Check salary and payment date';end if;
 select * into existing from provider_staff_payments where id=k;
 if existing.id is not null then
  if existing.payroll_id=r.id and existing.payload=payload then return jsonb_build_object('saved',true);end if;
  raise exception 'Payment request changed';end if;
 select coalesce(sum(amount_paise),0) into total from provider_staff_payments where payroll_id=r.id;
 if amount is null or amount<=0 or total+amount>r.amount_paise then raise exception 'Payment exceeds remaining salary or is invalid';end if;
 insert into provider_staff_payments(id,payroll_id,day,amount_paise,kind,note,payload)
 values(k,r.id,d,amount,payload->>'kind',coalesce(payload->>'note',''),payload);
 insert into audit_logs(actor_id,action,entity_type,entity_id,after_data) values(auth.uid(),'STAFF_PAYMENT_RECORDED','provider_staff_payments',k::text,payload);
 return jsonb_build_object('saved',true);
end $$;

create function public.provider_staff_month(target_month date) returns jsonb language plpgsql stable security definer set search_path=public as $$
declare p uuid:=kitchen_book_provider();m date:=date_trunc('month',target_month)::date;begin
 if m is null or m<'2020-01-01' or m>date_trunc('month',now() at time zone 'Asia/Kolkata')::date then raise exception 'Check month';end if;
 return jsonb_build_object('staff',coalesce((select jsonb_agg(to_jsonb(s)-'payload'||jsonb_build_object(
 'payroll',(select to_jsonb(r)-'payload'||jsonb_build_object('paid_paise',(select coalesce(sum(amount_paise),0) from provider_staff_payments where payroll_id=r.id)) from provider_staff_payroll r where r.staff_id=s.id and r.month=m),
 'attendance',coalesce((select jsonb_agg(to_jsonb(a) order by day) from provider_staff_attendance a where a.staff_id=s.id and a.day>=m and a.day<m+interval '1 month'),'[]'),
 'payments',coalesce((select jsonb_agg(to_jsonb(t)-'payload' order by day desc,created_at desc) from provider_staff_payments t join provider_staff_payroll r on r.id=t.payroll_id where r.staff_id=s.id and r.month=m),'[]')) order by s.name)
 from provider_staff s where s.provider_id=p),'[]'));
end $$;

-- Guard generic cost editing/removal and manual repeat against linked payroll entries.
create function public.kitchen_staff_guard() returns trigger language plpgsql set search_path=public as $$
begin
 if exists(select 1 from provider_staff_payroll where expense_id=old.id) then raise exception 'This is a linked salary expense. Manage it in Staff & salaries';end if;
 if tg_op='DELETE' then return old;end if;
 return new;
end $$;
create trigger kitchen_staff_guard before update or delete on provider_kitchen_entries for each row execute function kitchen_staff_guard();
revoke all on function kitchen_staff_guard() from public,anon,authenticated;

alter function public.provider_kitchen_save(jsonb) rename to kitchen_save_before_staff;
revoke all on function kitchen_save_before_staff(jsonb) from public,anon,authenticated;
create function public.provider_kitchen_save(payload jsonb) returns jsonb language plpgsql security definer set search_path=public as $$
declare p uuid:=kitchen_book_provider();d date:=(payload->>'date')::date;begin
 perform 1 from providers where id=p for update;
 if payload->>'category'='Staff' and exists(select 1 from provider_staff_payroll r join provider_staff s on s.id=r.staff_id where s.provider_id=p and r.month=date_trunc('month',d)::date) then
 raise exception 'Staff salaries already managed for this month. Use Staff & salaries to avoid duplicate costs';end if;
 return kitchen_save_before_staff(payload);
end $$;
revoke all on function provider_kitchen_save(jsonb) from public,anon;
grant execute on function provider_kitchen_save(jsonb) to authenticated;

alter function public.provider_kitchen_statement(date) rename to kitchen_statement_before_staff;
revoke all on function kitchen_statement_before_staff(date) from public,anon,authenticated;
create function public.provider_kitchen_statement(target_month date) returns jsonb language plpgsql volatile security definer set search_path=public as $$
declare p uuid:=kitchen_book_provider();b jsonb; rows jsonb; cash bigint;m date:=date_trunc('month',target_month)::date;
 salary_total bigint;salary_accrued bigint;allocated bigint;delta bigint;s jsonb;
 today date:=(now() at time zone 'Asia/Kolkata')::date;begin
 b:=kitchen_statement_before_staff(target_month);
 select coalesce(jsonb_agg(e||jsonb_build_object('staff_managed',r.id is not null,'paid_amount_paise',case when r.id is not null then
 (select coalesce(sum(amount_paise),0) from provider_staff_payments where payroll_id=r.id)
 when (e->>'paid')::boolean then (e->>'amount_paise')::bigint else 0 end) order by e->>'entry_date' desc),'[]') into rows
 from jsonb_array_elements(b->'entries') e left join provider_staff_payroll r on r.expense_id=(e->>'id')::uuid;
 select coalesce(sum(t.amount_paise),0) into cash from provider_staff_payments t join provider_staff_payroll r on r.id=t.payroll_id join provider_staff s on s.id=r.staff_id
 where s.provider_id=p and t.day>=m and t.day<m+interval '1 month';
 s:=b->'statement';
 select coalesce(sum(r.amount_paise),0),coalesce(sum(round(r.amount_paise::numeric*greatest(0,least(today,r.accrual_end)-r.accrual_start+1)/(r.accrual_end-r.accrual_start+1))),0)
 into salary_total,salary_accrued from provider_staff_payroll r join provider_staff st on st.id=r.staff_id where st.provider_id=p and r.month=m;
 allocated:=round(((s->>'monthly_overhead_paise')::bigint-salary_total)::numeric*(s->>'elapsed_days')::integer/(s->>'days_in_month')::integer)+salary_accrued;
 delta:=allocated-(s->>'allocated_fixed_paise')::bigint;
 return b||jsonb_build_object('entries',rows,'staff_cash_paid_paise',cash,'statement',s||jsonb_build_object(
 'allocated_fixed_paise',allocated,'costs_to_date_paise',(s->>'costs_to_date_paise')::bigint+delta,
 'profit_to_date_paise',(s->>'profit_to_date_paise')::bigint-delta,'salary_accrued_paise',salary_accrued));
end $$;
revoke all on function provider_staff_save(jsonb),provider_staff_attend(jsonb),provider_staff_approve(jsonb),provider_staff_pay(jsonb),provider_staff_month(date),provider_kitchen_statement(date) from public,anon;
grant execute on function provider_staff_save(jsonb),provider_staff_attend(jsonb),provider_staff_approve(jsonb),provider_staff_pay(jsonb),provider_staff_month(date),provider_kitchen_statement(date) to authenticated;
