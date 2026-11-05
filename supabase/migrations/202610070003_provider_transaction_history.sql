-- Posting-month history, authenticated kitchen scope and stable keyset pagination.
create index if not exists provider_ledger_posting_history_idx on public.provider_financial_ledger(provider_id,created_at desc,id desc);
create or replace function public.provider_transaction_months() returns jsonb
language plpgsql stable security definer set search_path=public as $$
declare p uuid:=kitchen_book_provider(); begin
 return jsonb_build_object('months',coalesce((select jsonb_agg(to_jsonb(x) order by month_start desc) from (
 select date_trunc('month',created_at at time zone 'Asia/Kolkata')::date month_start,count(*) entry_count
 from provider_financial_ledger where provider_id=p group by 1)x),'[]'::jsonb));
end $$;
create or replace function public.provider_transaction_page(target_month date,before_time timestamptz default null,before_id uuid default null) returns jsonb
language plpgsql stable security definer set search_path=public as $$
declare p uuid:=kitchen_book_provider(); rows jsonb; begin
 if target_month is null or target_month<>date_trunc('month',target_month)::date or (before_time is null)<>(before_id is null) then raise exception 'Invalid history request'; end if;
 select coalesce(jsonb_agg(to_jsonb(x) order by created_at desc,id desc),'[]'::jsonb) into rows from (
 select id,entry_type,service_date,meal_slot,gross_paise,commission_paise,provider_net_paise,created_at
 from provider_financial_ledger where provider_id=p
 and created_at >= (target_month::timestamp at time zone 'Asia/Kolkata')
 and created_at < ((target_month+interval '1 month') at time zone 'Asia/Kolkata')
 and (before_time is null or (created_at,id)<(before_time,before_id))
 order by created_at desc,id desc limit 51)x;
 return jsonb_build_object('entries',rows,'has_more',jsonb_array_length(rows)>50);
end $$;
revoke all on function public.provider_transaction_months() from public,anon;
revoke all on function public.provider_transaction_page(date,timestamptz,uuid) from public,anon;
grant execute on function public.provider_transaction_months() to authenticated;
grant execute on function public.provider_transaction_page(date,timestamptz,uuid) to authenticated;
