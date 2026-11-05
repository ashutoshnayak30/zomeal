# Zomeal kitchen voice — backend pilot foundation

## Status

Backend plus Android recording/review integration are implemented locally.
NOT deployed, no live Google calls made, no hosted database migration applied.
Do not announce live AI as available. A BIKI microphone-preview APK is built.
BIKI exposes local recording without a backend; upload stays disabled until the
public `KITCHEN_VOICE_URL` is configured and the app rebuilt. No secrets go in that URL.
The pilot currently fills the existing monthly stock totals; it does NOT yet
provide daily item-level stock history, automatic valuation or shopping forecasts.

## Design

Provider app -> Cloud Run `/v1/draft` -> Supabase Auth getUser -> atomic
provider quota reservation -> Google Speech V2 -> Vertex structured extraction
-> review-only JSON. There is deliberately no save/stock/wallet endpoint here.

The Cloud Run runtime uses attached service identity `zomeal-kitchen-voice`.
No service-account JSON keys, service-role Supabase key, or Google credentials
belong in the APK or repository. The backend uses a Supabase publishable/anon
key with the caller's bearer session. Users cannot choose a provider ID.

Quota: an explicitly enabled, unexpired kitchen pilot; ACTIVE provider;
existing OWNER/MANAGER membership; default 20 requests / 600 audio seconds per
IST day per kitchen. Membership and expiry are checked on every reservation.
Reservations are serialized in Postgres, shared across instances and members.
Failures after reservation consume quota (conservative billing protection).
Repeating a request consumes quota again, but cannot create financial duplicates
because the service does not save entries. RPC callers can consume their own
kitchen quota directly but cannot reset it or grant pilot access.

No audio or transcripts are persisted/logged by application code. They are sent
to Google for processing; obtain provider consent before recording/uploading.
Google platform retention/processing terms still apply. Default speech and
speech endpoint is in `asia-southeast1` (Singapore); transcript extraction uses
`asia-south1` (India), as explicitly approved by the owner. Cloud Run can also
be deployed there. Hindi `hi-IN` and Odia `or-IN` are user-selected, not inferred.

## Local checks (Windows CMD)

```cmd
cd /d D:\Codex\2026-08-09\build\zomeal\services\kitchen-voice
node --test
```

No external packages or credentials required. Tests stub Google/Supabase;
they do not prove hosted IAM, speech accuracy, or SQL concurrency behavior.
Production container targets Node 22. Local tests were also run with Node 20.10.

From the repository root, `node scripts/test-provider-voice.cjs` runs the actual
quota migration in isolated PGlite (using the repository's existing development
dependency). It covers SQL grants, pilot expiry, membership, provider status,
shared kitchen request/seconds quotas and previous-day usage. It does not mutate
hosted Supabase. Real parallel connections still require a staging test.

## Request contract

`POST /v1/draft`, `Authorization: Bearer <Supabase user access token>`, JSON:

```json
{"mode":"PURCHASE","language":"hi-IN","audio":"<base64 raw PCM>"}
```

Modes: PURCHASE, OPENING_STOCK, CLOSING_STOCK. Audio: signed little-endian
16-bit PCM, 16000 Hz, mono, 0.1–60 seconds, no WAV/container header.
Server derives length from bytes, caps body size, and ignores client IDs/duration.
Full recordings must not be submitted twice automatically after ambiguous failures.

Response: transcript, mode, rows with item/category/quantity/unit,
unit_price_paise and amount_paise (nullable), issues, requires_review=true,
saved=false, remaining_requests. Model numbers are untrusted suggestions.
Missing prices remain null, not zero. No market prices are invented by code.
Arithmetic conflicts and non-ingredient stock are flagged for correction.

## Environment (server only)

- `GOOGLE_CLOUD_PROJECT=zomeal-kitchen-ai`
- `SUPABASE_URL=https://tojgwcxfvicrenfabgml.supabase.co`
- `SUPABASE_ANON_KEY`: existing public anon/publishable key, not service-role key
- `VOICE_MODEL`: supported Vertex Gemini model ID; verify availability in the
  selected region before choosing. No model is silently defaulted.
