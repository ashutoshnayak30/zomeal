# Approved side-dish updates

Requires the two preceding 202610030001/002 menu-sync migrations. New migration:
`202610030003_menu_side_dish_sync.sql`.

- Approval updates included sides (rice/pulao/biriyani, roti, dal, salad, etc.) independently of saved main courses. No customer reselection.
- Side-only changes also appear in admin impact previews and the customer's menu-change history.
- One combined inbox notification and phone-push outbox entry per affected customer/request; retries do not duplicate them. Phone delivery still depends on the deployed push worker and device permissions.
- Scheduled/paused, uncharged meals before the existing preparation cutoff are eligible. Cancelled, delivered, preparing, paid and cutoff-locked meals are not rewritten. Prices/statuses never change in this sync.
- Snapshots preserve sides for each meal going forward; existing historical rows without snapshots are not guessed from the current menu. The customer app says side details unavailable for those rows.
- Explicit non-veg sides are blocked for veg selections. The provider's existing combined “included sides” field inherits its kitchen diet; a BOTH kitchen's free-text sides are not ingredient/allergen verification. Providers must correctly describe shared sides; do not treat arbitrary biriyani as vegetarian.
- This extends recurring weekly-menu approvals. The separate exact-date replacement form still edits main courses only.

## Deploy from Windows CMD

Run in `C:\Users\HP\Documents\Codex\2026-08-09\build\zomeal`:

```bat
npx supabase db push --dry-run
```

Review all pending migrations before applying (the workspace also contains earlier changes):

```bat
npx supabase db push
```

Publish the admin changes along with the preceding menu-sync files using your normal reviewed Git deployment. See `menu-updates-20261003.md`; do not stage the entire dirty workspace blindly.

Build customer APK after the database update:

```bat
set "JAVA_HOME=C:\Program Files\Java\jdk-17"
set "GRADLE_USER_HOME=%CD%\.gradle-user"
set "ANDROID_USER_HOME=%CD%\.android-home"
gradlew.bat :app:assembleDebug --no-daemon --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process "-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8"
```

APK: `app\build\outputs\apk\debug\app-debug.apk`.

Validation: isolated PostgreSQL tests cover rice→pulao→veg biriyani, side-only and mixed approvals, no preview mutation, unchanged prices, locked statuses, side removal, dietary conflict rollback and inbox/push deduplication. Customer Kotlin compile is checked separately; this does not prove delivery to a physical phone.
