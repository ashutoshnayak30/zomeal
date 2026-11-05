# Guest meals and customer UI

Local changes; not deployed automatically.

## Customer flow

My Plans → Add / manage guest meals. Expand a dated lunch/dinner, select 0–3 guests, choose each dish, review the exact total and confirm. Multiple dates can be edited in one request. Zero cancels future guests for that slot. Same delivery address; current plan dates/slots only. Existing paused guests can be rescheduled by explicitly saving that slot before cutoff.

Price is the customer's saved per-meal price plus 1,000 paise per guest. The review screen discloses the surcharge. The server rejects a stale price quote. No booking-time debit; existing daily ledger times apply (14:30 lunch, 22:00 dinner IST). Commission applies to the full guest-meal amount including the surcharge.

Pause asks Own / Guests / Both only when scheduled guests exist. Old APKs cannot accidentally pause guests through the legacy pause RPC. Own home cards filter out guest rows. Providers see each guest as a separate meal, including its dish and Guest number in the manifest and delivery export.

## Backend

Migration `202610050001_guest_meals.sql` adds guest seats 1–3 beside regular seat 0. Real meal rows reuse cooking counts, rider assignment, side snapshots, menu syncing, daily debits, refunds and provider earnings. Unique seat keys prevent duplicate bookings. Subscription/provider locks serialize saves and capacity checks. All RPCs verify customer ownership; dish/date/cutoff/capacity and price checks are server-side.

An insufficiently funded guest is paused without independently pausing the subscription, with an inbox/push alert. Guest auto-delivery waits for the successful debit; a guest dinner may consequently auto-complete after 22:00 rather than the ordinary 21:50 timer. Existing normal-subscription insufficient-wallet policy still applies. Wallet top-ups do not silently resume paused guests. Provider changes cannot silently reprice/move existing guest rows; cancel upcoming guests first.

## Other changes

- Home lunch/dinner cards share the taller intrinsic height.
- WhatsApp opens its composer (Business fallback); Messages opens SMS. Instagram attempts sharing and copies the invitation as a paste fallback. Missing apps fall back to the Android share sheet. No message is sent without the user's action and opening a composer is not recorded as a successful referral share.

## Verification and rollout

Isolated PostgreSQL tests cover guest limit, availability, owner isolation, separate dishes, same address, price/surcharge, duplicate saves, daily debit idempotency, pause scope, cutoff rejection, provider totals, commission on surcharge and low-wallet handling. Existing notification, delivery, complaints and menu-sync regression suite passes. Customer Kotlin compilation checked; physical-device UI, installed-app sharing and real cron delivery remain staging acceptance checks.

Deploy the database before installing the customer APK (the new pause screen needs the guest RPC). Review all pending migrations with `npx supabase db push --dry-run` before applying. Build customer APK with `gradlew.bat :app:assembleDebug`. No Edge Function redeploy is needed. Do not assume a debug APK is suitable for store release.
