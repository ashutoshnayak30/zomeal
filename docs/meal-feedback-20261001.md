# Meal feedback and support callbacks

Deploy 202610010001_meal_complaints.sql, publish admin/index.html, live.js,
meal-feedback.js and meal-feedback.css; rebuild both app and provider-app.
No new edge function or secret is required. Do not reapply the migration manually
after Supabase marks it applied (it wraps the existing experience-feed function).

Customer: the separate home action below banners is removed. Existing meal-card
review links remain. Orders → delivered meal details opens ratings; Orders →
Get support opens a meal-specific callback request. Rating → serious issue opens
the same request. One complaint per meal, private camera-only evidence (up to
three JPEGs, 2 MB each), a real saved ticket ID/status, and no customer refund or
replacement choice. Existing legal policy text is retained.

Admin: Ratings & complaints shows kitchen, customer/contact, actual meal value,
selected dish photo, rating/review, complaint and evidence. Records are paginated
50 per page; search/status filtering applies to the current page. Callback notes
are mandatory for status actions. Senior administrators can use the separate
wallet refund button. Provider app: Profile → Ratings & complaints shows the same
meal/feedback template, only for kitchens the signed-in account belongs to;
customer phone and internal admin notes are not exposed.

Financial policy confirmed by owner: the full approved refund is deducted from
provider earnings, without charging commission again or reversing the original
commission. Customer wallet credit, provider reversal, balanced journal, complaint
status and audit entry commit atomically. One refund per complaint; identical
retries are idempotent. Limit is the lesser of meal value and remaining actual
wallet debit, not a client-supplied entitlement. A corresponding meal earning must
exist, otherwise the admin is told reconciliation is required. Already-paid
earnings can produce a negative provider balance recoverable from future earnings;
this does not claw money back from a bank. No automatic callback phone call or
bank refund is performed.

Tests: scripts/test-admin-accounts.cjs runs test-meal-complaints.cjs against an
isolated database; test-meal-feedback-ui.cjs checks admin actions. Both apps must
be device-tested: capture photo, submit callback, inspect admin/provider,
approve a small authorised test refund, retry once, check one wallet credit and
one provider deduction. Camera orientation/process-recreation behavior and actual
private Storage delivery have not been validated on a physical device.

Operational note: unpublished camera uploads can remain private orphan files if
the customer leaves without submitting. Establish a retention/cleanup policy
before a large rollout. New complaint records deliberately prevent deleting linked
meal/provider history until a dedicated authorised retention/purge procedure is added.
