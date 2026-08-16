-- Resolve PL/pgSQL variable/column name collisions in the mobile submission RPC.
-- The original function intentionally uses qualified block variables on the RHS;
-- prefer table columns when an unqualified name exists in a SQL statement.

do $$
declare
  function_definition text;
begin
  select pg_get_functiondef('public.submit_provider_mobile_application(jsonb)'::regprocedure)
  into function_definition;

  if function_definition is null then
    raise exception 'submit_provider_mobile_application(jsonb) was not found';
  end if;

  function_definition := replace(
    function_definition,
    'AS $function$' || chr(10),
    'AS $function$' || chr(10) || '#variable_conflict use_column' || chr(10)
  );

  if position('#variable_conflict use_column' in function_definition)=0 then
    raise exception 'Could not patch the mobile submission function definition';
  end if;

  execute function_definition;
end;
$$;
