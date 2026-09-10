-- Explicit administrator-only purge for provider profiles created during testing.
-- This deliberately removes linked test finance/subscription history. It must not
-- be used after Zomeal starts accepting real customers or real provider payouts.

create or replace function public.block_finance_journal_mutation()
returns trigger language plpgsql set search_path=public as $$
begin
  if current_setting('app.zomeal_test_purge',true)='on' and public.can_manage_accounts() then
    return case when tg_op='DELETE' then old else new end;
  end if;
  raise exception 'Financial journal records are immutable. Post a reversal entry instead';
end;
$$;

create or replace function public.admin_provider_cleanup_candidates(search_text text default null,candidate_filter text default 'CANDIDATES')
returns jsonb language plpgsql security definer set search_path=public as $$
declare result jsonb:='[]'::jsonb; row_data record; likely_test boolean; warnings jsonb;
  normalized_filter text:=upper(coalesce(nullif(trim(candidate_filter),''),'CANDIDATES'));
begin
  if not public.can_manage_accounts() then raise exception 'Administrator access required' using errcode='42501'; end if;
  if normalized_filter not in ('CANDIDATES','DRAFT_INACTIVE','ALL') then raise exception 'Invalid provider cleanup filter'; end if;
  for row_data in
    select p.*,protection.reason protection_reason,
      (select count(*) from public.provider_members x where x.provider_id=p.id) member_count,
      (select count(*) from public.packages x where x.provider_id=p.id) package_count,
      (select count(*) from public.provider_menus x where x.provider_id=p.id) menu_count,
      (select count(*) from public.provider_service_areas x where x.provider_id=p.id) area_count,
      (select count(*) from public.provider_media x where x.provider_id=p.id) photo_count,
      (select count(*) from public.customer_subscriptions x where x.provider_id=p.id) subscription_count,
      (select count(*) from public.payment_orders x where x.provider_id=p.id) payment_count,
      (select count(*) from public.provider_financial_ledger x where x.provider_id=p.id) ledger_count,
      (select count(*) from public.provider_payout_requests x where x.provider_id=p.id) payout_count,
      (select count(*) from public.provider_advance_requests x where x.provider_id=p.id) advance_count
    from public.providers p left join public.provider_cleanup_protection protection on protection.provider_id=p.id
    where search_text is null or trim(search_text)='' or concat_ws(' ',p.display_name,p.contact_person_name,p.support_phone,p.id::text) ilike '%'||trim(search_text)||'%'
    order by p.created_at desc,p.display_name
  loop
    likely_test:=row_data.status in ('DRAFT','INACTIVE') or lower(coalesce(row_data.display_name,''))~'(test|testing|demo|dummy|sample|fake|temp)'
      or(row_data.package_count=0 and row_data.menu_count=0 and row_data.area_count=0);
    if normalized_filter='DRAFT_INACTIVE' and row_data.status not in ('DRAFT','INACTIVE') then continue; end if;
    if normalized_filter='CANDIDATES' and not likely_test then continue; end if;
    warnings:='[]'::jsonb;
    if row_data.protection_reason is not null then warnings:=warnings||jsonb_build_array('Previously marked as genuine. You can still select it during test-data cleanup.'); end if;
    if row_data.subscription_count>0 then warnings:=warnings||jsonb_build_array(row_data.subscription_count||' linked test subscription(s) will be removed.'); end if;
    if row_data.payment_count>0 or row_data.ledger_count>0 or row_data.payout_count>0 or row_data.advance_count>0 then
      warnings:=warnings||jsonb_build_array('Linked test payment, wallet, ledger, payout and advance records will be permanently removed.');
    end if;
    result:=result||jsonb_build_array(jsonb_build_object(
      'id',row_data.id,'name',row_data.display_name,'contact_name',row_data.contact_person_name,'phone',row_data.support_phone,
      'status',row_data.status,'created_at',row_data.created_at,'likely_test',likely_test,
      'protected',row_data.protection_reason is not null,'protection_reason',row_data.protection_reason,
      'deletion_allowed',true,'blockers',warnings,'member_count',row_data.member_count,'package_count',row_data.package_count,
      'menu_count',row_data.menu_count,'area_count',row_data.area_count,'photo_count',row_data.photo_count,
      'subscription_count',row_data.subscription_count,'payment_count',row_data.payment_count,'ledger_count',row_data.ledger_count,
      'payout_count',row_data.payout_count,'advance_count',row_data.advance_count));
  end loop;
  return jsonb_build_object('providers',result,'filter',normalized_filter,'total',jsonb_array_length(result),'test_purge_enabled',true);
end; $$;

