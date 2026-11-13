# Reuse opening prices for remaining stock

The monthly stock review previously retained only opening/closing currency totals; opening voice item rows were discarded. This change retains opening rows in the draft and saves them atomically with the monthly review. Reopening the remaining-stock voice screen reads the selected month's opening_rows from the authenticated statement.

Correction: prices are scoped to the same selected month, not carried forward automatically from another month. Selecting a suggested prior-month total alone cannot reconstruct its ingredients.

Closing speech now requests names and quantities only. Exact normalized-name matches with compatible physical units reuse the opening unit cost: opening total × closing base quantity / opening base quantity. kg/g and litre/ml convert; density and packet contents are not guessed. A dropdown lets an owner explicitly match a different spoken spelling to an opening item. Automatically valued cells are read-only and recalculate on quantity/unit changes. New/unmatched ingredients need a reviewed full cost; unknown values block using the total. Zero remaining quantity has zero value. No weighted-average/FIFO claims are made: this is the owner's requested opening-rate management estimate, even when later purchase prices differ.

Separate quantity-only inventory observations no longer show a price column at all. They still do not change the financial statement.

Saving the complete stock check persists opening rows across devices. Manually changing the opening total, choosing zero or using a total-only calculator clears row details to prevent stale prices. Existing total-only records cannot be reconstructed automatically and need item details entered once. Closing records include ALL remaining ingredients; omissions are not added automatically.

## Deployment / tests

Apply `supabase/migrations/202611100001_opening_stock_prices.sql` once, after staging verification, before distributing the updated provider APK. It adds one private JSON column and wraps the existing save RPC while preserving its optimistic-lock, signature and audit checks. The renamed implementation has no public/anon/authenticated execute access. Legacy clients retain prices only when their opening total is unchanged. No Cloud Run update is needed.

Run `node scripts/test-opening-stock-prices.cjs` (real migration in local PGlite, fixture membership/legacy save) and provider Kotlin unit tests. Real-device and real Supabase auth integration remain to test. No deployment is performed in this implementation step.

Acceptance: record 5 kg rice costing ₹250 as opening; remaining 2 kg shows ₹100 without speaking a price. Remaining 250 g shows ₹12.50. Save, close, reopen and repeat. New item stays unknown; explicit name match reuses rate; different unit family is not converted; duplicate opening names reject; manual total change clears prices; another provider/month cannot inherit these rates.
