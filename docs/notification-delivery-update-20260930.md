# Notification UI and automatic delivery

Admin: lunch and dinner reminder times are editable (08:00–21:00 IST, matching
the existing server validation). The stored time is shown; saving preserves
templates and on/off settings. Existing per-day/slot deduplication is unchanged.

Customer: readable notification cards, All/Unread/Meals/Wallet filters, IST
timestamps and corrected destination labels. Home and Orders poll server meal
data each minute and refresh on resume; display status is not inferred locally.

Apply migration 202609300001_automatic_meal_delivery.sql to enable the explicitly
approved time-based business policy. Each minute the server marks today's
eligible meals delivered after 14:30 (lunch) and 21:50 (dinner) IST. There is no
historical backfill. Paused/cancelled meals and inactive subscriptions are
excluded; unpaid meals must have sufficient wallet coverage at processing.
It does not debit the wallet. Dinner's existing 22:00 charging schedule is unchanged.

IMPORTANT: This is automatic completion, not proof of physical delivery. It runs
the existing delivered-meal earnings trigger and enables the usual delivered-meal
review eligibility. auto_delivered_at and MEAL_AUTO_DELIVERED audit logs identify
the source. No bank transfer is initiated by this job.
Disputed/missed deliveries still need operational review and appropriate adjustments.

Deployment: publish admin/meal-reminders.js and admin/notification-schedules.css,
apply the migration, and rebuild/install the customer APK for the UI and refresh
changes. No live deployment is performed by the local tests.

Tests: test-admin-accounts.cjs includes boundary and financial-ledger deduplication
tests; test-meal-reminders-ui.cjs covers edited times and validation.
