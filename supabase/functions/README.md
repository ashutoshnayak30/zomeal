# Edge functions

## Razorpay Test Mode

`create-razorpay-order` loads the active approved package price and Zomeal fee settings from the database before creating a Razorpay order. The Android app cannot override the amount.

`verify-razorpay-payment` validates the checkout HMAC signature and confirms the payment amount, currency, order and status directly with Razorpay. Captured payments activate a subscription only for an authenticated customer. Anonymous Test Mode payments are recorded but never create subscriptions.

Deploy after pushing the payment migration:

```powershell
npx supabase db push --dry-run
npx supabase db push
npx supabase functions deploy create-razorpay-order --no-verify-jwt
npx supabase functions deploy verify-razorpay-payment --no-verify-jwt
```

Required hosted secrets:

- `RAZORPAY_KEY_ID`
- `RAZORPAY_KEY_SECRET`

Never put the Razorpay Key Secret in Android code, `local.properties`, `admin/config.js`, or Git.
