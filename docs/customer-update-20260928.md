# Customer update 1.0.5

- Approved logo: option 1, two-tier tiffin with steam. Master: assets/brand/customer-two-tier-approved.png.
- Provider launcher colour verified from provider-app/src/main/res/drawable/ic_launcher_background.xml: #006B35.
- Native adaptive foreground traced from the approved master with scripts/vectorize-customer-launcher.cjs. Legacy density PNGs exported by scripts/export-crisp-launcher.cjs. In-screen logo uses a Compose-compatible vector resource.
- Change provider has a filled action button, retaining the existing callback/confirmation flow.
- Names are refreshed through customer_profile_details when the relevant screen opens/resumes; no invented names or database overwrites.
- Wallet history reads the authenticated customer's ledger in 500-entry pages, rather than using the truncated referral-dashboard activity. All pages must load before monthly totals are displayed. Deductions are grouped by posting month in Asia/Kolkata, gross debits only; credits/refunds shown separately. Amounts retain paise.
- No database migration, APK build, deployment or installation requested for this turn.

Image preparation: built-in image-generation tool. Final edit prompt:
Keep this exact approved white two-tier tiffin icon unchanged. Only set ALL green background pixels and negative spaces to one flat uniform sRGB #006B35 (RGB 0,107,53), no texture or gradient. Preserve sharp smooth edges of all white shapes. Keep original scale/position on square. No transparency, no noise or grunge, no redesign. Production app icon.

The unsuccessful transparent-background draft was not used. Android assets use a native solid background for exact colour.

Manual acceptance checks:
1. Sign in with an existing named account; verify home greeting and full profile name.
2. Edit name and return to home/profile; verify refresh.
3. Open wallet, expand/collapse months, scroll oldest month; reconcile debit totals to ledger.
4. Confirm credits do not reduce gross monthly deductions and timestamps near UTC month boundaries are grouped using IST.
5. Verify history error/retry UI offline; no partial totals displayed.
6. Tap Change provider and verify existing browse/review flow.
7. Verify launcher at 48px and adaptive round/squircle masks.
