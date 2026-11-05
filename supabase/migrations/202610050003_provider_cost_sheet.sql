-- All filled sheet rows commit together. Existing per-entry UUID/revision
-- protection also makes a lost-response retry safe.
create function public.provider_kitchen_save_sheet(rows jsonb) returns jsonb
language plpgsql security definer set search_path=public as $$
declare p uuid:=kitchen_book_provider(); row_data jsonb; saved_count integer:=0;
begin
 if jsonb_typeof(rows) is distinct from 'array' then raise exception 'Supply expense rows'; end if;
 if jsonb_array_length(rows)<1 or jsonb_array_length(rows)>50 then raise exception 'Save between 1 and 50 rows'; end if;
 if exists(select 1 from jsonb_array_elements(rows) r where jsonb_typeof(r)<>'object' or r->>'kind' is distinct from 'EXPENSE') then
  raise exception 'Only expense rows are allowed in a cost sheet';
 end if;
 if (select count(distinct r->>'id') from jsonb_array_elements(rows) r)<>jsonb_array_length(rows) then
  raise exception 'Every row needs a unique ID';
 end if;
 perform 1 from providers where id=p for update;
 for row_data in select value from jsonb_array_elements(rows) loop
  perform provider_kitchen_save(row_data);
  saved_count:=saved_count+1;
 end loop;
 return jsonb_build_object('saved',true,'count',saved_count);
end $$;
revoke all on function public.provider_kitchen_save_sheet(jsonb) from public,anon;
grant execute on function public.provider_kitchen_save_sheet(jsonb) to authenticated;
