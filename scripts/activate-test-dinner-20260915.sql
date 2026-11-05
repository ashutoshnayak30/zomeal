begin;
do $test$
declare meal public.subscription_meals;
begin
 select * into meal from public.subscription_meals where id='51e107b4-e344-4e4b-8043-5a458eaabcf8' for update;
 if meal.customer_id is distinct from '50cb9594-bb66-430d-a279-54791829fcd5'::uuid
   or meal.service_date is distinct from date '2026-09-15' or meal.meal_slot::text is distinct from 'DINNER'
   or meal.status is distinct from 'PAUSED' or meal.wallet_charged_at is not null or meal.delivered_at is not null
   or (now() at time zone 'Asia/Kolkata')::date<>date '2026-09-15'
   or (now() at time zone 'Asia/Kolkata')::time>=time '18:00' then raise exception 'Dinner changed or cutoff passed; no update'; end if;
 if not exists(select 1 from public.profiles where id=meal.customer_id and phone='6370648872')
   or not exists(select 1 from public.customer_subscriptions where id=meal.subscription_id and status='ACTIVE') then raise exception 'Account/subscription changed'; end if;
 update public.subscription_meals set status='SCHEDULED',updated_at=now() where id=meal.id;
 insert into public.audit_logs(actor_id,action,entity_type,entity_id,before_data,after_data,metadata)
 values(meal.customer_id,'DEMO_DINNER_REACTIVATED','subscription_meal',meal.id::text,to_jsonb(meal),jsonb_build_object('status','SCHEDULED'),
 jsonb_build_object('run','zomeal-test-wallet-6370648872-20260915','user_requested',true));
end; $test$;
commit;
select id,status,meal_value_paise,wallet_charged_at from public.subscription_meals where id='51e107b4-e344-4e4b-8043-5a458eaabcf8';
