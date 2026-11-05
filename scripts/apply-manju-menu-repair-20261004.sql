-- Targeted repair of a verified, previously approved request. Transactional:
-- any dietary conflict or newer request rolls back publication and notifications.
begin;
create temp table menu_repair_result(result jsonb) on commit drop;
do $$
declare request_id uuid:='9c63203c-d694-4d2c-9470-22171cbd53d8';
 menu_id uuid:='91aaaf13-2c7e-4394-8219-b6ce61cacef2';
 provider uuid:='9726ff16-eead-4e63-ba58-e797e8cb97ed';
 before_snapshot jsonb; impact jsonb; restored integer;
begin
 perform 1 from public.providers where id=provider for update;
 if not exists(select 1 from public.provider_change_requests where id=request_id and provider_id=provider and status='APPROVED') then raise exception 'Expected approved request not found'; end if;
 if exists(select 1 from public.provider_menus where provider_id=provider and status='APPROVED' and id<>menu_id) then raise exception 'Another live menu exists; inspect before repair'; end if;
 select jsonb_build_object('menu',to_jsonb(m),'choices',(select jsonb_agg(to_jsonb(c)) from public.menu_days d join public.menu_day_choices c on c.menu_day_id=d.id where d.menu_id=m.id),
  'photos',(select jsonb_agg(to_jsonb(pm)) from public.provider_media pm where pm.change_request_id=request_id)) into before_snapshot from public.provider_menus m where id=menu_id;
 restored:=public.reconcile_request_main_courses(request_id);
 if public.checked_request_menu(request_id,true)<>menu_id then raise exception 'Unexpected menu selected'; end if;
 update public.provider_menus set status='APPROVED',valid_until=null,updated_at=now() where id=menu_id;
 impact:=public.sync_customer_menu(menu_id,request_id::text,true);
 insert into public.audit_logs(action,entity_type,entity_id,before_data,after_data)
 values('APPROVED_MENU_MAINTENANCE_REPAIR','provider_menus',menu_id::text,before_snapshot,
  jsonb_build_object('request_id',request_id,'restored_alternatives',restored,'impact',impact,'reason','Restore approved payload alternatives omitted by legacy ordinal/duplicate merging, and reverse later empty-request archival. User-authorized maintenance.'));
 insert into menu_repair_result values(jsonb_build_object('menu_live',true,'restored_alternatives',restored,'customers',impact->'customers','changed_rows',jsonb_array_length(impact->'changes'),'conflicts',impact->'conflicts'));
end $$;
select result from menu_repair_result;
commit;
