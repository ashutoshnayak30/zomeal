# Reviewed stock carry-forward

Apply `supabase/migrations/202611120001_stock_carry_forward.sql` after the daily stock-cycle migration. This adds a read-only, owner/manager-scoped RPC; it does not rewrite existing counts or expenses.

- Empty opening-stock drafts offer **Use saved stock + later purchases**. Copying the estimate does not save it; physically check quantities, actual count time and values before saving.
- What to buy offers the same reference, then still requires per-ingredient confirmation for the selected meals.
- Latest physical cycle count wins by actual count time. Partial closing counts do not imply zero for omitted items.
- Purchases on/after the count date are candidates only: the ledger has no receipt time. The owner selects receipts actually received after the count and not included already. Selection is temporary and resets on reload.
- Only matching normalized names and compatible units combine. Purchases without a known baseline, unsupported packs or missing quantities need manual counting. Unknown prices remain unknown.
- No consumption, waste or transfers are automatically deducted. No expense is created by carry-forward. Closing values retain the existing opening-rate valuation policy (not FIFO).

Validation: `node scripts/test-stock-reference.cjs`; Android `StockCarryMathTest`. No APK or live database deployment in this change.