- `VOICE_ENABLED=false`: keep disabled until acceptance checks pass.
- `PORT`: supplied by Cloud Run.

Cloud Run needs HTTPS ingress for the app; the application authenticates ALL
draft requests. Do not expose a service without this auth handler. Keep runtime
min instances 0, max instances 1 initially, concurrency low, timeout 150 seconds.
These settings reduce load but are NOT a monetary spending cap. Set a billing
alert and quota monitoring before pilot deployment. Health reveals no secrets.

## Remaining Google setup — user approval required

October 31 deployment check: Gemini 2.5 Flash/Flash-Lite are retired according to
Google's current model lifecycle documentation. Do not deploy with those IDs.
Gemini 3.5 Flash-Lite lists asia-south1 model availability, but Standard PayGo
lists global/US/EU. Therefore run check-india-model.mjs in Cloud Shell before
deployment; India access is NOT assumed. Owner approved Singapore speech and
India interpretation; code and Android consent now reflect those locations.
No automatic global fallback is permitted. The check uses one billable synthetic
text request, with no kitchen data; never paste its access token into chat.
Sources: https://docs.cloud.google.com/gemini-enterprise-agent-platform/models/model-versions
and https://docs.cloud.google.com/gemini-enterprise-agent-platform/models/gemini/3-5-flash-lite

Already confirmed: billing, Speech API, Cloud Run, Cloud Build, Artifact Registry,
Vertex API, Cloud Speech Client, and the user's `zomealVoiceInference` custom role
with `aiplatform.endpoints.predict` on the runtime account.

Verify model availability with a controlled pilot recording. Do not add build/deploy
permissions to the speech runtime account. Source builds need a separate build
identity and deployer permissions configured according to Cloud Run docs.

## Database and release gates

1. Review `202610310001_provider_voice_pilot.sql` against staging migrations;
   never blindly push all the user's unrelated pending migrations.
2. Apply in staging and test: anonymous, ordinary customer, inactive membership,
   active owner/manager, suspended provider, disabled/expired pilot, unauthorized
   direct table writes, simultaneous quota requests and IST day rollover.
3. Enable ONLY the verified Manju provider UUID with a short expiry, via a trusted
   admin/SQL session. Never select a provider by display name alone (not unique).
   No kitchen is enabled by the migration itself.
4. Verify actual Hindi/Odia recordings: numbers, decimals, unit/total distinction,
   background noise and mixed language. Do not assume transcription accuracy.
5. Device-test the implemented Android recorder: explicit consent, runtime mic
   permission, foreground-only capture, cancellation on background/dispose, a
   60-second byte limit, permission denial and microphone unavailable handling.
6. Device-test the editable review and date/mode label. Expenses go to the normal
   sheet with its existing save UUID/revision semantics. Stock drafts REPLACE
   the selected opening/closing total only, before the normal stock save. Verify
   empty/missing fields, zero closing stock, row removal, rotation, failed uploads,
   and keyboard-safe scrolling. Nothing is saved from speech alone. Original AI
   warnings stay visible until the provider explicitly acknowledges review.
7. Item-level stock storage/cost valuation is STILL required. Existing stock
   review stores monthly aggregate values. Do not simply add daily closing counts
   together or treat stock as a purchase. Use last confirmed closing stock for the
   next opening, with explicit correction, consistent units, and purchase-cost
   valuation. Never silently infer unknown stock price or claim inventory tracking
   is complete with this backend alone.
8. Preserve profit formula: net earnings - (opening + purchases - closing) -
   non-food operating costs - allocated overhead. Gas is operating expense, not
   ingredient stock. Do not deduct commission twice or replace confirmed earnings
   with AI-generated figures. Unknown stock valuation must remain an estimate.

## References checked during implementation

- https://docs.cloud.google.com/speech-to-text/docs/speech-to-text-supported-languages
- https://docs.cloud.google.com/run/docs/securing/service-identity
- https://docs.cloud.google.com/vertex-ai/generative-ai/docs/samples/generativeaionvertexai-gemini-controlled-generation-response-schema-2
