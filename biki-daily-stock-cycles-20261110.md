# Flexible daily stock cycles

Entry: BIKI → Daily stock · Open / close.

Opening counts may be recorded at any actual time in IST. Closing counts can be
the same day, next day, or later. Late submission is allowed; actual count time
and server submission time are retained separately. There is no midnight reset.
One open cycle per provider prevents two simultaneous opening baselines.

Manual spreadsheet and voice entry are supported. Closing needs quantities only.
Exact normalized ingredient names and compatible units reuse the opening rate;
500 g of rice from an opening 5 kg / ₹250 is valued at ₹25. Unknown prices remain
unknown; omitted ingredients are not zero. Later purchase prices do not silently
replace opening rates. These values are estimates, not weighted-average accounting.

The screen links to preparation/shopping and the purchase ledger. Cycle records
remain separate from the existing meal-slot inventory reports, shopping stock
reference, and monthly valuation. No expense, consumption, or profit is posted
by saving a count. Closed cycles are read-only in this version.

## Release steps

Apply `supabase/migrations/202611100002_flexible_stock_cycles.sql` before using
the new screen. The prior monthly opening-price change separately needs
`202611100001_opening_stock_prices.sql`. Neither is deployed by this change.
Build/install a new provider APK; existing APKs/AABs do not contain this screen.

## Verification

`node scripts/test-stock-cycles.cjs` executes the actual migration in PGlite with
fixture membership/auth functions. Checks same-day/next-day and late counts,
future/reversed dates, duplicate items, one active cycle, revisions, retry
idempotency, server-derived closing prices, unknown prices, tenant isolation,
direct-table denial and anonymous/customer denial. This is not a live Supabase
session test. Phone layout, voice and deployed end-to-end testing remain required.
