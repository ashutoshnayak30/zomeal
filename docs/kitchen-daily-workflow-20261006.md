# Kitchen daily workflow

Supersedes the earlier manual monthly-bill workflow. Deploy migration `202610060001_kitchen_daily_workflow.sql` before installing the updated provider app.

- Monthly setup comes first: rent, utilities and salaries. Daily expenses capture ingredients and operating purchases separately.
- Monthly fixed costs default to recurring. Opening an eligible month creates one unpaid estimate per commitment, automatically. This is lazy creation, not a scheduled bank payment or wallet debit. Existing current-month commitments begin repeating next month; historical months are not backfilled.
- Providers can edit an estimate, mark it paid, or switch off future repeats. Removing a monthly copy does not recreate it on refresh. Repeats retain the latest preceding month's amount.
- Current-month results allocate overhead using monthly cost × elapsed calendar days / actual month days. The rounded daily figure is explanatory; allocation rounds once, not once per day. Completed months include the full amount. Ingredient stock is not also prorated as overhead.
- Stock check has Starting stock, Remaining stock and Review steps. An optional quantity × purchase-price calculator adds ingredient values to the aggregate stock total. Save a draft without completing the month-end checklist. Only ended, fully checked months can be closed.
- Statement shows the result, calculation bridge, monthly allocation, stock and separate payment information. Existing earnings, commission and payout accounting are unchanged.
- Expense and stock dialogs have independently scrolling content and bottom actions padded for navigation/keyboard insets. Physical-device layout verification is still required.

## Windows CMD

```bat
cd /d C:\Users\HP\Documents\Codex\2026-08-09\build\zomeal
npx supabase db push --dry-run
npx supabase db push
set "JAVA_HOME=C:\Program Files\Java\jdk-17"
set "GRADLE_USER_HOME=%CD%\.gradle-user"
set "ANDROID_USER_HOME=%CD%\.android-home"
gradlew.bat :provider-app:assembleDebug --no-daemon --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process "-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8"
```

Review pending migrations before the actual push. No admin-site or Edge Function deployment is required for this change.
