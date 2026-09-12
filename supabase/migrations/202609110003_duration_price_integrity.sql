-- Approval must never copy monthly totals onto weekly packages.
do $migration$
declare definition text; marker text:='    -- Apply admin edits to the exact staged dishes before publishing the menu.';
begin
  select pg_get_functiondef('public.admin_review_provider_business_update(uuid,text,text,jsonb)'::regprocedure) into definition;
  if position(marker in definition)=0 then raise exception 'Price approval function changed; review before migrating'; end if;
  definition:=replace(definition,'price.change_request_id=target_request and package.kind=','price.change_request_id=target_request and package.duration_days=30 and package.kind=');
  definition:=replace(definition,marker,$weekly$
    -- Weekly fields belong only to the matching 7-day package.
    for package_row in select value from jsonb_array_elements(jsonb_build_array(
      jsonb_build_object('kind','LUNCH_ONLY','price',payload->>'weeklyLunchPrice'),
      jsonb_build_object('kind','DINNER_ONLY','price',payload->>'weeklyDinnerPrice'),
      jsonb_build_object('kind','LUNCH_AND_DINNER','price',payload->>'weeklyBothPrice')
    )) loop
      if nullif(trim(package_row->>'price'),'') is not null then
        revised_total:=round((package_row->>'price')::numeric*100);
        if revised_total<=0 then raise exception 'Weekly price must be greater than zero'; end if;
        revised_lunch:=case package_row->>'kind' when 'LUNCH_ONLY' then revised_total when 'DINNER_ONLY' then 0 else revised_total/2 end;
        update public.package_price_versions price set total_price_paise=revised_total,
          lunch_value_paise=revised_lunch,dinner_value_paise=revised_total-revised_lunch
        from public.packages package where price.package_id=package.id and price.change_request_id=target_request
          and price.status='PENDING' and package.duration_days=7 and package.kind::text=package_row->>'kind';
      end if;
    end loop;
$weekly$||marker);
  execute definition;
  select pg_get_functiondef('public.admin_provider_change_detail(uuid)'::regprocedure) into definition;
  definition:=replace(definition,'''name'',package.name,''enabled''','''name'',package.name,''duration_days'',package.duration_days,''enabled''');
  execute definition;
end; $migration$;

-- Repair only proven instances of this bug, using the APPROVED request's own
-- weekly and monthly values. Publish a new version; leave historical prices,
-- existing subscriptions and wallet/payment records untouched.
do $repair$
declare row_data record; weekly_value bigint; lunch_value bigint; next_version integer; new_id uuid;
begin
  perform set_config('zomeal.weekly_price_write','1',true);
  for row_data in
    select price.*,pkg.kind,request.reviewed_by,
      request.requested_payload->'payload' payload
    from public.packages pkg
    join lateral(select * from public.package_price_versions pv where pv.package_id=pkg.id and pv.status='APPROVED'
      and pv.effective_from<=now() and (pv.effective_until is null or pv.effective_until>now())
      order by pv.effective_from desc,pv.version desc limit 1) price on true
    join public.provider_change_requests request on request.id=price.change_request_id and request.status='APPROVED'
    where pkg.duration_days=7 and pkg.is_active
  loop
    if coalesce(row_data.payload->>case row_data.kind when 'LUNCH_ONLY' then 'weeklyLunchPrice' when 'DINNER_ONLY' then 'weeklyDinnerPrice' else 'weeklyBothPrice' end,'') !~ '^[0-9]+([.][0-9]{1,2})?$'
      or coalesce(row_data.payload->>case row_data.kind when 'LUNCH_ONLY' then 'lunchPrice' when 'DINNER_ONLY' then 'dinnerPrice' else 'bothPrice' end,'') !~ '^[0-9]+([.][0-9]{1,2})?$' then continue; end if;
    weekly_value:=round((row_data.payload->>case row_data.kind when 'LUNCH_ONLY' then 'weeklyLunchPrice' when 'DINNER_ONLY' then 'weeklyDinnerPrice' else 'weeklyBothPrice' end)::numeric*100);
    if weekly_value<=0 or weekly_value=row_data.total_price_paise or row_data.total_price_paise<>
      round((row_data.payload->>case row_data.kind when 'LUNCH_ONLY' then 'lunchPrice' when 'DINNER_ONLY' then 'dinnerPrice' else 'bothPrice' end)::numeric*100) then continue; end if;
    lunch_value:=case row_data.kind when 'LUNCH_ONLY' then weekly_value when 'DINNER_ONLY' then 0 else weekly_value/2 end;
    select coalesce(max(version),0)+1 into next_version from public.package_price_versions where package_id=row_data.package_id;
    update public.package_price_versions set effective_until=now() where id=row_data.id;
    insert into public.package_price_versions(package_id,version,total_price_paise,lunch_value_paise,dinner_value_paise,status,requested_by,approved_by,approved_at,effective_from,change_request_id)
    values(row_data.package_id,next_version,weekly_value,lunch_value,weekly_value-lunch_value,'APPROVED',row_data.requested_by,row_data.reviewed_by,now(),now(),row_data.change_request_id) returning id into new_id;
    insert into public.audit_logs(action,entity_type,entity_id,before_data,after_data)
    values('WEEKLY_CATALOGUE_PRICE_REPAIRED','package_price_versions',new_id::text,
      jsonb_build_object('previous_price_id',row_data.id,'price_paise',row_data.total_price_paise),
      jsonb_build_object('price_paise',weekly_value,'approved_request',row_data.change_request_id));
  end loop;
  perform set_config('zomeal.weekly_price_write','0',true);
end; $repair$;
notify pgrst,'reload schema';
