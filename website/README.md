# Zomeal public website

Static, mobile-first discovery website for `zomeal.in`. It supports approved
provider discovery by pincode and early-access lead capture. It intentionally
does not create bookings, subscriptions or payments.

## Run locally

From the repository root:

```powershell
py -m http.server 8090 --directory website
```

Open `http://127.0.0.1:8090/`.

## Backend deployment

```powershell
npx supabase db push --dry-run
npx supabase db push
npx supabase functions deploy website-provider-search --no-verify-jwt
```

## Cloudflare Pages

Connect the GitHub repository, set the production branch, leave the build
command empty and set the output directory to `website`. Add `zomeal.in` and
`www.zomeal.in` under Custom domains after the preview is verified.
