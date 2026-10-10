# Admin management workspace

Deploy database migration `202610100001_admin_management_workspace.sql` before the updated admin static assets. No Android APK is needed.

- User Management reuses the searchable customer directory and account history. Administrator/Super Administrator can mark a customer profile reviewed, block or unblock with a reason. Phone verification is unchanged. Staff and provider-linked accounts cannot use these customer actions. Blocking sets the profile inactive and bans new authentication/refresh; existing access JWTs can remain valid until expiry. Existing scheduled meals, wallet money and accounting are not erased or cancelled.
- Payments & transactions is a read-only, production-only gateway order register, 50 rows per page, with creation-date IST, status and customer/kitchen/reference search. Amount is original order amount, not refunded amount. Existing provider payouts, advances and bank reconciliation remain linked and separate. Wallet history stays in User Management.
- Notifications separates meal reminders, offers, updates, other reminders and immediate broadcasts. Scheduled category is stored in the database; it is an admin organisation tag, not a change to phone/inbox delivery semantics. Historical schedules default to UPDATE. Recent runs retain their existing history.
- Business reports reuse the production analytics model and add distinct current active customers, customer counts, delivered value, provider earnings and captured/failed payment counts. Collections, platform fees, commission and revenue are explicitly separate. Revenue is not company profit. Current counts differ from period-based financial values.

Verification: isolated database suite and JSDOM workflow tests. No live customers blocked, no messages sent, no payment states changed, no deployment run. Real-browser visual acceptance still required.

CMD deployment (review all pending migrations and git changes first):

```bat
cd /d C:\Users\HP\Documents\Codex\2026-08-09\build\zomeal
npx supabase db push --dry-run
npx supabase db push
```

Deploy the admin folder through the existing Cloudflare Pages workflow. Do not stage all workspace files: unrelated customer/provider changes are present.
