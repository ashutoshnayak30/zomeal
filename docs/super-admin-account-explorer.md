# Administrator account explorer

## What is included

The admin sidebar shows **Users data** only for an active profile with both
the `ADMIN` permission group and either the `SUPER_ADMIN` or `ADMINISTRATOR` staff title. Every database function
checks the same requirements; hiding the navigation is not the security boundary.
Ordinary administrators can no longer grant themselves super-admin access through
the staff-management function. Deploy its update before enabling the page.

Search by a complete Indian phone number (10 digits, 91 prefix or +91 prefix),
user UUID, or provider UUID. Searching a user also finds their linked providers.
Select the customer or provider result to see its saved profile, addresses or
service areas, linked accounts, packages/price versions, menus and financial
activity. History is paged in groups of 25. Amounts are displayed in rupees with
two decimals; the database stores paise. Passwords, OTPs, auth tokens, gateway
secrets and raw checkout payloads are never returned.

Customer balance comes from `customer_wallets`. Provider balances follow the
existing ledger: available earnings less reserved payouts, future-available
earnings, net ledger total and outstanding advances. Test entries remain visible
in the ledger (including their metadata); they are not presented as a separate
cash wallet.

## Deletion policy

1. Select exactly one user or provider and choose **Review deletion**.
2. Read the scope and blockers. User deletion removes the login. Provider deletion
   removes the business and its memberships, but **keeps member logins**.
3. Type `DELETE <full UUID>` and a 10–500 character reason.
4. The server rechecks eligibility and performs database changes atomically.

Deletion is refused for staff accounts, users with provider memberships,
subscriptions (including historical subscriptions), real/completed payments,
wallet/reward history, provider ledger history, payouts or advances. An account
with financial history needs a separately reviewed retention/anonymization process.
This feature intentionally does **not** disable immutable-accounting triggers or
repeat the earlier one-off test journal purge. Failed/created test payment intents
without subscriptions may be removed with an otherwise eligible profile.

Other foreign-key dependencies cause a complete rollback, not a partial deletion.
Searches, detail access and successful deletions are audited. The audit contains
IDs and deletion reasons, not a copy of the erased profile. Administrators should
not put personal information in the reason.

Files under the exact account/provider UUID prefix in `provider-media` and
`provider-documents` are captured in a durable cleanup outbox. The Edge Function
removes them using the Storage API after the database commit. If Storage is down,
the account is still deleted and **Pending file cleanup → Retry cleanup** remains
available. No SQL deletes Storage metadata. Unrelated folders are never targeted.
Externally hosted avatar URLs and files outside these account-owned prefixes are
not deleted by this tool.

## Deploy to the existing Cloudflare / Supabase project

These changes are local until deployed. No production account is deleted by
installing the migration. Keep the existing Cloudflare Pages project; do not
create a separate site or change its authentication provider.

Run in PowerShell from the updated checkout:

```powershell
Set-Location -LiteralPath 'C:\Users\HP\Documents\Codex\2026-08-09\build\zomeal'
npx supabase db push --dry-run
```

Review all pending migrations first. This working tree also contains the earlier
weekly/monthly-package migration; `db push` includes it if still pending.

```powershell
npx supabase db push
npx supabase functions deploy admin-staff-management --project-ref tojgwcxfvicrenfabgml
npx supabase functions deploy admin-account-cleanup --project-ref tojgwcxfvicrenfabgml
```

Then publish the updated `admin` directory through the existing Cloudflare Pages
deployment. Keep `accounts.js` and `accounts.css` alongside `index.html`. Sign out
and back in as a Super Administrator or Administrator. If the menu is missing, verify the
migration, active profile, `ADMIN` role and either permitted title. Both roles can
view and delete eligible accounts and retry file cleanup. Other staff roles cannot.
Staff invitation/role management remains Super-Administrator-only. Never place the
service-role key in admin browser files.

## Tests

```powershell
npm ci
npm run test:accounts
```

The tests use in-memory PostgreSQL and a simulated DOM; they do not connect to
Supabase or send SMS. PostgreSQL fixtures emulate Supabase auth/storage schemas.
They skip old seed-data migrations and one legacy onboarding-only function-text
rewrite that depends on PostgreSQL pretty-printing. They run the new migration
unchanged and cover permissions, searches, reads, balances, pagination, blockers,
rollback, deletion, cleanup outbox, UI confirmation and error states. A live
deployment smoke test is still needed; use disposable accounts, not real customers.
