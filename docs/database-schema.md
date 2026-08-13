# Zomeal database schema

## Phase 1 migration

The first migration establishes the identity, provider catalogue, serviceability, pricing, capacity and approval foundation.

### Main entities

- `profiles`: application profile corresponding to a Supabase Auth user.
- `user_roles`: authorized customer, provider, operations, finance and admin roles.
- `providers` and `provider_members`: provider business records and staff access.
- `pincodes` and `provider_service_areas`: centrally controlled serviceability.
- `provider_change_requests`: approval workflow for price, capacity, service-area and closure changes.
- `packages` and `package_price_versions`: package catalogue with immutable, effective-dated prices.
- `provider_capacity`: availability by provider, pincode, date and lunch/dinner slot.
- `platform_settings`: effective-dated commission and fee configuration.
- `audit_logs`: immutable privileged-action history.

### Design rules

- Primary keys use UUIDs.
- Timestamps use `timestamptz` and are stored in UTC.
- Monetary values use integer paise.
- Soft lifecycle states are used instead of destructive deletion for business records.
- Effective-dated values preserve purchase and settlement history.
- Database constraints enforce basic invariants; transactional server functions will enforce cross-table capacity and checkout rules.

## Planned migrations

1. Identity, providers, serviceability, packages, pricing and capacity.
2. Menus, alternatives, nutrition/allergens, reusable weekly templates and moderated provider media.
   A follow-up readiness migration separates mandatory activation data from optional profile completion.
3. Addresses, subscriptions, daily meal schedules, pauses and replacement credits.
4. Orders, delivery states and provider earning ledger.
5. Payments, invoices, discounts, refunds, wallet and payout ledger.
6. Notifications, support, ratings, closure migration and operational audit workflows.
