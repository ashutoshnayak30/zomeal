# Approved menu synchronisation

## Customer experience

- Recurring main-course replacements now propagate through the full-business approval RPC to existing active/paused subscribers' weekly templates and eligible dated meals.
- Keep a dish still offered in that weekday/slot. Otherwise select the same ordinal within its exact dietary category; if that ordinal no longer exists, select the first compatible alternative. Never cross VEG/NON_VEG/VEGAN; an unknown/BOTH original or a missing compatible alternative blocks the transaction for admin correction.
- Only future SCHEDULED/PAUSED, uncharged meals before 08:00 lunch / 18:00 dinner IST cutoff can change. Paused stays paused. No wallet, price, earning, cancelled, delivered or preparing record is rewritten.
- One inbox notification per affected customer per approved request, with one outbox entry per enabled customer device. Reuses the installed `dispatch-meal-push` worker and its cron; FCM acceptance is not proof of phone delivery. No new Edge Function deployment is required by this change.
- Notifications count affected weekdays for recurring updates and affected dates for temporary updates. They link to My Plan, which now has a menu change log. Older APKs still receive notifications and the updated meal data; the new log requires the updated APK.
- My Plan shows all seven saved weekday choices instead of four provider defaults. My Plan refreshes subscription state on entry/resume/minute polling. Full week gives saved dated meals priority, including tomorrow.

## Admin: repair Manju Kitchen 1's already-approved change

After deploying BOTH new migrations and the admin files:

1. Provider change requests → Approved → open the latest Manju Kitchen 1 request.
2. In **Customer menu updates**, click **Preview customer impact**.
3. Review replacements and conflicts. Add compatible choices through a revised request if conflicts exist; do not override dietary safety.
4. Click **Sync this approved menu to existing customers** and confirm once.
5. Reopen My Plan on the customer phone. Meals past cutoff remain unchanged intentionally.

This is a targeted, audited repair. The migrations do not silently modify every provider's existing customer menus or send mass notifications. New full-business approvals automatically run the sync in the same transaction; a conflict rolls the whole approval back. The sync is restricted to admins/operations. An older/expired menu cannot replace a newer approved one.

## Provider: temporary dates vs recurring changes

- Regular Seven-day menu → edit → Submit all changes continues the recurring flow, now with automatic subscriber synchronisation. Customer prices on existing meals do not change; any separately submitted catalogue price revisions retain the existing approval rules.
- Manage business → **Change a meal on specific dates** requests one same-diet dish replacement for 1–7 exact dates within the next 30 days. Select lunch/dinner, the original dish, the replacement name, and dates. The server verifies the original dish exists on those dates.
- Admin → **Dated menu changes** shows the before/after, dates, dietary type and eligible customer count; approve or reject with a reason.
- Temporary approval does not edit weekly templates. It updates eligible existing dated meals and an insert/update trigger handles subsequently created dated meals. Locked dates are rejected, not silently moved to another date. Overlapping replacements cannot be stacked. Later weekly sync preserves already-approved dated replacements.
- Existing approved replacements can be reused only within the same dietary category; otherwise submit a distinct dish name. New temporary dishes have no invented photo. Providers can see request statuses.

## Deployment (Windows CMD, repository root)

```cmd
npx supabase db push --dry-run
```

Expected new migrations: `202610030001_menu_customer_sync.sql` and `202610030002_dated_menu_changes.sql`. If previous delivered-history migration is unapplied, review it too. Stop on unrelated unexpected migrations. Then:

```cmd
npx supabase db push
git add admin/index.html admin/live.js admin/changes.js admin/dated-menu-changes.js supabase/migrations/202610030001_menu_customer_sync.sql supabase/migrations/202610030002_dated_menu_changes.sql docs/menu-updates-20261003.md
git diff --cached --stat
git commit -m "Synchronise approved menus and add dated replacements"
git push origin main
```

Wait for Cloudflare Pages admin deployment success and hard-refresh. The git commands above intentionally publish only the admin/backend slice, not unrelated dirty mobile work.

Build both apps from this local workspace:

```cmd
set "JAVA_HOME=C:\Program Files\Java\jdk-17"
set "GRADLE_USER_HOME=%CD%\.gradle-user"
set "ANDROID_USER_HOME=%CD%\.android-home"
gradlew.bat :app:assembleDebug :provider-app:assembleDebug --offline --no-daemon --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process
```

Outputs: `app\build\outputs\apk\debug\app-debug.apk`, `provider-app\build\outputs\apk\debug\provider-app-debug.apk`.

## Verification and limitations

In-memory PostgreSQL tests cover real provider submission + approval, dry-run non-mutation, dietary conflict rollback, private access, future/paused updates, history/cancelled/preparing protection, notification/outbox deduplication, date-only approval, stable prices/templates, and preserving dated overrides during subsequent weekly changes. Admin DOM tests cover repair navigation, escaping, dated approvals and logout clearing. Both Android modules are compiled; device delivery, visual UX and camera are not claimed verified here.

Recurring changes take effect for the next eligible meals on approval (no arbitrary future activation-date scheduler). Dated changes have explicit dates. Only the three stored dietary categories are enforced; this is not an allergy-management system. Operators must review ingredients. Notification delivery still requires permission, a valid device token and the existing worker. No APK or live deployment was performed by the assistant for this task.
