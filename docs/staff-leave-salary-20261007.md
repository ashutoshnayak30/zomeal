# Leave allowance and live salary summary

Deploy `202610070002_staff_leave_summary.sql` before installing the updated provider app. No APK packaging was requested; use the existing provider build commands.

## Owner setup

Staff profiles and the staff sheet now require a paid-leave allowance on edits: a whole number per month (0–31) or per Monday–Sunday week (0–7). Existing profiles are left unset until the owner chooses; no allowance is silently granted. A week shared by two months uses one quota, not two. Leave dates use the quota chronologically. No carry-over, automatic Sunday marking or partial-month quota proration is assumed.

## Calculation

Monthly salary / actual calendar days × (Present + 0.5 × Half-day + allowed Leave).

Absent contributes zero. Excess leave and unmarked days are excluded from the displayed earned-so-far estimate, clearly flagged as unresolved, not silently finalised as unpaid. Future days are not assumed worked. The owner can grant additional leave pay by adjusting the final amount with an explanatory note.

The weekly attendance screen displays monthly cards for every staff member employed in the loaded month(s), with progress and Present/Half-day/Absent/Paid leave/Excess leave/Unmarked counts. Cross-month weeks display both month summaries. These are estimates, not payout transactions.

The updated salary approval path requires month-end, configured allowance, complete marked employment days and an unchanged server calculation signature. Excess leave or any overridden amount requires a decision note. Approval is still explicit; no automatic transfers or deductions occur. A zero-pay month creates a zero salary record without fabricating an expense. Existing approved salary snapshots remain unchanged; profile/policy changes affect unapproved previews.

Existing legacy manual approvals remain preserved. This supersedes the older UI's employment-days-only salary suggestion. Unpaid salary remains tied to its approved month and payment dates remain separate.

## Verification

In-memory tests cover 20 present + 2 half-days + 4 absent + 4 paid leave, weekly leave across month boundaries, invalid quotas, unmarked days, stale previews, override notes, current-month rejection, retry deduplication and zero salary. Kotlin compilation checks the app; device visual verification remains outstanding.

Deployment: run `npx supabase db push --dry-run`, inspect pending migrations, then `npx supabase db push`. No admin-site or Edge Function deployment is required.
