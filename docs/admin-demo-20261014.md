# Demo workspace

Admin navigation → Demo workspace. Requires an active SUPER_ADMIN profile and ADMIN permission group. Ordinary admins, providers, customers and anonymous callers cannot issue credits or enable demos. No live data was created during implementation.

## Customer wallet

Find an existing active customer by their exact Indian phone number (10 digits or +91). Verify the returned identity/balance, enter ₹1–₹50,000 and an internal reason, then confirm. Provider/staff accounts and ambiguous phone matches are rejected. The customer sees **Wallet credit**. The ledger metadata and private admin audit retain demo attribution. The existing customer app wallet shows it on refresh; no customer APK change is required.

These are **real spendable wallet adjustments**, not captured payments. No revenue, referral reward, notification or subscription restart is created by the credit action. Spending it can still fund real meals and provider earnings. For screenshot-only demos, avoid ordering real meals. Repeated requests with the same request ID do not add credit again.

Reverse credit creates an equal negative **Wallet adjustment**, without deleting the original entry. Lifetime credits remain a historical total. Reversal uses the current pooled balance (including later top-ups) and requires enough balance for the full amount; if already spent, manual reconciliation is required. It never reverses orders, provider earnings or payouts automatically. Retain audit records rather than erasing financial history.

## Provider preview

Choose a kitchen, 1–100 sample customers and illustrative lunch/dinner prices. Enable the demo; it expires in seven days. This does not create actual users, subscriptions, notifications or orders. All sample names/addresses and veg/non-veg meals are synthetic. Prices are explicit demo inputs, not a quotation from the live catalogue.

Updated provider app → Home → Refresh → Open demo. Switch lunch/dinner and practice preparing, packing and delivery. Progress is local, resets on exit, and never touches live APIs. The summary shows meal value, **not net earnings/profit**. Admin → Remove demo from app disables access; an open preview checks eligibility every 30 seconds. No sample rows exist to delete; only an audited session configuration is retained. The normal live screens remain unchanged.

## Verification

```cmd
node --preserve-symlinks --preserve-symlinks-main scripts/test-admin-accounts.cjs
node scripts/test-admin-demo-ui.cjs
```

DB tests cover role denial, phone normalization, exact identity, amount limits, idempotency, insufficient-balance reversal, immutable history, provider ownership, expiry and isolation. UI tests cover escaping, paise conversion, retry-key retention, phone changes and stale session responses.

Local verification: database and admin UI tests passed. Android verification on 15 October 2026 was blocked by an AAPT2 resource-linker daemon failure; skipping that task then failed because the generated R.jar was missing. No successful APK build or on-device verification is claimed. No live credits or provider demo sessions have been issued.

## Deploy and build (Windows CMD)

```cmd
cd /d C:\Users\HP\Documents\Codex\2026-08-09\build\zomeal
npx supabase db push --dry-run
```

Review **every** listed migration first—this checkout contains other work. Apply only once you intend to deploy all pending migrations:

```cmd
npx supabase db push
git add admin/index.html admin/live.js admin/demo.js admin/demo.css supabase/migrations/202610140001_admin_demo_workspace.sql provider-app/src/main/java/com/zomeal/provider/ProviderDemoScreen.kt provider-app/src/main/java/com/zomeal/provider/ProviderDashboardScreen.kt provider-app/src/main/java/com/zomeal/provider/SupabaseProviderRepository.kt scripts/test-admin-demo.cjs scripts/test-admin-demo-ui.cjs scripts/test-admin-accounts.cjs docs/admin-demo-20261014.md
git diff --cached --stat
git diff --cached
git commit -m "Add audited demo wallet credits and isolated provider previews"
git push origin main
set "JAVA_HOME=C:\Program Files\Java\jdk-17"
gradlew.bat :provider-app:assembleDebug
```

Some existing files contain earlier local work; review the staged diff before committing. Admin hosting must deploy the updated static `admin` directory (automatic if configured for main); hard-refresh after deployment. No new Edge Function is required. Debug APK: `provider-app\build\outputs\apk\debug\provider-app-debug.apk`, for testing only. Use the normal signed-release workflow for production distribution.
