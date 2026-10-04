# Publication repair, 4 October 2026

## Confirmed production cause

MANJU KITCHEN 1's latest complete request was approved on 3 October at 16:59:45 UTC. An older request with no remaining staged menu was approved at 17:00:20 UTC. The old approval function archived the complete live menu anyway. All Manju menu revisions were consequently archived.

The three admin rows are request revisions with the same provider ID, not three copies of that provider. The UI now groups earlier revisions under the kitchen and orders newest first. A separate provider named “manju catering” exists; it has not been modified or deleted.

## Changes

- Reject missing/incomplete seven-day menus before archiving the currently live menu.
- Reject older requests when a newer approved menu exists.
- Admin sees current live-menu state, explanatory errors and a prominent Publish & sync customer menus action.
- Repair only a previously approved, complete revision; preserve dietary checks and cutoff rules, and audit the operation. No pending menu is silently approved.
- Repair previews run inside a rolled-back subtransaction and show dietary conflicts without changing live state or sending notifications.
- Provider profile reads the same current approved menu selector used by side snapshots. A Refresh published menu & prices button is also available in the local provider app source.

## Manju repair blocker

Production preview found two customers with saved VEG lunch choices but only NON_VEG replacements on Tuesday (Egg curry), Wednesday (Chicken curry), Friday (Fish Curry), Sunday (Egg pulao). Repair has NOT been committed. Do not bypass the dietary checks or relabel those dishes as veg. The kitchen must supply vegetarian alternatives, or the affected customers must explicitly change their choices through the normal app flow.

After compatible dishes are submitted and approved, the guarded approval publishes and syncs in one transaction. To repair a compatible already-approved revision, select it in admin and use Preview customer changes, then Publish & sync customer menus.

## Verification

Isolated PostgreSQL tests cover publication repair, rollback-only preview, stale request rejection, incomplete request rollback, provider menu readback, dietary conflicts, historical/cutoff protection and notification deduplication. DOM tests check grouped revision history, primary button, success state and disabled older revision action. Provider Kotlin compilation passed. Production phone display is not yet verified.

Migrations: 202610040001_menu_publication_guard.sql and 202610040002_menu_repair_preview.sql. No push-worker redeploy or customer APK change is required by this fix. The provider refresh button requires a rebuilt provider APK; the existing app can reread restored data by leaving and reopening My uploaded data.
