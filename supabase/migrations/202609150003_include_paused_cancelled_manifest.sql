-- Include excluded meals for visibility only; operational totals remain unchanged.
do $migration$
declare definition text;
begin
 select pg_get_functiondef('public.provider_daily_dashboard(public.meal_slot,date)'::regprocedure) into definition;
 if position('and meal.status not in (''PAUSED'',''CANCELLED'')' in definition)=0 then
   raise exception 'Expected manifest filter not found';
 end if;
 execute replace(definition,'and meal.status not in (''PAUSED'',''CANCELLED'')','');
end;
$migration$;
