# Staff & salaries — first release

Open **Costs → Staff & salaries**. Restricted to the existing kitchen owner/manager role. This is private bookkeeping, not statutory payroll or a bank-payment service.

## Workflow

1. Add name, role, optional phone, monthly salary and joining date. Set a leaving date instead of deleting historical staff.
2. Record present, absent, half-day or leave for a date. Attendance does not deduct salary automatically.
3. Select a month and review the suggested salary. Joining/leaving days are prorated across actual calendar days. Edit the final amount before confirming. Approved months retain their amount and employment-period snapshot when profiles change later.
4. Approval creates exactly one non-recurring linked Staff expense. Each salary month requires separate approval. Existing manual Staff expenses block approval until reconciled; stop overlapping repeating salary templates as well. Do not remove unrelated real costs merely to bypass this check.
5. Record actual salary payments or advances against the approved month. This does not send money. Advances count toward that month's salary, cannot exceed the outstanding amount, and never create an additional expense.
6. Review payment and attendance history using month navigation. Salaries with partial payments appear Part-paid in Entries. Payments are attributed to their actual payment month in cash reporting; expense remains in its salary month.

Approved salary amounts and payment records are locked in this first version. Check amounts and dates before confirming. There is no automated attendance penalty, daily-wage payroll, cross-month loan/advance carry-forward, statutory deduction calculation or payment reversal workflow in this release. These require a separate policy/design step.

Current-period salary cost accrues across the approved employment period, so mid-month joiners are not prorated twice. Daily share shown on the general Costs dashboard remains a whole-month average. Remaining payable is scoped to the selected salary month.

## Safety

Private tables have RLS and no direct client grants. RPCs derive the provider from authenticated membership, lock the provider to serialize money records, cap payments at approved salary and use client UUIDs for retry deduplication. Linked expenses cannot be edited, removed or repeated through generic cost APIs. Audits record changes. No wallet, order, commercial commission or provider-payout mutations occur.

## Deploy and build (Windows CMD)

```bat
cd /d C:\Users\HP\Documents\Codex\2026-08-09\build\zomeal
npx supabase db push --dry-run
```

Inspect pending migrations, including `202610060002_provider_staff.sql`, then:

```bat
npx supabase db push
set "JAVA_HOME=C:\Program Files\Java\jdk-17"
set "GRADLE_USER_HOME=%CD%\.gradle-user"
set "ANDROID_USER_HOME=%CD%\.android-home"
gradlew.bat :provider-app:assembleDebug --no-daemon --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process "-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8"
```

Deploy the migration before using the updated app. No admin website or Edge Function changes are required. APK output: `provider-app\build\outputs\apk\debug\provider-app-debug.apk`.

Verification uses the in-memory PostgreSQL suite, including `scripts/test-provider-staff.cjs`, and Kotlin compilation only. No APK was built for this request; physical-device UI verification remains outstanding.