create or replace function public.admin_delete_provider_cleanup(target_ids uuid[],confirmation text,reason text)
returns jsonb language plpgsql security definer set search_path=public as $$
declare targets uuid[]; target_count integer; missing_count integer; target uuid; objects jsonb; job uuid; provider_snapshot jsonb;
  subscription_ids uuid[]:='{}'; meal_ids uuid[]:='{}'; payment_ids uuid[]:='{}'; ledger_ids uuid[]:='{}';
  referral_claim_ids uuid[]:='{}'; journal_ids uuid[]:='{}'; affected_customers uuid[]:='{}'; external_references text[]:='{}';
  deleted_rows jsonb:='[]'::jsonb;
begin
  if not public.can_manage_accounts() then raise exception 'Administrator access required' using errcode='42501'; end if;
  select array_agg(id order by id) into targets from(select distinct unnest(target_ids) id) selected;
  target_count:=coalesce(cardinality(targets),0);
  if target_count<1 or target_count>25 then raise exception 'Select between 1 and 25 providers'; end if;
  if confirmation is distinct from 'PURGE '||target_count||' TEST PROVIDERS' then raise exception 'Type the exact test-purge confirmation shown'; end if;
  if length(trim(coalesce(reason,'')))<10 or length(reason)>500 then raise exception 'A reason between 10 and 500 characters is required'; end if;
  select count(*) into missing_count from unnest(targets) selected(id) left join public.providers p on p.id=selected.id where p.id is null;
  if missing_count>0 then raise exception 'One or more selected providers no longer exist. Refresh and try again'; end if;

  lock table public.providers,public.payment_orders,public.payment_gateway_events,public.customer_subscriptions,
    public.subscription_meals,public.customer_wallet_entries,public.customer_wallets,public.customer_referral_claims,
    public.provider_financial_ledger,public.provider_payout_requests,public.provider_advance_requests,
    public.finance_journal_entries,public.finance_journal_lines in share row exclusive mode;
  perform 1 from public.providers where id=any(targets) for update;

  select coalesce(array_agg(id),'{}') into subscription_ids from public.customer_subscriptions where provider_id=any(targets);
  select coalesce(array_agg(id),'{}') into meal_ids from public.subscription_meals where provider_id=any(targets) or subscription_id=any(subscription_ids);
  select coalesce(array_agg(id),'{}') into payment_ids from public.payment_orders where provider_id=any(targets) or subscription_id=any(subscription_ids);
  select coalesce(array_agg(id),'{}') into ledger_ids from public.provider_financial_ledger
    where provider_id=any(targets) or subscription_id=any(subscription_ids) or meal_id=any(meal_ids);
  select coalesce(array_agg(id),'{}') into referral_claim_ids from public.customer_referral_claims where qualifying_subscription_id=any(subscription_ids);
  select coalesce(array_agg(distinct customer_id),'{}') into affected_customers from(
    select customer_id from public.customer_subscriptions where id=any(subscription_ids)
    union all select customer_id from public.payment_orders where id=any(payment_ids) and customer_id is not null
    union all select customer_id from public.customer_wallet_entries where
      (reference_type='payment_order' and reference_id=any(payment_ids::text[])) or
      (reference_type='subscription_meal' and reference_id=any(meal_ids::text[])) or
      (reference_type='referral_claim' and reference_id=any(referral_claim_ids::text[])) or
      coalesce(metadata->>'provider_id','')=any(targets::text[]) or coalesce(metadata->>'subscription_id','')=any(subscription_ids::text[])
  ) customers;
  select coalesce(array_agg(distinct reference),'{}') into external_references from(
    select gateway_order_id reference from public.payment_orders where id=any(payment_ids)
    union all select gateway_payment_id from public.payment_orders where id=any(payment_ids)
    union all select payment_reference from public.provider_payout_requests where provider_id=any(targets)
    union all select payment_reference from public.provider_advance_requests where provider_id=any(targets)
    union all select external_reference from public.provider_financial_ledger where id=any(ledger_ids)
  ) refs where reference is not null and trim(reference)<>'';

  with recursive related(id) as(
    select distinct entry.id from public.finance_journal_entries entry left join public.finance_journal_lines line on line.journal_entry_id=entry.id
    where line.provider_id=any(targets) or line.subscription_id=any(subscription_ids)
      or(entry.source_type='provider_ledger' and entry.source_id=any(ledger_ids::text[]))
      or(entry.source_type='subscription' and entry.source_id=any(subscription_ids::text[]))
      or(entry.source_type in('payment_order','wallet_payment') and entry.source_id=any(payment_ids::text[]))
    union select child.id from public.finance_journal_entries child join related parent on child.reverses_entry_id=parent.id
  ) select coalesce(array_agg(id),'{}') into journal_ids from related;

  foreach target in array targets loop
    select to_jsonb(p) into provider_snapshot from public.providers p where p.id=target;
    select coalesce(jsonb_agg(jsonb_build_object('bucket',bucket_id,'path',name)),'[]') into objects
      from storage.objects where bucket_id in('provider-media','provider-documents') and split_part(name,'/',1)=target::text;
    insert into public.admin_account_deletions(actor_id,target_kind,target_id,reason,objects,cleanup_status,completed_at)
    values(auth.uid(),'provider',target,trim(reason),objects,case when jsonb_array_length(objects)=0 then 'COMPLETE' else 'PENDING' end,
      case when jsonb_array_length(objects)=0 then now() end) returning id into job;
    insert into public.audit_logs(actor_id,action,entity_type,entity_id,before_data,metadata)
    values(auth.uid(),'TEST_PROVIDER_PURGE_STARTED','provider',target::text,provider_snapshot,
      jsonb_build_object('reason',trim(reason),'job_id',job,'test_data_acknowledged',true));
    deleted_rows:=deleted_rows||jsonb_build_array(jsonb_build_object('provider_id',target,'job_id',job,'cleanup_pending',jsonb_array_length(objects)>0));
  end loop;

  perform set_config('app.zomeal_test_purge','on',true);
  delete from public.finance_journal_lines where journal_entry_id=any(journal_ids);
  delete from public.finance_journal_entries where id=any(journal_ids);
  delete from public.customer_wallet_entries where
    (reference_type='payment_order' and reference_id=any(payment_ids::text[])) or
    (reference_type='subscription_meal' and reference_id=any(meal_ids::text[])) or
    (reference_type='referral_claim' and reference_id=any(referral_claim_ids::text[])) or
    coalesce(metadata->>'provider_id','')=any(targets::text[]) or coalesce(metadata->>'subscription_id','')=any(subscription_ids::text[]);
  delete from public.customer_referral_claims where id=any(referral_claim_ids);
  delete from public.payment_gateway_events where payment_order_id=any(payment_ids);
  delete from public.finance_external_transactions where external_reference=any(external_references);
  delete from public.provider_financial_ledger where id=any(ledger_ids);
  delete from public.provider_payout_requests where provider_id=any(targets);
  delete from public.provider_advance_requests where provider_id=any(targets);
  delete from public.customer_subscription_change_requests where subscription_id=any(subscription_ids) or requested_provider_id=any(targets);
  delete from public.payment_orders where id=any(payment_ids);
  delete from public.subscription_meals where id=any(meal_ids);
  delete from public.customer_subscriptions where id=any(subscription_ids);
  delete from public.provider_commission_terms where provider_id=any(targets);
  delete from public.providers where id=any(targets);

  update public.customer_wallets wallet set
    balance_paise=greatest(coalesce((select sum(entry.amount_paise) from public.customer_wallet_entries entry where entry.customer_id=wallet.customer_id),0),0),
    lifetime_credit_paise=greatest(coalesce((select sum(greatest(entry.amount_paise,0)) from public.customer_wallet_entries entry where entry.customer_id=wallet.customer_id),0),0),updated_at=now()
  where wallet.customer_id=any(affected_customers);
  perform set_config('app.zomeal_test_purge','off',true);
  insert into public.audit_logs(actor_id,action,entity_type,entity_id,metadata)
  values(auth.uid(),'TEST_PROVIDER_PURGE_COMPLETED','provider_cleanup',gen_random_uuid()::text,
    jsonb_build_object('provider_ids',to_jsonb(targets),'reason',trim(reason),'count',target_count,
      'subscriptions_removed',cardinality(subscription_ids),'payments_removed',cardinality(payment_ids),
      'journal_entries_removed',cardinality(journal_ids),'affected_customer_wallets',cardinality(affected_customers)));
  return jsonb_build_object('deleted',target_count,'results',deleted_rows,'subscriptions_removed',cardinality(subscription_ids),
    'payments_removed',cardinality(payment_ids),'journal_entries_removed',cardinality(journal_ids),'wallets_recalculated',cardinality(affected_customers));
exception when foreign_key_violation then
  perform set_config('app.zomeal_test_purge','off',true);
  raise exception 'Test purge stopped because another linked table was found. No data was deleted. Ask the developer to add that table to the purge transaction.';
end; $$;

revoke all on function public.admin_provider_cleanup_candidates(text,text),public.admin_delete_provider_cleanup(uuid[],text,text) from public;
grant execute on function public.admin_provider_cleanup_candidates(text,text),public.admin_delete_provider_cleanup(uuid[],text,text) to authenticated;
notify pgrst,'reload schema';
