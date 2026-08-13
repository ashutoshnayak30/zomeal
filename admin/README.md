# Zomeal Admin

Phase-one admin dashboard for provider onboarding and content moderation.

Open `index.html` in a browser. Copy `config.example.js` to `config.js` and insert only the public Supabase anon/publishable key to enable real authentication and live data. Never use a service-role key in this folder.

Sensitive writes use role-checked PostgreSQL functions and are recorded in `audit_logs`. Without a configured public key the panel stays in clearly labelled demo mode.
