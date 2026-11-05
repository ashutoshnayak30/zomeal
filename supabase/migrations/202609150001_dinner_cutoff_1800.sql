-- Temporary testing policy: dinner closes daily at 18:00 Asia/Kolkata.
-- Change only inspected dinner literals; preserve all ACLs and other logic.
do $migration$
declare signature text; definition text;
begin
  foreach signature in array array[
    'public.customer_update_delivery_address(jsonb)',
    'public.provider_daily_dashboard_commission_v1(public.meal_slot,date)',
    'public.customer_select_daily_meal(uuid,uuid)',
    'public.customer_switch_provider(uuid,uuid,uuid,jsonb)',
    'public.customer_pause_subscription_meals(uuid,date[],text)',
    'public.credit_captured_payment_to_wallet(uuid,uuid,text)'
  ] loop
    select pg_get_functiondef(signature::regprocedure) into definition;
    if position('16:00' in definition)=0 then
      raise exception 'Expected old dinner cutoff missing in %; no changes committed',signature;
    end if;
    execute replace(replace(definition,'16:00','18:00'),'4:00 PM','6:00 PM');
  end loop;
end;
$migration$;
