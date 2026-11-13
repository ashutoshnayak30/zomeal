# Daily-cycle comparison and missing closing reminder

Apply `supabase/migrations/202611120002_daily_stock_comparison.sql` after the flexible stock-cycle migration. No existing records are changed. Uses the existing owner/manager authorization helper; anonymous and cross-provider access are denied.

In BIKI > Kitchen tools & history > Daily stock history:
- An active opening shows calendar days since opening (IST), not a claim that a report is required every day.
- Each saved closed cycle offers Compare stock. Formula: opening + reviewed purchases - closing.
- Same-day boundary purchases and incompatible/missing units require review. Only compatible interior-day quantities are suggested; owner confirms completeness even when no purchases are found.
- Partial closing counts list unreported opening ingredients; these stay unknown. Unknown opening values do not produce consumption estimates. Negative reduction remains a discrepancy.
- The difference is observed net reduction, not proven recipe consumption or waste. Does not alter profit, quantities, wallet, ledger or counts.
- Purchase edits/confirmations are temporary and not expense entries. Forgotten expenses must still be saved separately.

No APK generated and no live SQL deployed by the agent. Device layout testing remains.
