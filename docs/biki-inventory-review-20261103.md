# BIKI inventory review — first release

Entry: BIKI robot > Inventory · 7 / 15-day review.

Implemented: provider-isolated item observations after lunch/dinner, individual item staleness, 7/15-day delivery/report grid, voice quantities without mandatory prices, manual rows, and catch-up purchases using the existing dated expense sheet. Reports are append-only and UUID-idempotent. Missing ingredients retain their own last observation. Guest portions are subscription_meals rows and count once each.

Estimates use a manually confirmed quantity per delivered meal. They are NOT measured consumption, do not alter stock observations, and do not post expenses or change monthly profit. Negative estimated remaining stock is retained as a discrepancy rather than hidden. No gas counts are requested.

Limitations: exact item-name/base-unit matching only; same-day purchase timing, wastage, staff meals and unreported purchases are not inferred. No automatic recipe learning, package conversion memory, alias matching, push reminders or automatic shopping. These need a separate reviewed item catalogue/event ledger before national rollout. Per-meal rates should only be entered when appropriate across the meals being counted; otherwise leave blank. All dates use India time.

Deploy migration 202611030001_inventory_review.sql to staging first. Compile check is not a database integration test. Do not apply the entire historical migration directory without reviewing pending migrations.

Staging acceptance checks:
- Anonymous/customer/inactive membership cannot call either RPC; active owner/manager sees only its provider.
- Same UUID and payload returns already_saved; different payload/provider cannot reuse it.
- Future dates, null quantities, duplicate ingredient names, invalid units and oversized arrays fail.
- Two rows on day 1, only one row on day 3: untouched row retains day-1 age.
- Delivered guest portions count; paused/cancelled/scheduled meals do not.
- Lunch observation excludes lunch but includes later dinner; dinner excludes that day's meals.
- Without a per-meal rate, estimated consumption and remainder are null.
- Later dated matching purchase increases estimate; unit mismatch prevents estimate. Edits/voids recalculate live.
- Late observations are selected by report date/slot, not upload date.
- Retry after a lost save response does not duplicate stock report.
- Voice report may omit prices, but quantity and base unit must be reviewed; gas not accepted as ingredient.
- Purchase catch-up for 7/15 days retains each original date; existing ledger check avoids duplicates.

Device QA still required: keyboard, navigation-bar clearance, voice return, large fonts, failed save/retry and two-provider isolation. No APK or production deployment is performed by this change.
