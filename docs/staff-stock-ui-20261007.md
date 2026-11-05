# Staff sheet and stock-check usability

- Costs has a prominent filled Staff & attendance button. Each selected employee has a filled Mark attendance button.
- Staff & salaries shows a compact horizontally scrollable team register. Manage selects the employee whose attendance and salary actions appear below.
- Open staff sheet edits names, positions and monthly salaries in a striped green/white table. Phone is optional. Phone and employment-date columns expand on demand. New joining dates default to today and must be checked for existing staff.
- Add row supports multiple people; save sends only changed, nonblank rows in one transaction (maximum 50). Existing revisions and client IDs protect concurrent edits and retries. An invalid row rolls back the entire save. Profiles remain separate from salary approval.
- Stock check uses simpler step labels, explicit zero-stock buttons and a visible quantity × price helper. Next/Save are in the scrolling content rather than a bottom overlay, with additional end padding. The scroll returns to the start on step changes.
- Dialogs now request system-window fitting. Form actions are scrollable with navigation/keyboard spacing. Test on the reported Vivo with gesture and three-button navigation and with the keyboard open; screenshots are not proof of the new runtime layout.
- No accounting formulas or existing salary approvals/payments were changed.

Deploy `202610070001_staff_sheet.sql` with `npx supabase db push --dry-run`, inspect the pending migrations, then `npx supabase db push`.

Build the provider APK yourself using the commands in `provider-staff-20261006.md`. Only Kotlin compilation and in-memory database tests were run here; no APK packaging or live database deployment was performed.
