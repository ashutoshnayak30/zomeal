-- Immutable balanced accounting journal and external transaction reconciliation.
create table public.finance_accounts (
  code text primary key,
  name text not null,
  account_type text not null check(account_type in ('ASSET','LIABILITY','EQUITY','REVENUE','EXPENSE')),
  normal_balance text not null check(normal_balance in ('DEBIT','CREDIT')),
  is_active boolean not null default true
);
insert into public.finance_accounts(code,name,account_type,normal_balance) values
('1000','Payment gateway clearing','ASSET','DEBIT'),('1010','Bank and payout clearing','ASSET','DEBIT'),
('1100','Provider advance receivable','ASSET','DEBIT'),('2000','Customer deferred meal revenue','LIABILITY','CREDIT'),
('2100','Provider payable','LIABILITY','CREDIT'),('2200','Customer refunds payable','LIABILITY','CREDIT'),
('2300','Tax and statutory payable','LIABILITY','CREDIT'),('4000','Provider commission revenue','REVENUE','CREDIT'),
('4010','Customer platform fee revenue','REVENUE','CREDIT'),('4020','Delivery fee revenue','REVENUE','CREDIT'),
('5000','Payment gateway fees','EXPENSE','DEBIT'),('5100','Zomeal-funded discounts','EXPENSE','DEBIT')
on conflict(code) do nothing;

create table public.finance_journal_entries (
  id uuid primary key default gen_random_uuid(), journal_number bigint generated always as identity unique,
  occurred_at timestamptz not null, event_kind text not null, description text not null,
  source_type text not null, source_id text not null, external_reference text,
  reverses_entry_id uuid references public.finance_journal_entries(id), metadata jsonb not null default '{}'::jsonb,
  created_by uuid references auth.users(id), created_at timestamptz not null default now(),
  unique(source_type,source_id,event_kind)
);
create table public.finance_journal_lines (
  id uuid primary key default gen_random_uuid(), journal_entry_id uuid not null references public.finance_journal_entries(id),
  account_code text not null references public.finance_accounts(code), provider_id uuid references public.providers(id),
  subscription_id uuid references public.customer_subscriptions(id), debit_paise bigint not null default 0,
  credit_paise bigint not null default 0, memo text,
  constraint journal_line_one_side check((debit_paise>0 and credit_paise=0) or (credit_paise>0 and debit_paise=0))
);
create index finance_journal_time_idx on public.finance_journal_entries(occurred_at desc);
create index finance_journal_reference_idx on public.finance_journal_entries(external_reference) where external_reference is not null;
create index finance_journal_lines_account_idx on public.finance_journal_lines(account_code,journal_entry_id);

create or replace function public.block_finance_journal_mutation() returns trigger language plpgsql as $$
begin raise exception 'Financial journal records are immutable. Post a reversal entry instead'; end; $$;
create trigger finance_entry_immutable before update or delete on public.finance_journal_entries for each row execute function public.block_finance_journal_mutation();
create trigger finance_line_immutable before update or delete on public.finance_journal_lines for each row execute function public.block_finance_journal_mutation();
alter table public.finance_accounts enable row level security;alter table public.finance_journal_entries enable row level security;alter table public.finance_journal_lines enable row level security;
create policy finance_accounts_staff_read on public.finance_accounts for select to authenticated using(public.has_role('ADMIN') or public.has_role('FINANCE'));
create policy finance_entries_staff_read on public.finance_journal_entries for select to authenticated using(public.has_role('ADMIN') or public.has_role('FINANCE'));
create policy finance_lines_staff_read on public.finance_journal_lines for select to authenticated using(public.has_role('ADMIN') or public.has_role('FINANCE'));

