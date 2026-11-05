# BIKI recording and review refresh

- Green header, mint status card, recording progress, numbered compact review cards.
- Voice dialog and expense sheet use system-fitting dialog windows and bottom
  navigation/keyboard padding so actions remain above phone controls.
- Consent is stored locally per authenticated user and policy version in separate
  BIKI preferences. Privacy settings can revoke it. Preview consent is not stored
  as consent to cloud processing. New accounts and policy versions ask again.
- App sends `language: auto`. Backend accepts auto as well as old Hindi/Odia
  requests. Existing Chirp 2 request passes `languageCodes: [auto]` to Singapore;
  interpretation remains in India. No extra language-detection model call.

Deploy the v6 backend zip BEFORE installing the new APK. Old backend validation
rejects auto. Old APK remains compatible with the new backend.

Google documents synchronous auto language recognition:
https://docs.cloud.google.com/speech-to-text/docs/models/chirp-2

Live Hindi and Odia recordings still need device testing; automatic detection is
not a guarantee of perfect recognition of short or mixed-language recordings.
Review all amounts. Nothing saves automatically.

Device QA: three-button and gesture navigation; keyboard open; large font;
reopen voice dialog/app and verify consent remembered; switch account; revoke
consent; purchase/stock entry; foreground recording; save expense sheet.
