# User-authorized wallet test

Account: 6370648872 / 50cb9594-bb66-430d-a279-54791829fcd5.
Run: zomeal-test-wallet-6370648872-20260915.
Credit: 300000 paise as ADJUSTMENT / DEMO_TEST_CREDIT, not a captured payment.
Observed starting wallet: 23000 paise. The transaction stores its locked baseline in entry metadata.

Cleanup must reconcile this exact credit, subsequent test meal debits, any resulting
provider earnings and genuine intervening payments/rewards. Do not zero the wallet,
delete this user's profile, or blindly subtract 300000 if some has been spent.
Use compensating entries where financial history requires retention. Inspect before
cleanup and obtain the user's confirmation that testing is complete.

No payment gateway, referral reward or withdrawal was created. This credit shares the
normal spendable balance: metadata is an audit label, NOT an enforced withdrawal block.
Do not withdraw test funds or settle test-generated provider earnings as real money.
Paused meals are restored only when their timestamp matches the insufficient-wallet
pause and their slot's change cutoff is still open. Other paused meals are preserved.