create or replace function public.post_finance_journal(target_source_type text,target_source_id text,target_event_kind text,target_occurred_at timestamptz,target_description text,target_reference text,target_lines jsonb,target_metadata jsonb default '{}'::jsonb)
returns uuid language plpgsql security definer set search_path=public as $$
declare entry_id uuid; total_debit bigint;total_credit bigint;line jsonb;
begin
  if jsonb_typeof(target_lines)<>'array' or jsonb_array_length(target_lines)<2 then raise exception 'A journal entry requires at least two lines';end if;
  select coalesce(sum((x->>'debit_paise')::bigint),0),coalesce(sum((x->>'credit_paise')::bigint),0) into total_debit,total_credit from jsonb_array_elements(target_lines)x;
  if total_debit<=0 or total_debit<>total_credit then raise exception 'Journal entry is not balanced: debit %, credit %',total_debit,total_credit;end if;
  insert into public.finance_journal_entries(occurred_at,event_kind,description,source_type,source_id,external_reference,metadata,created_by)
  values(coalesce(target_occurred_at,now()),target_event_kind,target_description,target_source_type,target_source_id,nullif(trim(target_reference),''),coalesce(target_metadata,'{}'::jsonb),auth.uid())
  on conflict(source_type,source_id,event_kind) do nothing returning id into entry_id;
  if entry_id is null then select id into entry_id from public.finance_journal_entries where source_type=target_source_type and source_id=target_source_id and event_kind=target_event_kind;return entry_id;end if;
  for line in select * from jsonb_array_elements(target_lines) loop
    insert into public.finance_journal_lines(journal_entry_id,account_code,provider_id,subscription_id,debit_paise,credit_paise,memo)
    values(entry_id,line->>'account_code',nullif(line->>'provider_id','')::uuid,nullif(line->>'subscription_id','')::uuid,coalesce((line->>'debit_paise')::bigint,0),coalesce((line->>'credit_paise')::bigint,0),line->>'memo');
  end loop;return entry_id;
end;$$;

create or replace function public.journal_subscription_payment() returns trigger language plpgsql security definer set search_path=public as $$
begin
  if new.payment_reference is not null and new.total_paid_paise>0 then perform public.post_finance_journal('subscription',new.id::text,'PAYMENT_CAPTURED',coalesce(new.created_at,now()),'Customer subscription payment captured',new.payment_reference,
    jsonb_build_array(jsonb_build_object('account_code','1000','debit_paise',new.total_paid_paise,'subscription_id',new.id),jsonb_build_object('account_code','2000','credit_paise',new.total_paid_paise,'subscription_id',new.id)),jsonb_build_object('provider_id',new.provider_id));end if;return new;
end;$$;
drop trigger if exists subscription_payment_journal on public.customer_subscriptions;
create trigger subscription_payment_journal after insert on public.customer_subscriptions for each row execute function public.journal_subscription_payment();

create or replace function public.journal_provider_financial_event() returns trigger language plpgsql security definer set search_path=public as $$
declare lines jsonb;
begin
  if new.entry_type in ('MEAL_EARNING','REVERSAL') then
    lines=jsonb_build_array(jsonb_build_object('account_code','2000','debit_paise',greatest(new.gross_paise,0),'provider_id',new.provider_id,'subscription_id',new.subscription_id),jsonb_build_object('account_code','2100','credit_paise',greatest(new.provider_net_paise,0),'provider_id',new.provider_id,'subscription_id',new.subscription_id),jsonb_build_object('account_code','4000','credit_paise',greatest(new.commission_paise,0),'provider_id',new.provider_id,'subscription_id',new.subscription_id));
    if new.gross_paise>0 then perform public.post_finance_journal('provider_ledger',new.id::text,'MEAL_DELIVERED',new.created_at,'Delivered meal earning recognized',new.external_reference,lines,jsonb_build_object('meal_id',new.meal_id));end if;
  elsif new.entry_type='PAYOUT' then
    perform public.post_finance_journal('provider_ledger',new.id::text,'PROVIDER_PAYOUT',new.created_at,'Provider payout completed',new.external_reference,jsonb_build_array(jsonb_build_object('account_code','2100','debit_paise',abs(new.provider_net_paise),'provider_id',new.provider_id),jsonb_build_object('account_code','1010','credit_paise',abs(new.provider_net_paise),'provider_id',new.provider_id)),new.metadata);
  elsif new.entry_type='ADVANCE_DISBURSEMENT' then
    perform public.post_finance_journal('provider_ledger',new.id::text,'ADVANCE_DISBURSED',new.created_at,'Provider advance disbursed',new.external_reference,jsonb_build_array(jsonb_build_object('account_code','1100','debit_paise',abs(new.provider_net_paise),'provider_id',new.provider_id),jsonb_build_object('account_code','1010','credit_paise',abs(new.provider_net_paise),'provider_id',new.provider_id)),new.metadata);
  end if;return new;
