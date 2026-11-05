alter table provider_staff add column leave_period text check(leave_period in('MONTHLY','WEEKLY'));
alter table provider_staff add column leave_days integer;
alter table provider_staff add constraint staff_leave_limit check((leave_period is null and leave_days is null) or (leave_period is not null and leave_days between 0 and case when leave_period='WEEKLY' then 7 else 31 end));
alter function provider_staff_save(jsonb) rename to staff_save_before_leave;
revoke all on function staff_save_before_leave(jsonb) from public,anon,authenticated;
create function provider_staff_save(payload jsonb) returns jsonb language plpgsql security definer set search_path=public as $$
#variable_conflict use_variable
declare p uuid:=kitchen_book_provider();result jsonb;mode text:=payload->>'leave_period';n integer:=(payload->>'leave_days')::integer;begin
 if payload ? 'leave_period' and (mode not in('WEEKLY','MONTHLY') or mode is null or n is null or n<0 or n>case when mode='WEEKLY' then 7 else 31 end) then raise exception 'Choose weekly or monthly leave and a valid allowance';end if;
 result:=staff_save_before_leave(payload);
 if payload ? 'leave_period' then update provider_staff set leave_period=mode,leave_days=n where id=(payload->>'id')::uuid and provider_id=p;end if;
 return result;
end $$;
revoke all on function provider_staff_save(jsonb) from public,anon;
grant execute on function provider_staff_save(jsonb) to authenticated;

create function staff_attendance_summary(staff_key uuid,m date) returns jsonb language plpgsql stable security definer set search_path=public as $$
declare s provider_staff;today date:=(now() at time zone 'Asia/Kolkata')::date;lastday date:=(m+interval '1 month - 1 day')::date;
 p integer;h integer;a integer;l integer;paidleave integer;elapsed integer;missing integer;amount bigint;begin
 select * into s from provider_staff where id=staff_key;
 select count(*) filter(where status='PRESENT'),count(*) filter(where status='HALF_DAY'),count(*) filter(where status='ABSENT'),count(*) filter(where status='LEAVE')
 into p,h,a,l from provider_staff_attendance where staff_id=staff_key and day between greatest(m,s.joined_on) and least(lastday,today,coalesce(s.left_on,lastday));
 if s.leave_period='MONTHLY' then paidleave:=least(l,s.leave_days);
 elsif s.leave_period='WEEKLY' then
  select count(*) into paidleave from (
   select day,row_number() over(partition by date_trunc('week',day) order by day) n from provider_staff_attendance
   where staff_id=staff_key and status='LEAVE' and day between greatest(date_trunc('week',m)::date,s.joined_on) and least(lastday,today,coalesce(s.left_on,lastday))
  ) x where day>=m and n<=s.leave_days;
 else paidleave:=0;end if;
 elapsed:=greatest(0,least(lastday,today,coalesce(s.left_on,lastday))-greatest(m,s.joined_on)+1);
 missing:=greatest(0,elapsed-p-h-a-l);
 amount:=round(s.monthly_salary_paise::numeric*(p+h*0.5+paidleave)/extract(day from lastday));
 return jsonb_build_object('month',m,'days_in_month',extract(day from lastday),'present',p,'half_day',h,'absent',a,'leave',l,'paid_leave',paidleave,
 'excess_leave',l-paidleave,'unmarked',missing,'elapsed_employment_days',elapsed,'payable_units',p+h*0.5+paidleave,'estimated_salary_paise',amount,
 'policy_set',s.leave_period is not null,'month_ended',lastday<today,'needs_review',missing>0 or l>paidleave or s.leave_period is null,
 'signature',md5(jsonb_build_array(s.monthly_salary_paise,s.leave_period,s.leave_days,p,h,a,l,paidleave,missing,s.joined_on,s.left_on)::text));
