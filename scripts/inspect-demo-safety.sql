select table_name,column_name,data_type,is_nullable,column_default
from information_schema.columns where table_schema='public'
and table_name in ('customer_subscriptions','subscription_meals','provider_financial_ledger') order by table_name,ordinal_position;
select c.relname table_name,t.tgname,pg_get_triggerdef(t.oid) definition
from pg_trigger t join pg_class c on c.oid=t.tgrelid join pg_namespace n on n.oid=c.relnamespace
where n.nspname='public' and not t.tgisinternal and c.relname in ('customer_subscriptions','subscription_meals','provider_financial_ledger','profiles');
select p.proname from pg_proc p join pg_namespace n on n.oid=p.pronamespace where n.nspname='public'
and (p.prosrc ilike '%wallet_charged_at%' or p.prosrc ilike '%cron%' or p.prosrc ilike '%is_test%') order by p.proname;
select jobname,schedule,command from cron.job;
