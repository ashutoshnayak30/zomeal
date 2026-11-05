# Provider Kitchen Costs

New footer tab: Dashboard, Orders, Earnings, Kitchen Costs, Profile.

## Spreadsheet UI update

Footer labels are now compact: Home, Orders, Earnings, Costs, Profile (20dp icons, single-line 10sp labels).
Cost sheet opens a full-height green/white grid with Daily and Monthly tabs. Common groceries, vegetables, oil/masalas, meat/eggs/fish, gas, rent, utilities and salary rows are prefilled; unused amounts stay blank. Add row chooses a category; Add columns reveals quantity or notes. The total includes filled rows from both tabs. Save sheet commits 1–50 expense rows in one transaction. Retrying the same request does not duplicate rows. A failed network request is frozen for retry; if closing it, refresh Entries before entering the same costs again.

Monthly entries are fixed costs, daily entries are variable costs; equipment/owner/loan stays excluded from profit. Entries now has a horizontally scrollable ledger with alternating green rows, separate cost/sales columns, totals, daily/monthly filters and tap-to-edit. The date applies to all new rows; monthly bills are recorded once rather than automatically multiplied or repeated. Existing entries and amounts are unchanged.

Deploy `202610050003_provider_cost_sheet.sql` as well as the earlier kitchen-cost migration before using Save sheet. No Edge Function or admin deployment is needed for this UI change.

## Daily use

- Add an expense with item, category, total bill amount, date and paid/unpaid status.
- Optional quantity, unit, supplier and notes keep the basic form short.
- Use Other sales for non-Zomeal kitchen income; include portions for cost-per-meal estimates.
- Overview shows estimated profit/loss, six-month charts, category breakdown and a break-even calculator.
- Entries can be edited, repeated or removed with an audit trail.
- Bills provides explicit monthly confirmation for recurring rent/salary/etc. Copies start unpaid; there are no automatic wallet deductions.
- Export the selected month as an Excel-compatible CSV using the download icon.

## Accounting boundaries

Profit = net Zomeal meal earnings (including provider-funded reversals) + other sales - operating expenses. Payouts are cash movement, never income a second time. Costs are purchase-based, not inventory-consumption accounting. Stock adjustments, depreciation and tax reporting are not calculated. Equipment, owner withdrawals and loan principal are excluded from operating profit, but paid amounts affect cash movement. Cash movement is not a bank balance.

Record full bill amounts, not unit prices. Avoid manually entering commission/refunds again because Zomeal net earnings already includes them. Shared-kitchen costs need all relevant other sales recorded. Break-even inputs are editable planning assumptions; partial-month purchases can distort estimates.

Only active provider owners/managers can access their kitchen book. Amounts use integer paise. Retried saves use a client UUID and exact payload matching; edits use revisions. Removed entries are retained for audit. A removed recurring copy cannot be generated again for the same month; add a corrected manual entry instead.

## Deployment and build (Windows CMD)

```bat
cd /d C:\Users\HP\Documents\Codex\2026-08-09\build\zomeal
npx supabase db push --dry-run
```

Review pending migrations before applying. This feature needs `202610050002_provider_kitchen_costs.sql`. Other pending migrations may belong to previous changes.

```bat
npx supabase db push
set "JAVA_HOME=C:\Program Files\Java\jdk-17"
set "GRADLE_USER_HOME=%CD%\.gradle-user"
set "ANDROID_USER_HOME=%CD%\.android-home"
gradlew.bat :provider-app:assembleDebug --no-daemon --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process "-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8"
```

APK: `provider-app\build\outputs\apk\debug\provider-app-debug.apk`. No new Edge Function is needed. Install and check add/edit/remove expense, past months, recurring bills, export and a second provider's isolation. Never upload credentials or use `git add .` on this mixed working tree.

## Automated checks

`scripts/test-admin-accounts.cjs` runs the isolated database suite including kitchen-book access, arithmetic, refunds, payouts, save retries, revisions and recurring-copy checks. `scripts/KitchenBookMathTest.kt` tests decimal amounts, break-even rounding and CSV formula escaping. These checks do not change the live database.
