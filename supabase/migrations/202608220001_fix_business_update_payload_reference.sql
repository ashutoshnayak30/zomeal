-- Repair the already-deployed active-provider submission RPC. Function
-- parameters are reliably addressable as $1 inside PL/pgSQL SQL statements;
-- the previous block-label qualification was interpreted as a table alias.

do $$
declare
  function_definition text;
begin
  select pg_get_functiondef('public.provider_submit_business_update(jsonb)'::regprocedure)
  into function_definition;

  if function_definition is null then
    raise exception 'provider_submit_business_update(jsonb) was not found';
  end if;

  function_definition := replace(function_definition,'business_update.payload','$1');

  if position('business_update.payload' in function_definition)>0 then
    raise exception 'Could not repair the provider business update payload reference';
  end if;

  execute function_definition;
end;
$$;

notify pgrst,'reload schema';
