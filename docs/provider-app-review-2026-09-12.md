# Provider app review — 12 September 2026

Scope: source review, not an on-device visual or live production audit. Preserve the existing green/white design and compact typography.

## Priorities

1. **Reliable daily operations.** Dashboard and orders requests could finish out of order after a slot/date change. Older results could replace the current selection. Orders also used device-local dates and described provisional data as final. Added request-generation/selection checks, disposal invalidation, India-time dates, explicit loading/provisional/final labels, Retry actions, and selection locks during assignment. Cutoff copy now uses server data.
2. **Readable, adaptable operational UI.** Dashboard and orders contained 9–11sp operational labels and fixed-height controls. Added shared compact 12sp captions and minimum-height primary controls/navigation. Kept existing main heading sizes, colours and card shapes. This is the first two-screen pass, not a claim that every provider screen has been redesigned.
3. **Approval transparency.** Manage Business describes pincodes as “approved or verified”; pincode verification is not equivalent to admin approval. Separate saved drafts, submitted requests, rejection reasons and currently published information. Preserve live content while edits are pending.
4. **Photo feedback.** Profile image loading lacks a distinct failure/retry presentation. Show upload progress, pending approval and published photo separately, with a customer-view preview.
5. **Earnings clarity.** Clearly distinguish estimated meal value, commission, payable earnings, held amounts and paid-out amounts. Dashboard source defaults a missing commission rate to 14%; missing financial data should be explicit rather than silently treated as confirmed.
6. **Navigation consistency.** Dashboard has Dashboard/Orders/Earnings/Profile navigation, while Orders has only Dashboard/Orders/Profile. Standardize routes and selected states in a later pass.

## Backend correction

The original provider dashboard function (now named `provider_daily_dashboard_commission_v1`) and automatic route assignment use 07:00 IST for lunch. Customer wallet/meal cancellation functions use 08:00 IST. Migration `202609120001_provider_operations_cutoff.sql` changes only those two provider functions, preserving signatures and permissions. It fails if the expected old definition is absent. It is not deployed by this review.

The isolated PGlite test verifies the migration against cutoff fixtures, not the entire production database or delivery workflow.

## Required device checks before release

- Switch Lunch/Dinner and dates rapidly on a slow connection: no previous selection's list should replace the current one.
- Fail a request, then Retry; no stale manifest should remain actionable.
- Verify India operational dates when the device timezone is elsewhere.
- Confirm provisional/final list boundaries at 07:59/08:00 and 15:59/16:00 IST after migration deployment.
- Start route assignment; meal/date selection must not change during the operation.
- Review dashboard/orders at narrow, standard and wide phone sizes, including enlarged system font settings. Check navigation labels, filters, amounts, long addresses and long provider names.
- Check image failures, empty orders and an account awaiting approval.

## Cooking-first dashboard follow-up

Kitchen-sheet implementation: prominent meal total, portion badges, readable date, per-session response timestamp, final/provisional colour treatment, and delivery readiness. Orders links preserve the selected meal slot. A preparation checklist is stored in device-local preferences, namespaced by provider/date/session. Quantity changes invalidate the stored ticks and require acknowledgement. It does not write delivery statuses or sync to other devices. Side-item quantities remain explicitly unavailable because the existing RPC does not expose their selections; the UI does not infer rice/roti/dal/salad counts. Device visual verification is still required.

User correction: lunch and dinner are separate cooking sessions. The current implementation shows only the selected session's totals and dishes, using a compact dropdown rather than two large buttons or combined summary cards. Initial selection is Lunch before 16:00 IST and Dinner afterwards; the provider can switch manually, and the screen does not auto-switch while being used. This selection does not change server cutoffs or meal records. Both requests may load in the background, but their data is never combined on screen. This supersedes the simultaneous-display proposal below.

Replaced the dashboard's slot-switching layout with lunch and dinner totals visible together and two preparation lists. Named main-course counts, exclusions, cutoff status, and already-delivered context take priority. Removed capacity, service-area metrics, earnings estimates, profile editing and sign-out from this screen; management remains in Profile and delivery work in Orders. Navigation now uses a 72dp bar, 22dp icons and compact 11sp labels, without duplicating the scaffold's system insets.

Both slot requests use the same India-time date and refresh generation. Failed loads are not shown as zero. Final complete manifests are grouped by main-course name; otherwise the existing server choice aggregate is used. Missing or excessive dish totals trigger a reconciliation warning rather than an invented default allocation. Daily totals include already delivered meals and explicitly say so. This follow-up is code-only and is not in the previously built business-ui APK.

## Follow-up implementation

The requested follow-up adds a live read-only review panel using existing provider application-status and approved-area RPCs, including admin notes and a published-data link. Draft labels no longer conflate verification with approval. Changed-photo uploads report their current item count. Published photos show loading, missing-photo and failed/retry states. All four main screens use one shared navigation component. Missing commission rates and payout availability are displayed as unavailable; ledger reversals use their actual signed amounts and advance requests are not described as approved before approval.

Source-level UI wiring checks are in `scripts/test-provider-ui-wiring.cjs`; they do not constitute visual/device tests. Provider debug assembly succeeded (38 tasks; 8 executed), and `Zomeal-Provider-2026-09-12-business-ui-debug.apk` was copied with matching SHA-256 hashes (18,659,933 bytes). The cutoff migration remains local pending deployment. No production notifications, payments or Git push are part of this follow-up.
