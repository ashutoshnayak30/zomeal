# Customer checkout update

Address fields grow with text rather than clipping at a fixed height. Fresh registration initializes only the registration pincode; other address fields start empty. Real saved subscription addresses are retained. Package cards use full-width, content-sized layouts.

## Advance payments

- The gateway order endpoint provides a server-priced quote before checkout.
- Customers enter an advance in rupees (up to two decimal places).
- During controlled testing, each initial payment must be ₹5–₹10,000 and cannot exceed the full plan total. Restore the production minimum to ₹500 before public release.
- The full weekly/monthly plan is activated after capture using the existing fulfillment flow. Remaining balance is a plan obligation, not wallet credit or a discount.
- `payment_orders.amount_paise` and its allocated components remain actual cash amounts. `plan_total_paise` retains the full obligation.
- My Plan reads paid and remaining amounts from authenticated server state, including after restart.
- My Plan provides a dedicated authenticated balance-payment checkout. It reuses an existing open Razorpay order, prevents overpayment, and applies a captured payment exactly once.
- Payments are not collected automatically. The customer chooses when and how much of the remaining balance to pay, within the limits returned by the server. A final balance below ₹500 can be paid in full.
- The Razorpay webhook is the authoritative fallback when checkout closes before the app receives the capture result.

## Wallet recharge

- Authenticated customers can temporarily add ₹5–₹10,000 through Razorpay from Zomeal Wallet during controlled testing. Restore the production minimum to ₹500 before public release.
- `WALLET_RECHARGE` orders do not require a provider, package, or subscription.
- A recharge credits `customer_wallets` and writes an immutable `RECHARGE` wallet entry only after capture verification.
- `capture_applied_at`, the unique wallet-entry reference, and row locking make webhook/app retries idempotent.
- Wallet recharge cash is recorded as gateway clearing against customer wallet payable; it is not revenue at recharge time.

## Rollout / verification

Apply `202609040002_plan_advance_payments.sql` and `202609050001_razorpay_capture_and_balance.sql`, then deploy `create-razorpay-order`, `verify-razorpay-payment`, and `razorpay-webhook`. Configure the same private `RAZORPAY_WEBHOOK_SECRET` in Supabase and Razorpay, and subscribe the webhook to `payment.captured` and `payment.failed`. Then distribute the updated customer APK.

Run `node scripts/test-advance-payment.mjs` and `node scripts/test-admin-accounts.cjs` (the latter includes balance/RLS tests and all schema migrations). On-device checks: large system fonts, long/multiline street/locality, fresh registration pincode, weekly/monthly selection, ₹4 rejection, ₹5 acceptance, ₹10,000 cap, over-plan rejection, successful test capture, My Plan balance payment, and restored remaining balance. Complete this first with Razorpay test keys; make one small live payment only after the full test-mode flow passes.
