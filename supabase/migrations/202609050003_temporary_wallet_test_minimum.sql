-- Temporary launch testing rule: allow ₹5 wallet recharges. Restore to 50000 paise before public release.
do $$
declare function_sql text; updated_sql text;
begin
  select pg_get_functiondef('public.apply_captured_payment(uuid,uuid)'::regprocedure) into function_sql;
  updated_sql := replace(function_sql,
    'pay.amount_paise not between 50000 and 1000000',
    'pay.amount_paise not between 500 and 1000000');
  if updated_sql = function_sql then raise exception 'Wallet recharge validation clause was not found'; end if;
  execute updated_sql;
end $$;

comment on function public.apply_captured_payment(uuid,uuid) is
'Applies captured payments once. Wallet recharge minimum temporarily reduced to ₹5 for controlled testing.';
