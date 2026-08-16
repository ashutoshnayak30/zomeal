# Subscription operations verification

Apply migrations first:

```powershell
cd "C:\Users\HP\Documents\Codex\2026-08-09\build\zomeal"
npx supabase db push --dry-run
npx supabase db push
```

In the Supabase SQL editor, replace the sample UUIDs and set the JWT subject to an existing ADMIN, FINANCE, or OPERATIONS user for the test transaction:

```sql
begin;
select set_config('request.jwt.claim.sub','<admin-user-uuid>',true);

select public.admin_confirm_subscription_payment(
  target_customer := '<customer-profile-uuid>',
  target_package := '<active-package-uuid>',
  target_start_date := current_date + 1,
  target_delivery_address := jsonb_build_object(
    'house_number','Plot 123',
    'address_line','Near Jagamara Square',
    'locality','Khandagiri',
    'city','Bhubaneswar',
    'pincode','751030'
  ),
  target_total_paid_paise := 649900,
  target_payment_reference := 'TEST-PAYMENT-001'
);

commit;
```

Run the same call again. It must return `idempotent: true` and must not create duplicate meals.

Verify the price snapshot and meal totals:

```sql
select id, package_price_paise, lunch_component_paise, dinner_component_paise,
       commission_basis_points, start_date, end_date
from public.customer_subscriptions
where payment_reference='TEST-PAYMENT-001';

select meal_slot, count(*) meals, sum(meal_value_paise) component_total_paise
from public.subscription_meals
where subscription_id=(select id from public.customer_subscriptions where payment_reference='TEST-PAYMENT-001')
group by meal_slot order by meal_slot;
```

For a combined 30-day package, there must be 30 lunch and 30 dinner rows. Each component sum must exactly equal the snapshotted component—even when the component is not evenly divisible by 30.

Test tracking and earning accrual with one meal:

```sql
update public.subscription_meals
set status='PREPARING'
where id=(select id from public.subscription_meals
  where subscription_id=(select id from public.customer_subscriptions where payment_reference='TEST-PAYMENT-001')
  order by service_date,meal_slot limit 1);

update public.subscription_meals
set status='DELIVERED', delivered_at=now()
where id=(select id from public.subscription_meals
  where subscription_id=(select id from public.customer_subscriptions where payment_reference='TEST-PAYMENT-001')
  order by service_date,meal_slot limit 1);

select old_status,new_status,occurred_at from public.meal_status_events
where subscription_id=(select id from public.customer_subscriptions where payment_reference='TEST-PAYMENT-001')
order by occurred_at;

select entry_type,meal_slot,gross_paise,commission_paise,provider_net_paise,available_at
from public.provider_financial_ledger
where subscription_id=(select id from public.customer_subscriptions where payment_reference='TEST-PAYMENT-001');
```

Expected result: one `MEAL_EARNING`, commission calculated with the subscription's snapshotted basis points, and `available_at` exactly 48 hours after delivery. Repeating `DELIVERED` must not create a second earning.
