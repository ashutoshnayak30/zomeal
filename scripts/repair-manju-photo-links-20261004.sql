begin;
create temp table photo_repair_result(result jsonb) on commit drop;
do $$
declare before_snapshot jsonb; repaired integer;
begin
 perform 1 from public.providers where id='9726ff16-eead-4e63-ba58-e797e8cb97ed' for update;
 select jsonb_agg(to_jsonb(pm)) into before_snapshot
 from public.provider_media pm join public.menu_items old_item on old_item.id=pm.menu_item_id
 where pm.provider_id='9726ff16-eead-4e63-ba58-e797e8cb97ed'
 and pm.change_request_id='39f56661-8f06-41e5-b554-d5f80ec72871'
 and pm.status='APPROVED' and pm.media_type='MENU_ITEM' and trim(pm.alt_text)<>old_item.name;
 update public.provider_media pm set menu_item_id=i.id,is_primary=false,updated_at=now()
 from public.menu_items i
 where pm.provider_id='9726ff16-eead-4e63-ba58-e797e8cb97ed'
 and pm.change_request_id='39f56661-8f06-41e5-b554-d5f80ec72871'
 and pm.status='APPROVED' and pm.media_type='MENU_ITEM'
 and i.provider_id=pm.provider_id and i.name=trim(pm.alt_text)
 and i.status='APPROVED' and i.category='MAIN_COURSE' and pm.menu_item_id<>i.id
 and exists(select 1 from public.menu_days d join public.menu_day_choices c on c.menu_day_id=d.id
 join public.provider_menus m on m.id=d.menu_id
 where m.id='91aaaf13-2c7e-4394-8219-b6ce61cacef2' and m.status='APPROVED' and c.menu_item_id=i.id);
 get diagnostics repaired=row_count;
 if repaired not in (0,6) then raise exception 'Unexpected photo count: %',repaired; end if;
 if repaired>0 then
 insert into public.audit_logs(action,entity_type,entity_id,before_data,after_data)
 values('APPROVED_MENU_PHOTO_LINK_REPAIR','providers','9726ff16-eead-4e63-ba58-e797e8cb97ed',before_snapshot,
 jsonb_build_object('repaired',repaired,'reason','Match approved photo labels to exact approved live dish names; no images or meals deleted.'));
 end if;
 insert into photo_repair_result values(jsonb_build_object('repaired',repaired));
end $$;
select result from photo_repair_result;
commit;
