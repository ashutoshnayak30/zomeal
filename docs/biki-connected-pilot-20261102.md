# BIKI connected pilot — 2 November 2026

Cloud Run endpoint: https://zomeal-kitchen-voice-569891833908.asia-southeast1.run.app

Owner-provided outputs confirm deployment, configured public Supabase key,
VOICE_ENABLED=true, and service-level allUsers/run.invoker binding.
Agent independently confirmed unauthenticated POST /v1/draft returns
HTTP 401 with "Please sign in again." on 2 November. No audio was sent.

Owner verified Manju provider 9726ff16-eead-4e63-ba58-e797e8cb97ed, ACTIVE,
phone suffix 6281. Pilot screenshot confirms expiry 2026-11-09 15:51:16 UTC,
20 requests / 600 audio seconds per IST day. Do not extend automatically.

local.properties now points to the live endpoint. Updated Android consent
describes Singapore speech recognition and India transcript interpretation.
Both parent sheets are hidden while the recorder dialog is shown.

Local Node tests (16) and isolated PGlite tests pass. Owner's Cloud Shell
synthetic India model request returned HTTP 200. This does not prove the
Cloud Run service identity can call either speech or extraction APIs.

Pending device acceptance: sign in with the authorized Manju owner account,
open BIKI > Record expenses, consent and allow microphone, record a short
Hindi or Odia purchase, stop, send, inspect transcript and all amounts.
Do not save synthetic purchases into live accounts. Discard after review.
Capture only the error message if unsuccessful; never share session tokens.

This remains a limited pilot, not production certification. No automatic
self-training, wake-word listening, daily item-level stock history or shopping
forecasting. Stock voice drafts replace existing monthly aggregate values
after review; missing purchase prices must be supplied explicitly.
