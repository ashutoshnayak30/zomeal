-- Management statements only: no changes to commission, wallets or settlements.
create table public.provider_kitchen_reviews (
 provider_id uuid not null references providers(id), month date not null,
 opening_stock_paise bigint not null check(opening_stock_paise between 0 and 100000000),
 closing_stock_paise bigint not null check(closing_stock_paise between 0 and 100000000),
 checks jsonb not null default '{}', revision integer not null default 1,
 closed_at timestamptz, source_signature text, updated_at timestamptz not null default now(),
 primary key(provider_id,month), check(month=date_trunc('month',month)::date)
);
alter table public.provider_kitchen_reviews enable row level security;
revoke all on public.provider_kitchen_reviews from public,anon,authenticated;

create function public.provider_kitchen_statement(target_month date) returns jsonb
language plpgsql stable security definer set search_path=public as $$
declare
 p uuid:=kitchen_book_provider(); m date:=date_trunc('month',target_month)::date;
 book jsonb:=provider_kitchen_month(m); review provider_kitchen_reviews;
 gross bigint; commission bigint; reversals bigint; pending bigint;
 food bigint; fixed bigint; costs bigint; opening bigint; closing bigint;
 consumed bigint; net bigint; other bigint; signature text; categories jsonb;
begin
 select * into review from provider_kitchen_reviews where provider_id=p and month=m;
 select coalesce(sum(gross_paise) filter(where entry_type='MEAL_EARNING'),0),
 coalesce(sum(commission_paise) filter(where entry_type='MEAL_EARNING'),0),
 coalesce(sum(provider_net_paise) filter(where entry_type='REVERSAL'),0)
 into gross,commission,reversals from provider_financial_ledger
 where provider_id=p and entry_type in('MEAL_EARNING','REVERSAL')
 and case when entry_type='REVERSAL' then (created_at at time zone 'Asia/Kolkata')::date else service_date end>=m
 and case when entry_type='REVERSAL' then (created_at at time zone 'Asia/Kolkata')::date else service_date end<m+interval '1 month';
 -- Signed, account-wide unsettled ledger balance; not promised available cash.
 select coalesce(sum(provider_net_paise),0) into pending from provider_financial_ledger
 where provider_id=p and entry_type in('MEAL_EARNING','REVERSAL','PAYOUT');
 select coalesce(sum(amount_paise) filter(where category in('Grains','Vegetables','Meat & eggs','Oil & spices','Dairy')),0),
 coalesce(sum(amount_paise) filter(where cost_type='FIXED'),0),coalesce(sum(amount_paise),0)
 into food,fixed,costs from provider_kitchen_entries where provider_id=p and voided_at is null
 and kind='EXPENSE' and cost_type<>'EXCLUDED' and entry_date>=m and entry_date<m+interval '1 month';
 select coalesce(jsonb_object_agg(category,total),'{}') into categories from (
 select category,sum(amount_paise) total from provider_kitchen_entries where provider_id=p and voided_at is null
 and kind='EXPENSE' and cost_type<>'EXCLUDED' and entry_date>=m and entry_date<m+interval '1 month' group by category) x;
 opening:=coalesce(review.opening_stock_paise,0); closing:=coalesce(review.closing_stock_paise,0);
 consumed:=opening+food-closing; net:=gross-commission+reversals;
 other:=coalesce((book->'trend'->-1->>'other_sales_paise')::bigint,0);
 signature:=md5(jsonb_build_object('entries',book->'entries','gross',gross,'commission',commission,'reversals',reversals)::text);
 return book||jsonb_build_object('statement',jsonb_build_object(
 'gross_paise',gross,'commission_paise',commission,'adjustments_paise',reversals,'net_paise',net,
 'unsettled_paise',pending,'food_purchases_paise',food,'food_used_paise',consumed,
 'operating_costs_paise',case when consumed>=0 then costs+opening-closing else costs end,
 'profit_paise',net+other-case when consumed>=0 then costs+opening-closing else costs end,
 'fixed_paise',fixed,'fixed_per_day_paise',round(fixed::numeric/extract(day from m+interval '1 month - 1 day')),
 'categories',categories,'stock_valid',consumed>=0,'stock_entered',review.provider_id is not null,
 'review',case when review.provider_id is null then null else to_jsonb(review) end,
 'status',case when review.closed_at is null then 'ESTIMATED' when review.source_signature=signature then 'REVIEWED' else 'NEEDS_REVIEW' end,
 'source_signature',signature,'can_close',m<date_trunc('month',now() at time zone 'Asia/Kolkata')::date,
 'suggested_opening_paise',(select closing_stock_paise from provider_kitchen_reviews where provider_id=p and month=(m-interval '1 month')::date),
 'previous_profit_paise',(select (t->>'profit_paise')::bigint+case when r.closing_stock_paise<=r.opening_stock_paise+
 coalesce((select sum(amount_paise) from provider_kitchen_entries e where e.provider_id=p and e.voided_at is null and e.kind='EXPENSE' and e.cost_type<>'EXCLUDED'
 and e.category in('Grains','Vegetables','Meat & eggs','Oil & spices','Dairy') and e.entry_date>=r.month and e.entry_date<r.month+interval '1 month'),0)
 then r.closing_stock_paise-r.opening_stock_paise else 0 end
 from jsonb_array_elements(book->'trend') t left join provider_kitchen_reviews r on r.provider_id=p and r.month=(t->>'m')::date
 where (t->>'m')::date=(m-interval '1 month')::date)
 ));