end;$$;
drop trigger if exists provider_financial_event_journal on public.provider_financial_ledger;
create trigger provider_financial_event_journal after insert on public.provider_financial_ledger for each row execute function public.journal_provider_financial_event();

create or replace function public.journal_advance_recovery() returns trigger language plpgsql security definer set search_path=public as $$
declare recovered_delta bigint;
begin recovered_delta=new.recovered_paise-old.recovered_paise;if recovered_delta>0 then perform public.post_finance_journal('advance_request',new.id::text,'ADVANCE_RECOVERY_'||new.recovered_paise::text,now(),'Advance recovered from provider earnings',new.payment_reference,jsonb_build_array(jsonb_build_object('account_code','2100','debit_paise',recovered_delta,'provider_id',new.provider_id),jsonb_build_object('account_code','1100','credit_paise',recovered_delta,'provider_id',new.provider_id)),jsonb_build_object('remaining_paise',coalesce(new.approved_amount_paise,new.amount_paise)-new.recovered_paise));end if;return new;end;$$;
drop trigger if exists advance_recovery_journal on public.provider_advance_requests;
create trigger advance_recovery_journal after update of recovered_paise on public.provider_advance_requests for each row when(new.recovered_paise>old.recovered_paise) execute function public.journal_advance_recovery();

create table public.finance_external_transactions(
 id uuid primary key default gen_random_uuid(),transaction_type text not null check(transaction_type in ('COLLECTION','PAYOUT','REFUND','ADVANCE','GATEWAY_FEE')),
 external_reference text not null,amount_paise bigint not null check(amount_paise>0),occurred_at timestamptz not null,status text not null default 'SETTLED',source text not null default 'MANUAL',note text,recorded_by uuid references auth.users(id),created_at timestamptz not null default now(),unique(transaction_type,external_reference)
);
alter table public.finance_external_transactions enable row level security;
create policy finance_external_staff_read on public.finance_external_transactions for select to authenticated using(public.has_role('ADMIN') or public.has_role('FINANCE'));

create or replace function public.admin_record_external_transaction(target_type text,target_reference text,target_amount_paise bigint,target_occurred_at timestamptz,target_status text default 'SETTLED',target_note text default null)
returns jsonb language plpgsql security definer set search_path=public as $$declare transaction_id uuid;normalized text:=upper(trim(target_type));begin
 if not(public.has_role('ADMIN') or public.has_role('FINANCE'))then raise exception 'Finance access is required';end if;if target_amount_paise<=0 or nullif(trim(target_reference),'')is null then raise exception 'Reference and positive amount are required';end if;
 insert into public.finance_external_transactions(transaction_type,external_reference,amount_paise,occurred_at,status,note,recorded_by)values(normalized,trim(target_reference),target_amount_paise,coalesce(target_occurred_at,now()),upper(trim(target_status)),nullif(trim(target_note),''),auth.uid())
 on conflict(transaction_type,external_reference)do update set amount_paise=excluded.amount_paise,occurred_at=excluded.occurred_at,status=excluded.status,note=excluded.note,recorded_by=auth.uid() returning id into transaction_id;
 insert into public.audit_logs(actor_id,action,entity_type,entity_id,after_data)values(auth.uid(),'EXTERNAL_TRANSACTION_RECORDED','finance_external_transaction',transaction_id::text,jsonb_build_object('type',normalized,'reference',target_reference,'amount_paise',target_amount_paise));return jsonb_build_object('id',transaction_id);end;$$;

