-- Align provider final lists/routes with the existing 08:00 IST customer cutoff.
-- Preserve function signatures, ownership, ACLs and the existing security checks.
do $migration$
declare
  signature text;
  definition text;
begin
  foreach signature in array array[
    'public.provider_daily_dashboard_commission_v1(public.meal_slot,date)',
    'public.provider_auto_assign_routes(public.meal_slot,date)'
  ] loop
    if to_regprocedure(signature) is null and signature='public.provider_auto_assign_routes(public.meal_slot,date)' then
      -- Some linked environments lack this RPC; restored by the following migration.
      continue;
    end if;
    select pg_get_functiondef(signature::regprocedure) into definition;
    if position('time ''07:00''' in definition) = 0 then
      raise exception 'Expected provider cutoff not found in %. Migration stopped.', signature;
    end if;
    execute replace(definition, 'time ''07:00''', 'time ''08:00''');
  end loop;
end
$migration$;
