-- Two-stage referral rewards controlled from Zomeal Admin.
-- "Install" is intentionally verified signup: a raw APK install has no secure
-- user identity and must never create spendable wallet money.

alter table public.customer_referral_claims
  drop constraint if exists customer_referral_claims_status_check;
alter table public.customer_referral_claims
  add constraint customer_referral_claims_status_check
  check(status in ('APPLIED','SIGNUP_REWARDED','REWARDED','CANCELLED','FLAGGED'));
alter table public.customer_referral_claims
  add column if not exists verified_signup_referrer_paise bigint not null default 0 check(verified_signup_referrer_paise>=0),
  add column if not exists verified_signup_referred_paise bigint not null default 0 check(verified_signup_referred_paise>=0),
  add column if not exists subscription_referrer_reward_paise bigint not null default 0 check(subscription_referrer_reward_paise>=0),
  add column if not exists signup_rewarded_at timestamptz;

-- Preserve historic totals while separating already-paid subscription rewards.
update public.customer_referral_claims
set subscription_referrer_reward_paise=referrer_reward_paise
where status='REWARDED' and subscription_referrer_reward_paise=0;

do $$
declare current_value jsonb;
begin
  current_value:=public.customer_referral_program();
  update public.platform_settings set effective_until=now()
  where setting_key='customer_referral_program' and effective_until is null;
  insert into public.platform_settings(setting_key,value,effective_from)
  values('customer_referral_program',current_value||jsonb_build_object(
    'verified_referrer_reward_paise',0,
    'verified_referred_reward_paise',coalesce((current_value->>'referred_reward_paise')::bigint,(current_value->>'verified_install_reward_paise')::bigint,0),
    'first_subscription_referrer_reward_paise',coalesce((current_value->>'referrer_reward_paise')::bigint,(current_value->>'first_subscription_reward_paise')::bigint,0),
    'qualification','VERIFIED_SIGNUP_AND_FIRST_CAPTURED_SUBSCRIPTION',
    'terms_version','2026-09-10'
  ),now());
end $$;

create or replace function public.customer_apply_referral(target_code text)
returns jsonb language plpgsql security definer set search_path=public as $$
declare
  code text:=upper(trim(target_code)); referrer uuid; claim_id uuid; programme jsonb;
  inviter_amount bigint; new_customer_amount bigint; cap bigint; cycle_days integer; already_earned bigint;