create or replace function public.admin_finance_reconciliation(target_from date default current_date-7,target_to date default current_date)
returns jsonb language plpgsql stable security definer set search_path=public as $$declare result jsonb;begin
 if not(public.has_role('ADMIN') or public.has_role('FINANCE'))then raise exception 'Finance access is required';end if;
 with expected as(
  select 'COLLECTION'::text transaction_type,s.payment_reference reference,s.total_paid_paise amount_paise,s.created_at occurred_at,'subscription' source_type,s.id::text source_id from public.customer_subscriptions s where s.payment_reference is not null and s.created_at::date between target_from and target_to
  union all select 'PAYOUT',r.payment_reference,r.amount_paise,r.paid_at,'payout_request',r.id::text from public.provider_payout_requests r where r.status='PAID' and r.paid_at::date between target_from and target_to
  union all select 'ADVANCE',a.payment_reference,coalesce(a.approved_amount_paise,a.amount_paise),a.disbursed_at,'advance_request',a.id::text from public.provider_advance_requests a where a.status in('DISBURSED','RECOVERED')and a.disbursed_at::date between target_from and target_to
 ),comparison as(select e.*,x.id external_id,x.amount_paise external_amount,case when x.id is null then'MISSING_EXTERNAL'when x.amount_paise<>e.amount_paise then'AMOUNT_MISMATCH'else'MATCHED'end reconciliation_status from expected e left join public.finance_external_transactions x on x.transaction_type=e.transaction_type and x.external_reference=e.reference),
 journal_balance as(select j.id,j.journal_number,j.occurred_at,j.event_kind,j.description,j.external_reference,coalesce(sum(l.debit_paise),0)debits,coalesce(sum(l.credit_paise),0)credits from public.finance_journal_entries j join public.finance_journal_lines l on l.journal_entry_id=j.id where j.occurred_at::date between target_from and target_to group by j.id)
 select jsonb_build_object('from',target_from,'to',target_to,'expected_count',(select count(*)from comparison),'matched_count',(select count(*)from comparison where reconciliation_status='MATCHED'),'exception_count',(select count(*)from comparison where reconciliation_status<>'MATCHED'),'expected_amount_paise',(select coalesce(sum(amount_paise),0)from comparison),'exceptions',coalesce((select jsonb_agg(to_jsonb(c)order by occurred_at desc)from comparison c where reconciliation_status<>'MATCHED'),'[]'::jsonb),'journal_entries',coalesce((select jsonb_agg(to_jsonb(j)order by occurred_at desc)from journal_balance j),'[]'::jsonb),'unbalanced_journal_count',(select count(*)from journal_balance where debits<>credits))into result;return result;end;$$;

create or replace function public.admin_reverse_finance_journal(target_entry uuid,target_reason text)
returns jsonb language plpgsql security definer set search_path=public as $$declare original public.finance_journal_entries;reversal_id uuid;line record;begin
 if not(public.has_role('ADMIN') or public.has_role('FINANCE'))then raise exception 'Finance access is required';end if;if nullif(trim(target_reason),'')is null then raise exception 'Reversal reason is required';end if;
 select*into original from public.finance_journal_entries where id=target_entry;if original.id is null then raise exception 'Journal entry not found';end if;if exists(select 1 from public.finance_journal_entries where reverses_entry_id=target_entry)then raise exception 'Journal entry is already reversed';end if;
 insert into public.finance_journal_entries(occurred_at,event_kind,description,source_type,source_id,external_reference,reverses_entry_id,metadata,created_by)values(now(),'REVERSAL','Reversal: '||trim(target_reason),'journal_entry',target_entry::text,original.external_reference,target_entry,jsonb_build_object('reason',trim(target_reason)),auth.uid())returning id into reversal_id;
 for line in select*from public.finance_journal_lines where journal_entry_id=target_entry loop insert into public.finance_journal_lines(journal_entry_id,account_code,provider_id,subscription_id,debit_paise,credit_paise,memo)values(reversal_id,line.account_code,line.provider_id,line.subscription_id,line.credit_paise,line.debit_paise,'Reversal of journal #'||original.journal_number);end loop;
 insert into public.audit_logs(actor_id,action,entity_type,entity_id,after_data)values(auth.uid(),'FINANCE_JOURNAL_REVERSED','finance_journal_entry',target_entry::text,jsonb_build_object('reversal_id',reversal_id,'reason',target_reason));return jsonb_build_object('reversal_id',reversal_id);end;$$;

revoke all on function public.post_finance_journal(text,text,text,timestamptz,text,text,jsonb,jsonb),public.admin_record_external_transaction(text,text,bigint,timestamptz,text,text),public.admin_finance_reconciliation(date,date),public.admin_reverse_finance_journal(uuid,text)from public;
grant execute on function public.admin_record_external_transaction(text,text,bigint,timestamptz,text,text),public.admin_finance_reconciliation(date,date),public.admin_reverse_finance_journal(uuid,text)to authenticated;
