# Weekly attendance journey

Costs → Staff & attendance now opens a Monday–Sunday matrix. Staff names are fixed on the left; day columns scroll together horizontally. Previous/Next and This week navigate weeks. Future dates and dates outside the person's employment interval are disabled.

Tap a cell and choose Present, Absent, Half-day or Leave. Each selection uses the existing authenticated attendance RPC, saves immediately and reloads confirmed values. Errors leave the selection open for retry. Unmarked is never interpreted as absent. Attendance remains independent of salary deductions.

Weeks crossing a month/year boundary read both monthly reports, deduplicate staff and merge dated attendance. Future months are not requested. A failed partial load shows an error instead of an incomplete week.

Add staff opens the existing sheet; saving returns to and refreshes the week. Salaries & team preserves the existing salary management workflow. The green header, alternating rows and current app theme remain unchanged.

No new database migration is required for this screen. The previously supplied staff and staff-sheet migrations must already be deployed. Compile-check only; build the provider APK using the existing Windows CMD instructions. Physical-device layout verification remains necessary.