begin
  if auth.uid() is null then raise exception 'Customer authentication is required'; end if;
  if not exists(select 1 from auth.users where id=auth.uid() and phone is not null and phone_confirmed_at is not null) then
    raise exception 'Verify your phone number before applying a referral code';
  end if;
  if code !~ '^ZM[A-Z0-9]{8}$' then raise exception 'Enter a valid Zomeal referral code'; end if;
  programme:=public.customer_referral_program();
  if not coalesce((programme->>'enabled')::boolean,false) then raise exception 'The referral programme is currently paused'; end if;
  select customer_id into referrer from public.customer_referral_accounts where referral_code=code;
  if referrer is null then raise exception 'Referral code was not found'; end if;
  if referrer=auth.uid() then raise exception 'You cannot use your own referral code'; end if;
  if exists(select 1 from public.customer_subscriptions where customer_id=auth.uid() and total_paid_paise>0) then
    raise exception 'Referral codes must be applied before the first paid subscription';
  end if;

  insert into public.customer_referral_claims(referrer_id,referred_customer_id,referral_code)
  values(referrer,auth.uid(),code) returning id into claim_id;
  perform public.ensure_customer_referral_account(auth.uid());

  inviter_amount:=greatest(coalesce((programme->>'verified_referrer_reward_paise')::bigint,0),0);
  new_customer_amount:=greatest(coalesce((programme->>'verified_referred_reward_paise')::bigint,0),0);
  cap:=greatest(coalesce((programme->>'cycle_cap_paise')::bigint,0),0);
  cycle_days:=greatest(coalesce((programme->>'cycle_days')::integer,30),1);
  select coalesce(sum(amount_paise),0) into already_earned
  from public.customer_wallet_entries
  where customer_id=referrer and entry_type='REFERRER_REWARD'
    and created_at>=now()-make_interval(days=>cycle_days);
  inviter_amount:=least(inviter_amount,greatest(cap-already_earned,0));

  insert into public.customer_wallets(customer_id) values(referrer) on conflict do nothing;
  insert into public.customer_wallets(customer_id) values(auth.uid()) on conflict do nothing;
  if inviter_amount>0 then
    insert into public.customer_wallet_entries(customer_id,entry_type,amount_paise,description,reference_type,reference_id,metadata)
    values(referrer,'REFERRER_REWARD',inviter_amount,'Referral reward for a verified Zomeal signup','referral_signup',claim_id::text,
      jsonb_build_object('stage','VERIFIED_SIGNUP','referred_customer_id',auth.uid())) on conflict do nothing;
    if found then update public.customer_wallets set balance_paise=balance_paise+inviter_amount,
      lifetime_credit_paise=lifetime_credit_paise+inviter_amount,updated_at=now() where customer_id=referrer; end if;
  end if;
  if new_customer_amount>0 then
    insert into public.customer_wallet_entries(customer_id,entry_type,amount_paise,description,reference_type,reference_id,metadata)
    values(auth.uid(),'REFERRED_REWARD',new_customer_amount,'Welcome reward for joining Zomeal with a referral','referral_signup',claim_id::text,
      jsonb_build_object('stage','VERIFIED_SIGNUP','referrer_id',referrer)) on conflict do nothing;
    if found then update public.customer_wallets set balance_paise=balance_paise+new_customer_amount,
      lifetime_credit_paise=lifetime_credit_paise+new_customer_amount,updated_at=now() where customer_id=auth.uid(); end if;
  end if;
  update public.customer_referral_claims set status='SIGNUP_REWARDED',
    verified_signup_referrer_paise=inviter_amount,verified_signup_referred_paise=new_customer_amount,
    referrer_reward_paise=inviter_amount,referred_reward_paise=new_customer_amount,signup_rewarded_at=now()
  where id=claim_id;
  if inviter_amount>0 then
    insert into public.customer_notifications(customer_id,category,title,message,destination,dedupe_key)
    values(referrer,'Reward','Referral signup reward added','₹'||to_char(inviter_amount/100.0,'FM999999990.00')||' was added after your friend completed verified signup.','wallet','REFERRAL_SIGNUP_INVITER_'||claim_id::text)
    on conflict(customer_id,dedupe_key) do nothing;
  end if;
  if new_customer_amount>0 then
    insert into public.customer_notifications(customer_id,category,title,message,destination,dedupe_key)
    values(auth.uid(),'Reward','Welcome reward added','₹'||to_char(new_customer_amount/100.0,'FM999999990.00')||' was added to your Zomeal wallet.','wallet','REFERRAL_SIGNUP_NEW_'||claim_id::text)
    on conflict(customer_id,dedupe_key) do nothing;
  end if;
  return jsonb_build_object('applied',true,'claim_id',claim_id,'inviter_reward_paise',inviter_amount,
    'new_customer_reward_paise',new_customer_amount,'message','Referral applied and verified-signup rewards were processed.');
exception when unique_violation then raise exception 'A referral code has already been applied to this account';
end; $$;

