create or replace function public.admin_menu_sync(target_request uuid,apply_changes boolean default false) returns jsonb
language plpgsql security definer set search_path=public as $$
declare r provider_change_requests; m provider_menus; target uuid; result jsonb; repair boolean;
begin
 if not(has_role('ADMIN') or has_role('OPERATIONS')) then raise exception 'Admin access required'; end if;
 select * into r from provider_change_requests where id=target_request;
 if r.id is null then raise exception 'Request not found'; end if;
 perform 1 from providers where id=r.provider_id for update;
 if apply_changes and r.status<>'APPROVED' then raise exception 'Approve the request before syncing'; end if;
 target:=checked_request_menu(target_request,r.status='APPROVED');
 select * into m from provider_menus where id=target for update;
 repair:=r.status='APPROVED' and (m.status<>'APPROVED' or m.valid_until is not null);
 if m.valid_from>current_date then raise exception 'Future menus cannot be published early'; end if;
 if repair and not apply_changes then
  -- Preview using restored visibility inside a rolled-back subtransaction.
  -- No catalogue mutation, notification or audit entry survives this block.
  begin
   update provider_menus set status='APPROVED',valid_until=null where id=target;
   result:=sync_customer_menu(target,target_request::text,false);
   raise sqlstate 'ZX001' using message='Rollback preview-only visibility';
  exception when sqlstate 'ZX001' then null;
  end;
  return result||jsonb_build_object('requires_repair',true,
   'message','Approved menu needs restoration. Resolve any dietary conflicts below before publishing and syncing.');
 end if;
 if apply_changes then
  update provider_menus set status='ARCHIVED',valid_until=greatest(valid_from,current_date),updated_at=now()
   where provider_id=r.provider_id and id<>target and status='APPROVED';
  update provider_menus set status='APPROVED',valid_until=null,updated_at=now() where id=target;
 end if;
 result:=sync_customer_menu(target,target_request::text,apply_changes);
 if apply_changes then
  insert into audit_logs(actor_id,action,entity_type,entity_id,before_data,after_data)
   values(auth.uid(),'APPROVED_MENU_PUBLISHED_AND_SYNCED','provider_menus',target::text,to_jsonb(m),result);
 end if;
 return result||jsonb_build_object('menu_live',apply_changes or (m.status='APPROVED' and not repair),'repaired',repair and apply_changes);
end $$;
revoke all on function public.admin_menu_sync(uuid,boolean) from public,anon;
grant execute on function public.admin_menu_sync(uuid,boolean) to authenticated;


notify pgrst,'reload schema';
