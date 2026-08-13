# Zomeal backend

This directory contains the Supabase/PostgreSQL backend for Zomeal.

## Layout

- `migrations/`: versioned database schema and security changes.
- `functions/`: server-side payment, settlement, refund and notification workflows.
- `seed.sql`: development-only sample data, added after the schema is ready.

## Applying migrations later

After a Supabase project and CLI are configured, link the local project and run:

```powershell
supabase link --project-ref YOUR_PROJECT_REFERENCE
supabase db push
```

The staging project reference is `tojgwcxfvicrenfabgml`. Link it with:

```powershell
npx supabase login
npx supabase link --project-ref tojgwcxfvicrenfabgml
npx supabase db push --dry-run
npx supabase db push
```

Do not place a Supabase service-role key in this repository or in the Android app. Environment files containing credentials must remain ignored by Git.
