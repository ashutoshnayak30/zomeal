# Zomeal Admin

Phase-one admin dashboard for provider onboarding and content moderation.

Open `index.html` through a local static server for development. Production uses `config.production.js`, which contains only the public Supabase URL and publishable key. Never use a service-role key in this folder.

Cloudflare Pages deployment settings:

- Root directory: repository root
- Build command: leave empty
- Build output directory: `admin`
- Custom domain: `admin.zomeal.in`

Sensitive writes use role-checked PostgreSQL functions and are recorded in `audit_logs`. Without a configured public key the panel stays in clearly labelled demo mode.

Super Administrator and Administrator account lookup and guarded deletion: see
[`docs/super-admin-account-explorer.md`](../docs/super-admin-account-explorer.md)
for permissions, deletion limits, deployment and isolated tests.
