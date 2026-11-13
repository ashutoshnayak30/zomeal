# BIKI closing-stock comparison

Entry: BIKI > Closing stock · Compare & review. Existing 7/15-day history now highlights days without a report; individual item dates remain authoritative. Enter observed closing quantities by voice or spreadsheet, then choose Compare with earlier stock. The new comparison is read-only; return to the stock sheet to save the actual observation using its existing idempotent save flow.

For each item, the server finds the latest strictly earlier physical count by date and meal slot. Lunch precedes dinner. Same-slot reports are corrections rather than a consumption interval. Backdated reports do not compare against future observations. Missing or incompatible prior units mean no comparison, never an assumed zero baseline.

Purchases match exact case-insensitive trimmed item names and the provider ID, excluding voided entries and sales. Compatible kg/g and litre/ml quantities convert into the new count's unit. No density, packet or cross-language-name inference occurs. Ledger entries on either count date have unknown before/after-meal timing, so require review. Missing quantities and incompatible units also require review. Even with no ambiguous entries, the owner must confirm completeness before a result appears.

Observed reduction = earlier physical count + owner-reviewed interval purchases − new physical count. Negative results stay visible as discrepancies. Reductions may include cooking, staff meals, spoilage, transfers or errors; they are not automatically classified as recipe consumption or waste. No stock deduction, purchase creation or profit adjustment is made. Preview purchase edits are temporary; missed purchases must be separately entered using the existing expense workflow.

## Deployment

Apply only `supabase/migrations/202611060001_inventory_reconciliation.sql` to a staging database first. It depends on the existing kitchen membership helper, inventory observations and kitchen entries. The new authenticated RPC is provider-scoped; anon/public execution is revoked. No Cloud Run/voice update is needed. Then rebuild the provider app. Previously delivered AABs do not include this change. This implementation does not deploy the migration automatically.

## Verification

`node scripts/test-inventory-reconciliation.cjs` runs the real migration in local PGlite with minimal table and membership-helper fixtures. Tests cover read-only behaviour, tenant isolation, fixture owner/customer/anonymous access, unit conversion, missing baselines, same-slot correction handling, backdating boundaries, same-day purchases, voids/sales and input validation. The fixture helper does not prove production session/membership enforcement; staging must test actual owner, inactive provider/member and expired-session behaviour.

`gradlew.bat :provider-app:compileDebugKotlin :provider-app:testDebugUnitTest` checks the app and arithmetic including unknown/zero purchases, negative discrepancy and fractional precision.

Device acceptance: enter/voice a count, open comparison, confirm purchases, return without losing draft, save once, retry a failed save, test back navigation, keyboard/navigation insets, large fonts, missing migration and network failure. Counts entered today before the displayed meal actually finishes should use the correct completed meal; the app cannot infer when the physical count occurred.

Remaining work: recipe-based historical usage and wastage attribution, persisted reconciliation adjustments, notifications, financial integration and automated buying. None is implied by this comparison.
