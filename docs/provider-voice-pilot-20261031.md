# Provider voice pilot — 31 October 2026

Implemented locally on D:, not deployed or enabled for any kitchen.

## Provider journey

- Costs → Add expenses → Speak expenses → Hindi/Odia → consent → record
  (maximum 60 seconds) → Send → edit item/category/quantity/unit/full amount →
  confirm review → add to the existing sheet → Save rows.
- Costs → Stock check → Start or Remaining → Speak stock → same review →
  replace the selected monthly stock total → review both totals → Save stock check.
- The stock review still checks remaining value <= opening + recorded purchases.
  It adjusts the existing statement; it does not create another purchase.
- Audio is discarded from app memory after upload/disposal. Capture stops on
  background, cancellation or 60 seconds. There is no background microphone service.
- Manual entry remains available. BIKI provides a draggable green robot button on
  the five main signed-in screens, above bottom navigation. Tap for expenses or
  monthly opening/remaining stock. The assistant menu scrolls on smaller screens.
- Unconfigured builds allow local microphone preview through BIKI, but clearly
  disable AI submission. No background wake-word detection or automatic learning.

## Verified locally

- Fixed overlapping native dialogs in both expense and stock entry: the parent
  sheet is not composed while its voice recorder is open. Rebuilt debug APK.
  Device verification remains required; recording starts only after consent and
  the Start recording action (plus Android microphone permission).

- `:provider-app:compileDebugKotlin --offline --no-daemon --console=plain`: PASS
  after the final recorder wall-clock timeout/foreground safeguards.
- 16 Node backend tests: PASS (external responses simulated).
- Actual quota migration executed in isolated PGlite: PASS for anonymous/customer
  rejection, owner/manager shared quota, expiry, disable, provider suspension,
  inactive membership, invalid seconds, yesterday's quota and table access denial.
- BIKI debug APK built successfully with `:provider-app:assembleDebug`; microphone
  preview only because KITCHEN_VOICE_URL is not configured. No connected device
  was available for visual or microphone testing.
- No hosted migration, billing change, paid model request, deployment,
  provider activation, Git commit or push performed.

## Remaining before release

1. Approve Google processing in Singapore and set billing alerts/usage monitoring.
2. Verify a supported Vertex model in that region and the deployment build identity.
3. Review/apply ONLY the voice migration to the intended environment, with a backup
   and staging/concurrency checks. Avoid pushing unrelated pending migrations.
4. Deploy disabled first; verify auth rejection without charged calls; then allow
   one verified kitchen for seven days, 20 requests/600 seconds per IST day.
5. Configure `KITCHEN_VOICE_URL=https://<actual-cloud-run-host>` in local.properties
   (public URL only), recompile, and have the owner build/install the test APK.
6. Real-device and live Hindi/Odia tests, including background/rotation, permissions,
   decimal quantities, units, unit versus total price, missing values and retries.
7. This phase uses existing monthly stock totals. Daily per-item stock snapshots,
   historical unit-cost lookup, daily consumption and buying forecasts are NOT
   implemented. Never sum daily closing balances or describe this as full inventory.

Usage caps are not a rupee spending guarantee. Nothing is saved automatically from
speech. Review the selected date and every value before using the normal Save action.
