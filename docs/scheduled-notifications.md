# Scheduled customer and partner notifications

Admin → Push notifications → Scheduled reminders & campaigns.

- Choose app, audience, editable title/body and IST start/stop times.
- Audience: all eligible accounts, selected people (phone/name search), multiple pincodes, cities, kitchens, or customers below ₹500.
- Customer kitchen targeting includes current ACTIVE/PAUSED subscriptions, not expired ones. Provider targeting uses active kitchen memberships; suspended/inactive kitchens are excluded. Customer campaigns exclude provider-only/admin-only identities unless they also have a customer subscription.
- Once, every 2/6/12 hours or daily. Maximum end date is 90 days away. Default daytime window: 08:00–21:00 IST. Outside-window sends move to 08:00; recurrence continues from that time.
- Preview, then confirmation. No schedules are seeded or activated during deployment.
- Edit / pause / resume / cancel. Editing a paused schedule keeps it paused. In-flight/accepted pushes cannot be recalled. Pausing discards pending sends rather than replaying them later.
- Server evaluates audiences on each occurrence. A ₹500+ wallet no longer matches low-wallet targeting. It does not debit wallets or change subscriptions.
- Both in-app notification feed and FCM push are created. Devices without notification permission may only see the in-app feed.
- Scheduler runs every minute, so timing is minute-level and push delivery also depends on FCM/device availability. Google acceptance is not proof of device display or reading.
- Row locking, unique run/device keys and leases prevent ordinary duplicates. As with most external push delivery, a timeout after FCM accepts a request can cause a retry; stable notification tags replace the existing Android notification where supported. This is not an exactly-once external delivery guarantee.
- Up to eight attempts, exponential backoff, 24-hour message expiry. Invalid FCM tokens are disabled. Payment push queue and schedule are untouched.
- History: recipients, devices, FCM accepted, pending, failed/stopped. No token values exposed to admin.

## Deployment

1. Apply `202609230001_scheduled_notifications.sql` (depends on Sept21 meal push infrastructure).
2. Deploy `dispatch-campaign-push` with the existing `FIREBASE_SERVICE_ACCOUNT_JSON` project secret. Endpoint verifies the private DB dispatcher secret and does not accept caller-supplied messages/recipients.
3. Publish the admin files including `notification-schedules.js` and `.css`.
4. Create a single-recipient, one-time test with explicit admin confirmation. Verify both app feed and phone notification before any bulk campaign.

No APK changes are required for notification display with the current FCM-enabled customer/partner builds. This feature does not implement a new per-category marketing opt-out screen; honor recipient requests and avoid high-frequency promotional campaigns.

## Local tests (no live sends)

- `scripts/test-admin-accounts.cjs` applies actual migrations to PGlite and runs `test-notification-schedules.cjs`.
- `scripts/test-notification-schedules-ui.cjs` checks selections, escaping, timezone, confirmation and logout.
- `scripts/test-campaign-push-worker.cjs` uses Node24 TS stripping and mocked FCM responses.
