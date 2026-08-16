-- Use an explicit PL/pgSQL block label for submission-local variables.

do $$
declare
  function_definition text;
  declare_position integer;
begin
  select pg_get_functiondef('public.submit_provider_mobile_application(jsonb)'::regprocedure)
  into function_definition;

  if function_definition is null then
    raise exception 'submit_provider_mobile_application(jsonb) was not found';
  end if;

  declare_position := position('declare' in lower(function_definition));
  if declare_position=0 then
    raise exception 'Could not locate the mobile submission declaration block';
  end if;
  function_definition := substring(function_definition from 1 for declare_position-1)
    || '<<mobile_submit>>' || chr(10)
    || substring(function_definition from declare_position);
  function_definition := replace(function_definition,'submit_provider_mobile_application.provider_id','mobile_submit.provider_id');
  function_definition := replace(function_definition,'submit_provider_mobile_application.menu_id','mobile_submit.menu_id');

  execute function_definition;
end;
$$;