end $$;
revoke all on function staff_attendance_summary(uuid,date) from public,anon,authenticated;
alter function provider_staff_month(date) rename to staff_month_before_leave;
revoke all on function staff_month_before_leave(date) from public,anon,authenticated;
create function provider_staff_month(target_month date) returns jsonb language plpgsql stable security definer set search_path=public as $$
declare b jsonb;begin
 b:=staff_month_before_leave(target_month);
 return b||jsonb_build_object('staff',coalesce((select jsonb_agg(s||jsonb_build_object('attendance_summary',staff_attendance_summary((s->>'id')::uuid,date_trunc('month',target_month)::date))) from jsonb_array_elements(b->'staff') s),'[]'));
end $$;
revoke all on function provider_staff_month(date) from public,anon;
grant execute on function provider_staff_month(date) to authenticated;

-- Zero-pay months are recorded without inventing a positive kitchen expense.
alter table provider_staff_payroll alter column expense_id drop not null;
alter table provider_staff_payroll drop constraint provider_staff_payroll_amount_paise_check;
alter table provider_staff_payroll add check(amount_paise between 0 and 100000000);
create function provider_staff_approve_attendance(payload jsonb) returns jsonb language plpgsql security definer set search_path=public as $$
#variable_conflict use_variable
declare p uuid:=kitchen_book_provider();s provider_staff;r provider_staff_payroll;summary jsonb;m date:=(payload->>'month')::date;
 amount bigint:=(payload->>'amount')::bigint;k uuid:=(payload->>'id')::uuid;begin
 perform 1 from providers where id=p for update;
 select * into s from provider_staff where id=(payload->>'staff_id')::uuid and provider_id=p;
 if s.id is null or k is null or m is null or m<>date_trunc('month',m)::date or m<'2020-01-01' or s.joined_on>=m+interval '1 month' or s.left_on<m then raise exception 'Check staff and month';end if;
 select * into r from provider_staff_payroll where id=k;
 if r.id is not null then if r.staff_id=s.id and r.payload=payload then return jsonb_build_object('saved',true);end if;raise exception 'Salary approval changed';end if;
 summary:=staff_attendance_summary(s.id,m);
 if not (summary->>'month_ended')::boolean then raise exception 'Final salary approval opens after month-end';end if;
 if not (summary->>'policy_set')::boolean then raise exception 'Set the staff leave allowance first';end if;
 if (summary->>'unmarked')::integer>0 then raise exception 'Mark all employment days before approving salary';end if;
 if payload->>'attendance_signature' is distinct from summary->>'signature' then raise exception 'Attendance or policy changed. Refresh the salary preview';end if;
 if amount is null or amount<0 or amount>100000000 then raise exception 'Enter a valid final salary';end if;
 if (amount<>(summary->>'estimated_salary_paise')::bigint or (summary->>'excess_leave')::integer>0) and length(trim(coalesce(payload->>'note','')))<5 then raise exception 'Explain your decision for excess leave or salary adjustment in the note';end if;
 if amount>0 then return provider_staff_approve(payload);end if;
 if exists(select 1 from provider_staff_payroll where staff_id=s.id and month=m) then raise exception 'Salary already approved';end if;
 perform kitchen_ensure_month(p,m);
 if exists(select 1 from provider_kitchen_entries e where e.provider_id=p and e.category='Staff' and e.kind='EXPENSE' and e.voided_at is null and e.entry_date>=m and e.entry_date<m+interval '1 month' and not exists(select 1 from provider_staff_payroll x where x.expense_id=e.id)) then raise exception 'Reconcile existing manual Staff expenses first';end if;
 insert into provider_staff_payroll(id,staff_id,month,amount_paise,expense_id,accrual_start,accrual_end,note,payload)
 values(k,s.id,m,0,null,greatest(m,s.joined_on),least((m+interval '1 month - 1 day')::date,coalesce(s.left_on,(m+interval '1 month - 1 day')::date)),coalesce(payload->>'note',''),payload);
 insert into audit_logs(actor_id,action,entity_type,entity_id,after_data) values(auth.uid(),'STAFF_ZERO_SALARY_APPROVED','provider_staff_payroll',k::text,payload);
 return jsonb_build_object('saved',true);
end $$;
revoke all on function provider_staff_approve_attendance(jsonb) from public,anon;
grant execute on function provider_staff_approve_attendance(jsonb) to authenticated;
