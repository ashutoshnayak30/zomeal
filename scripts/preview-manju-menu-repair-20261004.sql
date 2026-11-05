-- Scoped maintenance preview. Every write is rolled back; no push is queued.
begin;
select id from public.providers where id='9726ff16-eead-4e63-ba58-e797e8cb97ed' for update;
select public.reconcile_request_main_courses('9c63203c-d694-4d2c-9470-22171cbd53d8');
select public.checked_request_menu('9c63203c-d694-4d2c-9470-22171cbd53d8',true);
update public.provider_menus set status='APPROVED',valid_until=null
where id='91aaaf13-2c7e-4394-8219-b6ce61cacef2' and change_request_id='9c63203c-d694-4d2c-9470-22171cbd53d8';
select jsonb_build_object('customers',impact->'customers','conflicts',impact->'conflicts','changed_rows',jsonb_array_length(impact->'changes')) as repair_preview
from (select public.sync_customer_menu('91aaaf13-2c7e-4394-8219-b6ce61cacef2','9c63203c-d694-4d2c-9470-22171cbd53d8',false) impact) x;
rollback;
