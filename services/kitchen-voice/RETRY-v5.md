# BIKI bounded retry

On an explicit Vertex HTTP 429, retry interpretation once after 1.5–2 seconds
of jitter. Respect Retry-After; if it requires more than 5 seconds, return the
existing capacity message instead. Both attempts share the 45-second extraction
budget. No retries for other statuses, network failures or parsing failures.

Speech and Supabase pilot reservation occur once per request. The same transcript
and schema remain in asia-south1. No automatic saves or region fallback.
Maximum model attempts per admitted request increases from one to two; this can
increase model usage/cost. Persistent capacity shortages can still fail.

23 local tests pass. Production deployment and live phone testing are separate.
