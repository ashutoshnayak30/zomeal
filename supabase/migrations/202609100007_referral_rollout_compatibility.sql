-- Keep the already-installed customer APK and cached admin JavaScript working
-- while the two-stage referral programme rolls out.

alter function public.customer_referral_dashboard()
  rename to customer_referral_dashboard_two_stage;

create or replace function public.customer_referral_dashboard()
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  dashboard jsonb;
begin
  dashboard := public.customer_referral_dashboard_two_stage();

  return dashboard || jsonb_build_object(
    -- Legacy customer APK aliases. The new APK reads the stage-specific keys.
    'referrer_reward_paise', coalesce(
      (dashboard ->> 'first_subscription_referrer_reward_paise')::integer,
      0
    ),
    'referred_reward_paise', coalesce(
      (dashboard ->> 'verified_signup_referred_paise')::integer,
      0
    )
  );
end;
$$;

revoke all on function public.customer_referral_dashboard_two_stage() from public;
revoke all on function public.customer_referral_dashboard() from public;
grant execute on function public.customer_referral_dashboard() to authenticated;

-- Compatibility overload for an admin page that was opened before deployment.
-- It preserves the new verified-signup inviter amount and maps the two legacy
-- reward boxes to the paid-subscription inviter bonus and signup customer credit.
create or replace function public.admin_update_referral_settings(
  target_enabled boolean,
  target_referrer_reward_paise bigint,
  target_referred_reward_paise bigint,
  target_cycle_cap_paise bigint,
  target_cycle_days integer
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  programme jsonb;
begin
  programme := public.customer_referral_program();

  return public.admin_update_referral_settings(
    target_enabled,
    coalesce((programme ->> 'verified_referrer_reward_paise')::bigint, 0),
    target_referred_reward_paise,
    target_referrer_reward_paise,
    target_cycle_cap_paise,
    target_cycle_days
  );
end;
$$;

revoke all on function public.admin_update_referral_settings(boolean, bigint, bigint, bigint, integer) from public;
grant execute on function public.admin_update_referral_settings(boolean, bigint, bigint, bigint, integer) to authenticated;

notify pgrst, 'reload schema';
