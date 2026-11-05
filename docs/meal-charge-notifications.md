# Meal deduction notifications

- Lunch becomes due at 14:30 IST; dinner at 22:00 IST. Existing five-minute server ledger job includes both boundaries and retries missed runs. This does not change cancellation cutoffs.
- Positive meal debits create an in-app notification and per-enabled-customer-device push outbox rows in the same transaction. Text uses exact paise from the ledger entry and the resulting balance. No historical notifications are backfilled.
- Paused/cancelled meals, insufficient balance, zero-value demos and duplicate ledger inserts produce no deduction notification.
- The once-per-minute push dispatcher sends queued notifications, independent of whether the app is open. Push arrival is best effort (device connectivity, permissions and FCM may delay delivery); it is not a precise alarm.
- Outbox claims use five-minute leases and bounded retries (8 attempts). Completed devices are not intentionally resent; a crash after FCM acceptance can retry the same message. Stable Android tags replace duplicates. Inspect unfinished rows with attempts >= 8 to investigate exhausted retries.
- No enabled device: the in-app wallet notification remains available, but old pushes are not replayed to a later device.
- Only the DB-held secret authorizes the dispatch endpoint. Service-role-only RPCs claim/finish jobs. Do not expose the config secret, device tokens or service key to apps/admin JavaScript.
- Menu date switches to tomorrow at 22:00 IST and today's label at midnight; the screen refreshes on aligned minutes and resume.

## Phone notification recovery

On 21 September, all three queued device registrations for the dinner alert were rejected as unregistered by FCM (NOT_FOUND), rather than successfully delivered. The in-app record was unaffected. `register_customer_push_device` tells updated clients to renew disabled/rejected tokens instead of repeatedly re-enabling them. The app checks registration on resume, renews rejected tokens with Firebase, and retries transient registration errors. Renewal is rate-limited. Android notification permission/channel settings remain under the user's control; the app must not override a disabled channel.

Install the recovery APK over the existing app, open it once while logged in, and allow notifications. Validate the next real deduction in both the app feed and the system notification shade; do not create another debit solely to test push delivery.

Validation: `scripts/test-admin-accounts.cjs` runs all migrations plus charge/push ledger fixtures in a rolled-back local database. Node 24 runs `scripts/test-meal-push-worker.cjs` with mocked FCM (never a customer push).
