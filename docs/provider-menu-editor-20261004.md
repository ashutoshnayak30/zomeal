# Provider weekly-menu editor

- Save Monday & next day (and equivalent daily actions) saves a draft, never submits an approval request.
- Active providers stay in the editor after a day save, including when all seven days are already complete.
- Submit changes for approval is separate, enabled after all days are saved, with a confirmation explaining that other saved business edits are included. A one-day edit uses the same final action.
- Removed the description/ingredients input; existing descriptions remain in the payload.
- Comma-separated side dishes generate corresponding portion fields independently for lunch and dinner. Removing a side removes its serving instruction; unchanged names retain their portions.
- Menu submission no longer calls the separate delivery-contact save endpoint. Contacts remain editable in Photos & delivery. Server-side existing onboarding requirements are unchanged.
- Existing remote media references are not treated as new local uploads, and remote dish photos are no longer cleared by a local content-resolver readability check.

Validation: provider Kotlin compilation and standalone Kotlin portion regression tests. Physical-device UI and actual approval submission have not been exercised. No database migration is required; install a rebuilt provider APK for these changes.
