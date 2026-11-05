# BIKI foreground handling

The voice dialog previously observed the calling view's lifecycle, which can be
owned by another dialog. It now resolves the host ComponentActivity from its
context and uses that lifecycle for start checks and background cancellation.
Idle stops no longer generate a misleading recording/request interruption error.
BIKI's own dialog keeps the screen awake only during recording or processing and
restores the previous flag afterward. Genuine activity stops still cancel capture
and invalidate pending results; no background microphone capability was added.

Device acceptance checks (not yet performed):
- Open from both purchase and stock screens; record without switching apps.
- Grant microphone permission on first use; record once the app is resumed.
- Wait during processing without touching the screen; review should remain visible.
- Press Home or lock while recording: capture must stop, no automatic save.
- Close BIKI: microphone must stop and normal screen timeout must return.

Requires rebuilding/installing the provider APK, not a Cloud Run redeployment.
This does not resolve persistent Google model HTTP 429 responses.
