# BIKI simplified recording journey — preview before APK

Requested flow: BIKI → choose Opening stock / Closing stock / Purchases /
Recipe ingredients → recorder → automatic processing after stop → editable
review sheet → confirmed save. Kitchen plans, historical reports and monthly
valuation live under secondary tools, not competing recording choices.

Interactive design preview is shown in the conversation; it is not Android UI.
Show persistent language beside the microphone, saved consent under settings,
actual count date behind a friendly change control, and quantity/unit before
optional category information. Footer must clear Android navigation and keyboard.

Screenshot diagnoses:
- Packet and bag units accepted by voice conflict with daily RPC physical units.
  Added pre-submit validation retaining editable draft. Proposed UI asks actual
  contents of one pack; never assume all packets share a weight.
- Every failed save froze draft. Added explicit P0001/HTTP400 rollback handling
  to unlock editing. Uncertain network outcomes retain the same retry request.
- Local source sends selected or-IN to speech, but deployed request configuration
  has not been inspected. Added backend Bengali-script rejection for explicit
  Odia selection, before extraction. This is a safeguard, not improved speech
  recognition. Requires deployment and live Odia recording tests.

## Approved design implementation

Owner approved the preview. Android now has a full-screen four-task chooser,
secondary tools/history, direct daily opening/closing recorder entry (after an
active-cycle check), a circular microphone with real recording timer, visible
saved language and collapsed transcript. Daily stock uses a responsive row sheet
with quantity and unit in view, optional date/time editing, packet/bag contents
confirmation, and a saved confirmation. Stop automatically opens the daily sheet;
there is no separate voice-draft acceptance screen for this entry point.

Purchases opened through BIKI also go directly into their existing expense sheet,
with missing voice prices blocking partial saving. Recipes first require an
approved menu dish, then open the recorder and transfer ingredients to the recipe
sheet. Existing monthly valuation and other tools remain accessible.

Packet conversion multiplies the owner's stated pack count by confirmed contents,
preserves total cost, rejects invalid/over-precise/oversized quantities, and never
assumes a standard packet weight. Four calculation regression tests cover this.

The Android client also rejects Bengali-script transcripts when Odia was chosen,
so the safeguard is included even before the backend guard is deployed. This does
not improve the speech engine's recognition accuracy. Live Odia root-cause
verification and backend deployment remain outstanding.

Build: provider debug APK. Automated checks: 49 Android unit tests, 28 backend
tests and PGlite daily-cycle regression script. No connected phone was available;
device layout, microphone and regional-language accuracy testing remain required.
No new SQL migration is needed beyond the two existing 20261110 migrations.
