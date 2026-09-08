-- Restore existing approved kitchens that were unintentionally hidden by the
-- publication-quality rollout. Catalogue readiness still requires an active
-- provider, approved service area, current prices and complete approved menu.

create or replace function public.provider_publication_quality_check(target_provider uuid)
returns jsonb
language plpgsql
stable
security definer
set search_path=public
as $$
declare
  provider_record public.providers%rowtype;
  missing text[]:=array[]::text[];
  normalized_phone text;
begin
  select * into provider_record from public.providers where id=target_provider;
  if not found then raise exception 'Provider not found'; end if;

  normalized_phone:=right(regexp_replace(coalesce(provider_record.support_phone,''),'[^0-9]','','g'),10);
  if length(trim(provider_record.display_name))<3 then missing:=array_append(missing,'Public provider name'); end if;
  if lower(trim(provider_record.display_name)) ~ '^(test|testing|demo|sample)(\s|$)' then missing:=array_append(missing,'Production provider name'); end if;
  if normalized_phone !~ '^[6-9][0-9]{9}$' then missing:=array_append(missing,'Valid 10-digit Indian support phone'); end if;

  -- Provider apps historically saved genuine approved photos under several
  -- media types (including KITCHEN and menu-item media). Any approved image is
  -- valid minimum visual content; customer_marketplace selects the best cover,
  -- kitchen, meal and dish images independently.
  if not exists(
    select 1 from public.provider_media media
    where media.provider_id=target_provider
      and media.status='APPROVED'
      and nullif(trim(media.storage_path),'') is not null
  ) then missing:=array_append(missing,'At least one approved provider or food photo'); end if;

  if exists(
    select 1 from public.providers other
    where other.id<>target_provider and other.status='ACTIVE'
      and (
        right(regexp_replace(coalesce(other.support_phone,''),'[^0-9]','','g'),10)=normalized_phone
        or (lower(trim(other.display_name))=lower(trim(provider_record.display_name))
          and coalesce(other.business_pincode,'')=coalesce(provider_record.business_pincode,''))
      )
  ) then missing:=array_append(missing,'Duplicate active provider identity'); end if;

  return jsonb_build_object('provider_id',target_provider,'ready',cardinality(missing)=0,'missing_requirements',to_jsonb(missing));
end;
$$;

notify pgrst,'reload schema';
