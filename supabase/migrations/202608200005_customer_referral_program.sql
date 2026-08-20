insert into public.platform_settings(setting_key,value,effective_from)
select 'customer_referral_program','{"verified_install_reward_paise":2500,"first_subscription_reward_paise":10000,"cycle_cap_paise":100000,"enabled":true}'::jsonb,now()
where not exists(select 1 from public.platform_settings where setting_key='customer_referral_program');

create or replace function public.customer_referral_program()
returns jsonb language sql stable security definer set search_path=public as $$
select coalesce((select value from public.platform_settings where setting_key='customer_referral_program'
  and effective_from<=now() and(effective_until is null or effective_until>now()) order by effective_from desc limit 1),
  '{"verified_install_reward_paise":2500,"first_subscription_reward_paise":10000,"cycle_cap_paise":100000,"enabled":true}'::jsonb);
$$;
grant execute on function public.customer_referral_program() to authenticated;
