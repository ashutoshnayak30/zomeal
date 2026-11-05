# Kitchen month review

Deploy `202610050004_kitchen_month_review.sql` after the existing kitchen-cost migrations, then install the updated provider APK. No Edge Function/admin deployment is needed. No commission rates, meal prices, balances or payouts change.

## Provider flow

1. Add daily purchases and monthly bills as usual.
2. Open Month review. Enter opening stock and stock remaining at purchase cost (ingredients only: grains, vegetables, meat/eggs/fish, oil/spices, dairy).
3. Check each expense category: amount complete, or not applicable. Check that recorded costs correspond to the income covered by the statement.
4. Save a draft while the month is running. After month-end, confirm all checks and Close month.
5. Statement shows gross sales, actual ledger commission, refunds/adjustments, net earnings, stock consumption and estimated operating profit. Download exports the statement, expense-category purchases and transaction rows to Excel-compatible CSV.

Ingredients consumed = opening stock + ingredient purchases − closing stock. Costs = recorded operating expenses + opening stock − closing stock. Closing stock cannot exceed opening stock plus ingredient purchases. No stock entered means purchase-based estimates. Negative consumption after later entry changes flags the review and falls back to purchase costs until corrected.

Reviewed means provider-confirmed, not audited. Edits remain allowed; changed entries/earnings make the review stale. Stock and checklist edits use revision checks and an earnings/expense signature to reject stale saves. Previous month's stock is offered as a suggestion, never silently rolled forward. The fixed-cost daily allocation is the month's recorded fixed costs divided by calendar days; it is planning information, not an extra posting or true daily P&L.

Other sales has been removed from the main workflow. Existing sales are retained, shown only where relevant and still included to avoid rewriting history. There is no customer-conversion or 7–9% commission policy change in this release.

Cash movement and profit remain separate. The signed unsettled ledger balance is account-wide and includes held earnings; it is not withdrawable cash. Earnings retains payout-eligibility information. Statements remain management estimates excluding depreciation/tax adjustments. Shared costs need appropriate attribution while any business remains outside Zomeal.

## Checks

Run `scripts/test-admin-accounts.cjs` for isolated SQL tests: stock validation, calculations, close-current-month prevention, checklist enforcement, stale signatures/revisions, provider privacy, ledger/payout separation and earlier regressions. No live records are modified.

## Windows CMD

```bat
cd /d C:\Users\HP\Documents\Codex\2026-08-09\build\zomeal
npx supabase db push --dry-run
```

Review pending migrations, then:

```bat
npx supabase db push
set "JAVA_HOME=C:\Program Files\Java\jdk-17"
set "GRADLE_USER_HOME=%CD%\.gradle-user"
set "ANDROID_USER_HOME=%CD%\.android-home"
gradlew.bat :provider-app:assembleDebug --no-daemon --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process "-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8"
```
