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

## Manju repair completed

The initial published-link-only diagnosis was incomplete. The approved payload already contained Aloo Panerr, Mushroom, Paneer masala and Veg pulao, plus Monday's Aloo patal. Legacy ordinal matching and duplicate merging lost these choice links. Migration 202610040003 restores exact payload alternatives and stable ordering before review/sync, without changing dietary classifications. Preview rolls back all changes.

The latest approved Manju menu was restored live: 14 meal slots, 19 main courses, five restored alternatives, zero dietary conflicts. Both affected customers were synced (76 eligible changed rows); both notification outbox entries completed without errors. Past/cutoff-locked meals and money remain unchanged. Older mislinked photo records are repaired separately by exact approved dish name, keeping them secondary to the latest photos. No customer reselection is needed.

## Verification

Isolated PostgreSQL tests cover publication repair, rollback-only preview, stale request rejection, incomplete request rollback, provider menu readback, dietary conflicts, historical/cutoff protection and notification deduplication. DOM tests check grouped revision history, primary button, success state and disabled older revision action. Provider Kotlin compilation passed. Production phone display is not yet verified.

Migrations: 202610040001_menu_publication_guard.sql and 202610040002_menu_repair_preview.sql. No push-worker redeploy or customer APK change is required by this fix. The provider refresh button requires a rebuilt provider APK; the existing app can reread restored data by leaving and reopening My uploaded data.
