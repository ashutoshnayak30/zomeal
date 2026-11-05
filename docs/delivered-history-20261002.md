# Delivered history and compact ratings

Deploy `202610020001_delivered_meal_history.sql` before installing the updated customer APK.

Orders > Delivered lists all recorded delivered meals across the customer's subscriptions, grouped by month and year. Expanding a month loads only that month's meals, newest first, with the actual kitchen, menu photo where available, stored price, review and support actions. No dates are silently truncated and no scheduled/paused/cancelled meals are relabelled as delivered. Old records that were never marked delivered will not appear as delivered.

The delivered summary counts the complete history. Refresh reloads counts and the expanded month. Category-rating stars are grouped together rather than spread across the card; 48dp tap targets remain.

Verification: `scripts/test-admin-accounts.cjs` covers migration application, a delivered meal older than a year, month filtering, price and customer isolation, alongside existing finance/complaint tests.

Manual device checks: expand/collapse multiple months, review an old meal, request callback, return after submitting a rating, test no-history/offline/retry states, and check stars at large font sizes.
