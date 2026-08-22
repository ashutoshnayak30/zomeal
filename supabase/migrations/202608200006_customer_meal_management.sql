create or replace function public.customer_pause_subscription_meals(target_subscription uuid,target_dates date[],target_slot text)
returns jsonb language plpgsql security definer set search_path=public as $$
declare changed integer; normalized text:=upper(trim(target_slot));
begin
  if normalized not in ('LUNCH','DINNER','BOTH') then raise exception 'Choose lunch, dinner or both'; end if;
  if coalesce(array_length(target_dates,1),0)=0 or array_length(target_dates,1)>14 then raise exception 'Choose between 1 and 14 dates'; end if;
  update public.subscription_meals meal set status='PAUSED',updated_at=now()
  where meal.subscription_id=target_subscription and meal.customer_id=auth.uid() and meal.service_date=any(target_dates)
    and(normalized='BOTH' or meal.meal_slot::text=normalized) and meal.status='SCHEDULED'
    and now()<((meal.service_date+case meal.meal_slot when 'LUNCH' then time '07:00' else time '16:00' end) at time zone 'Asia/Kolkata');
  get diagnostics changed=row_count;
  return jsonb_build_object('updated_meals',changed,'status','PAUSED');
end; $$;

grant execute on function public.customer_pause_subscription_meals(uuid,date[],text) to authenticated;
