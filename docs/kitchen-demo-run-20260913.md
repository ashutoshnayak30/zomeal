# Live kitchen demo run — 13 September 2026

Run ID: `zomeal-kitchen-demo-20260913`

User requested temporary server demo data for MANJU KITCHEN 1 (7205586281) and yogesh kitchen. The separate draft “manju catering” was excluded.

- 12 synthetic customers per kitchen; 24 total. Names start with DEMO.
- Every day from 13–19 September: 10 scheduled lunch meals, 1 paused, 1 cancelled; 9 scheduled dinner meals, 2 paused, 1 cancelled, per kitchen.
- 336 meal rows and 24 subscriptions in total, using the kitchens' existing weekly combined packages and approved main courses.
- No menus, provider profiles, prices, pre-existing customer records, rider records or balances were modified.
- Login is banned; no phone numbers, FCM tokens or deliverable email addresses were assigned. Profiles are inactive to avoid customer outreach lists. Addresses explicitly say DO NOT DELIVER.
- All subscription/meal monetary values are zero. `wallet_charged_at` is populated only as a demo billing-exclusion sentinel; it does NOT represent a real payment. These records never enter the scheduled wallet-processing loop.
- Manually marking demo meals delivered can create zero-value ledger events, but cannot create payable earnings or finance journals. This was tested transactionally.

Verified initial live dashboard totals: Manju 10 lunch / 9 dinner; Yogesh 11 lunch / 10 dinner due to one pre-existing non-demo meal per session. Existing paused meals also remain in the totals. Operate only DEMO-labelled orders; avoid bulk actions on unrelated existing meals.

Most existing menus have one main course. Yogesh's Wednesday lunch has two; the demo selects them evenly. No fake second menu choice was published.

## Validation and cleanup

`scripts/test-kitchen-demo-transaction.sql` successfully created the fixtures, transitioned one demo meal to delivered, and exercised exact-run cleanup inside one rolled-back transaction before the actual insertion.

`scripts/seed-kitchen-demo-20260913.sql` was then committed once. It rejects duplicate runs and checks the exact kitchen identities and expected counts.

After the user finishes testing, execute `scripts/cleanup-kitchen-demo-20260913.sql` against the linked project. It deletes only identities with this exact trusted auth metadata marker and their tagged subscriptions/meals. It stops and rolls back if money, payment orders, finance journals or unexpected subscriptions are linked. Audit records remain. Cleanup has NOT been run against the committed demo data.

No APK build, Git push, pending schema migration deployment, outbound notifications or real payments were performed for this task.