create or replace function public.process_customer_referral_reward()
returns trigger language plpgsql security definer set search_path=public as $$
declare claim public.customer_referral_claims; programme jsonb; subscription_amount bigint; cap bigint; cycle_days integer; already_earned bigint;
begin
  if new.total_paid_paise<=0 or new.status not in ('ACTIVE','PAUSED') then return new; end if;
  select * into claim from public.customer_referral_claims
  where referred_customer_id=new.customer_id and status in('APPLIED','SIGNUP_REWARDED') for update;
  if claim.id is null then return new; end if;
  programme:=public.customer_referral_program();
  if not coalesce((programme->>'enabled')::boolean,false) then return new; end if;
  subscription_amount:=greatest(coalesce((programme->>'first_subscription_referrer_reward_paise')::bigint,0),0);
  cap:=greatest(coalesce((programme->>'cycle_cap_paise')::bigint,0),0);
  cycle_days:=greatest(coalesce((programme->>'cycle_days')::integer,30),1);
  select coalesce(sum(amount_paise),0) into already_earned from public.customer_wallet_entries
  where customer_id=claim.referrer_id and entry_type='REFERRER_REWARD' and created_at>=now()-make_interval(days=>cycle_days);
  subscription_amount:=least(subscription_amount,greatest(cap-already_earned,0));
  insert into public.customer_wallets(customer_id) values(claim.referrer_id) on conflict do nothing;
  if subscription_amount>0 then
    insert into public.customer_wallet_entries(customer_id,entry_type,amount_paise,description,reference_type,reference_id,metadata)
    values(claim.referrer_id,'REFERRER_REWARD',subscription_amount,'Referral bonus after friend’s first paid subscription','referral_subscription',claim.id::text,
      jsonb_build_object('stage','FIRST_PAID_SUBSCRIPTION','subscription_id',new.id)) on conflict do nothing;
    if found then update public.customer_wallets set balance_paise=balance_paise+subscription_amount,
      lifetime_credit_paise=lifetime_credit_paise+subscription_amount,updated_at=now() where customer_id=claim.referrer_id; end if;
  end if;
  update public.customer_referral_claims set status='REWARDED',qualifying_subscription_id=new.id,
    subscription_referrer_reward_paise=subscription_amount,
    referrer_reward_paise=verified_signup_referrer_paise+subscription_amount,rewarded_at=now()
  where id=claim.id;
  if subscription_amount>0 then
    insert into public.customer_notifications(customer_id,category,title,message,destination,dedupe_key)
    values(claim.referrer_id,'Reward','Subscription referral bonus added','₹'||to_char(subscription_amount/100.0,'FM999999990.00')||' was added after your friend activated their first paid plan.','wallet','REFERRAL_SUBSCRIPTION_'||claim.id::text)
    on conflict(customer_id,dedupe_key) do nothing;
  end if;
  return new;
end; $$;

create or replace function public.customer_referral_dashboard()
returns jsonb language plpgsql security definer set search_path=public as $$
declare code text; programme jsonb; wallet public.customer_wallets; joined integer; signup_rewarded integer; paid integer; earned bigint;
begin
  if auth.uid() is null then raise exception 'Customer authentication is required'; end if;
  code:=public.ensure_customer_referral_account(auth.uid());programme:=public.customer_referral_program();
  select * into wallet from public.customer_wallets where customer_id=auth.uid();
  select count(*),count(*) filter(where status in('SIGNUP_REWARDED','REWARDED')),count(*) filter(where status='REWARDED')
  into joined,signup_rewarded,paid from public.customer_referral_claims where referrer_id=auth.uid();
  select coalesce(sum(amount_paise),0) into earned from public.customer_wallet_entries
  where customer_id=auth.uid() and entry_type in('REFERRER_REWARD','REFERRED_REWARD');
  return jsonb_build_object(
    'enabled',coalesce((programme->>'enabled')::boolean,false),'referral_code',code,'share_link','https://zomeal.in/?ref='||code,
    'verified_referrer_reward_paise',coalesce((programme->>'verified_referrer_reward_paise')::bigint,0),
    'verified_referred_reward_paise',coalesce((programme->>'verified_referred_reward_paise')::bigint,0),
    'first_subscription_referrer_reward_paise',coalesce((programme->>'first_subscription_referrer_reward_paise')::bigint,0),
    'cycle_cap_paise',coalesce((programme->>'cycle_cap_paise')::bigint,0),'balance_paise',coalesce(wallet.balance_paise,0),
    'referral_balance_paise',earned,'lifetime_referral_earned_paise',earned,'friends_joined',joined,
    'friends_signup_rewarded',signup_rewarded,'friends_rewarded',paid,
    'applied_referral',(select jsonb_build_object('code',referral_code,'status',status,'applied_at',applied_at,
      'signup_rewarded_at',signup_rewarded_at,'subscription_rewarded_at',rewarded_at,
      'welcome_reward_paise',verified_signup_referred_paise) from public.customer_referral_claims where referred_customer_id=auth.uid()),
    'activity',coalesce((select jsonb_agg(jsonb_build_object('type',entry_type,'amount_paise',amount_paise,'description',description,
      'stage',metadata->>'stage','created_at',created_at) order by created_at desc) from
      (select * from public.customer_wallet_entries where customer_id=auth.uid() order by created_at desc limit 30)e),'[]'::jsonb)
  );