end $$;

create function public.provider_kitchen_review_save(payload jsonb) returns jsonb
language plpgsql security definer set search_path=public as $$
declare
 p uuid:=kitchen_book_provider(); m date:=(payload->>'month')::date;
 opening bigint:=(payload->>'opening_stock_paise')::bigint; closing bigint:=(payload->>'closing_stock_paise')::bigint;
 checked jsonb:=payload->'checks'; closing_month boolean:=coalesce((payload->>'close')::boolean,false);
 existing provider_kitchen_reviews; report jsonb; k text;
begin
 perform 1 from providers where id=p for update;
 report:=provider_kitchen_statement(m)->'statement';
 if m is null or m<>date_trunc('month',m)::date or opening is null or closing is null or opening<0 or closing<0 or opening>100000000 or closing>100000000 then raise exception 'Enter valid stock values'; end if;
 if closing>opening+(report->>'food_purchases_paise')::bigint then raise exception 'Closing stock exceeds opening stock plus ingredient purchases'; end if;
 if jsonb_typeof(checked) is distinct from 'object' then raise exception 'Review the cost checklist'; end if;
 select * into existing from provider_kitchen_reviews where provider_id=p and month=m for update;
 if coalesce(existing.revision,0)<>coalesce((payload->>'revision')::integer,0) then raise exception 'Review changed. Refresh before saving'; end if;
 if payload->>'source_signature' is distinct from report->>'source_signature' then raise exception 'Costs or earnings changed. Refresh and review again'; end if;
 if closing_month then
  if not (report->>'can_close')::boolean then raise exception 'This month is still running. Save a draft instead'; end if;
  foreach k in array array['ingredients','Rent','Staff','Utilities','Gas & fuel','Delivery','Packaging','Cleaning','Other','scope'] loop
   if checked->>k is distinct from 'true' then raise exception 'Confirm every checklist item, including not-applicable costs'; end if;
  end loop;
 end if;
 insert into provider_kitchen_reviews(provider_id,month,opening_stock_paise,closing_stock_paise,checks,closed_at,source_signature)
 values(p,m,opening,closing,checked,case when closing_month then now() else null end,report->>'source_signature')
 on conflict(provider_id,month) do update set opening_stock_paise=excluded.opening_stock_paise,closing_stock_paise=excluded.closing_stock_paise,
 checks=excluded.checks,closed_at=excluded.closed_at,source_signature=excluded.source_signature,revision=provider_kitchen_reviews.revision+1,updated_at=now();
 insert into audit_logs(actor_id,action,entity_type,entity_id,after_data)
 values(auth.uid(),case when closing_month then 'KITCHEN_MONTH_REVIEWED' else 'KITCHEN_MONTH_DRAFT' end,'provider_kitchen_reviews',p::text||'/'||m::text,payload);
 return provider_kitchen_statement(m);
end $$;
revoke all on function public.provider_kitchen_statement(date),public.provider_kitchen_review_save(jsonb) from public,anon;
grant execute on function public.provider_kitchen_statement(date),public.provider_kitchen_review_save(jsonb) to authenticated;
