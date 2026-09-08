# Edge functions

## Razorpay Test Mode

`create-razorpay-order` loads the active approved package price and Zomeal fee settings from the database before creating a Razorpay order. The Android app cannot override the amount.

`verify-razorpay-payment` validates the checkout HMAC signature and confirms the payment amount, currency, order and status directly with Razorpay. All payment orders require an authenticated customer. Captured initial payments activate a subscription; captured balance payments increase the amount paid toward that subscription.

`razorpay-webhook` verifies the exact raw request body with `RAZORPAY_WEBHOOK_SECRET`, stores an idempotent gateway event, and applies captured initial or plan-balance payments exactly once.

Deploy after pushing the payment migration:

```powershell
npx supabase db push --dry-run
npx supabase db push
npx supabase functions deploy create-razorpay-order --no-verify-jwt
npx supabase functions deploy verify-razorpay-payment --no-verify-jwt
npx supabase functions deploy razorpay-webhook --no-verify-jwt
```

Required hosted secrets:

- `RAZORPAY_KEY_ID`
- `RAZORPAY_KEY_SECRET`
- `RAZORPAY_WEBHOOK_SECRET`

In Razorpay Dashboard, register this webhook URL and enable `payment.captured` and `payment.failed`:

`https://tojgwcxfvicrenfabgml.supabase.co/functions/v1/razorpay-webhook`

Never put the Razorpay Key Secret in Android code, `local.properties`, `admin/config.js`, or Git.