end; $$;

create or replace function public.admin_referral_settings()
returns jsonb language plpgsql stable security definer set search_path=public as $$
declare programme jsonb;
begin
  perform public.require_staff(array['ADMIN']::public.app_role[]);programme:=public.customer_referral_program();
  return programme||jsonb_build_object(
    'total_claims',(select count(*) from public.customer_referral_claims),
    'verified_signup_claims',(select count(*) from public.customer_referral_claims where status in('SIGNUP_REWARDED','REWARDED')),
    'rewarded_claims',(select count(*) from public.customer_referral_claims where status='REWARDED'),
    'total_rewarded_paise',(select coalesce(sum(amount_paise),0) from public.customer_wallet_entries where entry_type in('REFERRER_REWARD','REFERRED_REWARD')),
    'is_super_admin',exists(select 1 from public.admin_staff_profiles where user_id=auth.uid() and staff_role='SUPER_ADMIN'));
end; $$;

drop function if exists public.admin_update_referral_settings(boolean,bigint,bigint,bigint,integer);
create function public.admin_update_referral_settings(
  target_enabled boolean,target_verified_referrer_reward_paise bigint,target_verified_referred_reward_paise bigint,
  target_subscription_referrer_reward_paise bigint,target_cycle_cap_paise bigint,target_cycle_days integer
) returns jsonb language plpgsql security definer set search_path=public as $$
declare new_value jsonb;
begin
  perform public.require_staff(array['ADMIN']::public.app_role[]);
  if not exists(select 1 from public.admin_staff_profiles where user_id=auth.uid() and staff_role='SUPER_ADMIN') then
    raise exception 'Only the Super Administrator can change referral rewards'; end if;
  if target_verified_referrer_reward_paise not between 0 and 1000000
    or target_verified_referred_reward_paise not between 0 and 1000000
    or target_subscription_referrer_reward_paise not between 0 and 1000000
    or target_cycle_cap_paise not between 0 and 10000000 or target_cycle_days not between 1 and 365 then
    raise exception 'Rewards must be ₹0–₹10,000, cycle cap ₹0–₹100,000, and cycle 1–365 days'; end if;
  new_value=jsonb_build_object('enabled',target_enabled,
    'verified_referrer_reward_paise',target_verified_referrer_reward_paise,
    'verified_referred_reward_paise',target_verified_referred_reward_paise,
    'first_subscription_referrer_reward_paise',target_subscription_referrer_reward_paise,
    -- Compatibility keys for any older installed app during rollout.
    'referrer_reward_paise',target_subscription_referrer_reward_paise,
    'referred_reward_paise',target_verified_referred_reward_paise,
    'cycle_cap_paise',target_cycle_cap_paise,'cycle_days',target_cycle_days,
    'qualification','VERIFIED_SIGNUP_AND_FIRST_CAPTURED_SUBSCRIPTION','terms_version','2026-09-10');
  update public.platform_settings set effective_until=now() where setting_key='customer_referral_program' and effective_until is null;
  insert into public.platform_settings(setting_key,value,effective_from,created_by)
  values('customer_referral_program',new_value,now(),auth.uid());
  insert into public.audit_logs(actor_id,action,entity_type,entity_id,after_data)
  values(auth.uid(),'REFERRAL_PROGRAM_UPDATED','platform_setting','customer_referral_program',new_value);
  return new_value;
end; $$;

revoke all on function public.customer_apply_referral(text),public.customer_referral_dashboard(),public.process_customer_referral_reward(),
  public.admin_referral_settings(),public.admin_update_referral_settings(boolean,bigint,bigint,bigint,bigint,integer) from public,anon;
grant execute on function public.customer_apply_referral(text),public.customer_referral_dashboard() to authenticated;
grant execute on function public.admin_referral_settings(),public.admin_update_referral_settings(boolean,bigint,bigint,bigint,bigint,integer) to authenticated;
notify pgrst,'reload schema';
