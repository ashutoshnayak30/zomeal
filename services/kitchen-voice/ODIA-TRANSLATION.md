# Odia output update — 11 November 2026

Speech still receives the explicitly selected or-IN language in Singapore.
Validated extraction runs in India. For Odia selections an additional India
Vertex call translates transcript and ingredient labels into Odia. It cannot
modify the extracted quantities, prices, units, categories or totals. No entries
are saved automatically. The result must have Odia script, no Bengali script,
and exactly one translated label per item. Invalid output remains an error.

This adds a paid model call and latency per Odia request. No automatic translation
retry or global-region fallback. Remaining request time bounds translation.
Translation cannot repair all misheard words; review items and amounts carefully.
Automated tests use mock responses, not live speech or live model translation.

Current Android APK accepts the translated transcript; no SQL changes needed.

Cloud Shell deployment after uploading biki-odia-translation-20261111.zip:

```sh
mkdir -p ~/biki-odia-translation-20261111
unzip -n ~/biki-odia-translation-20261111.zip -d ~/biki-odia-translation-20261111
cd ~/biki-odia-translation-20261111
node --test voice.test.mjs
```

Only after all tests pass:

```sh
gcloud run deploy zomeal-kitchen-voice \
  --project=zomeal-kitchen-ai \
  --region=asia-southeast1 \
  --source=. \
  --service-account=zomeal-kitchen-voice@zomeal-kitchen-ai.iam.gserviceaccount.com
```

Uses existing service environment and IAM settings. Do not replace keys, disable
authentication checks or modify the voice pilot for this update. Confirm deployed
revision then test Odia in the provider app. Do not share tokens or real audio.
