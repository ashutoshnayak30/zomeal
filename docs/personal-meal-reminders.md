# Personal meal reminders

Deploy migration `202609290001_personal_meal_reminders.sql` and the admin assets
(`index.html`, `live.js`, `meal-reminders.js`). The new settings start disabled.
An authorised admin enables lunch/dinner in Push notifications → Daily meal reminders.
Defaults: lunch 10:30 AM, dinner 6:30 PM, Asia/Kolkata.

Templates support `{main_course}`, `{provider}`, `{meal}`. The selected daily menu
item is used, not a generic weekly menu. Messages say scheduled, not cooking.
Low-wallet/auto-paused subscriptions get the wallet variant instead of the normal
message. Manually paused/cancelled meals are excluded. No meal or wallet mutations
are performed; skipped meals remain skipped.

The unique customer/date/slot key enforces one food reminder in the inbox. Its ID
is reused in the existing meal push outbox for each enabled customer device. The
existing dispatch-meal-push worker must be deployed, configured and healthy. Phone
delivery also requires notification permission and a valid registered device;
FCM acceptance is not proof of delivery. Payment alerts and generic admin campaigns
are separate, so do not create duplicate food campaigns there.

The worker checks each minute, within a 30-minute window. Missed windows are not
replayed. Editing/re-enabling a template does not resend an already-created reminder.

Validation: `node scripts/test-admin-accounts.cjs` includes reminder integration
tests in an isolated database. No live messages are sent by these tests.

App fix: RPC responses are parsed on the worker thread. The mark-read RPC returns
an integer; previously parsing this as JSONObject on the UI thread could crash
notification taps. Install a rebuilt customer APK to receive that fix.
