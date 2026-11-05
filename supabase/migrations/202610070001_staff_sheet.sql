-- Atomic, retry-safe multi-row staff editing using the existing private API.
create function public.provider_staff_save_sheet(rows jsonb) returns jsonb
language plpgsql security definer set search_path=public as $$
declare p uuid:=kitchen_book_provider();r jsonb;begin
 if jsonb_typeof(rows) is distinct from 'array' then raise exception 'Expected staff rows';end if;
 if jsonb_array_length(rows)<1 or jsonb_array_length(rows)>50 then raise exception 'Save between 1 and 50 staff rows';end if;
 if (select count(distinct value->>'id') from jsonb_array_elements(rows))<>jsonb_array_length(rows) then raise exception 'Each staff row needs a unique ID';end if;
 perform 1 from providers where id=p for update;
 for r in select value from jsonb_array_elements(rows) loop perform provider_staff_save(r);end loop;
 return jsonb_build_object('saved',true,'count',jsonb_array_length(rows));
end $$;
revoke all on function provider_staff_save_sheet(jsonb) from public,anon;
grant execute on function provider_staff_save_sheet(jsonb) to authenticated;
