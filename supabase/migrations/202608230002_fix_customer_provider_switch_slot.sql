-- The provider-switch RPC uses slot_value as both a PL/pgSQL loop variable
-- and as the column exposed by unnest() while creating missing future meals.
-- Prefer the SQL column when it exists; in the other statements there is no
-- slot_value column, so PL/pgSQL continues to use the loop variable.

do $migration$
declare
  function_definition text;
begin
  select pg_get_functiondef(
    'public.customer_switch_provider(uuid,uuid,uuid,jsonb)'::regprocedure
  ) into function_definition;

  if function_definition is null then
    raise exception 'customer_switch_provider(uuid,uuid,uuid,jsonb) was not found';
  end if;

  -- Make the migration safe to re-run during local development.
  if position('#variable_conflict use_column' in function_definition) = 0 then
    function_definition := regexp_replace(
      function_definition,
      'AS[[:space:]]+\$function\$[[:space:]]*',
      E'AS $function$\n#variable_conflict use_column\n',
      'i'
    );
  end if;

  if position('#variable_conflict use_column' in function_definition) = 0 then
    raise exception 'Could not add the PL/pgSQL conflict directive';
  end if;

  execute function_definition;
end;
$migration$;

notify pgrst, 'reload schema';
