# Customer motion — first pass

Reference: the supplied healthcare booking reel. Adapted to Zomeal colours; no third-party animation service or dependency.

- Home content and meal cards fade/lift in; My Plans, Orders, Profile and provider details use the same entry language.
- Navigation's mint highlight slides between existing route positions. Route order and actions are unchanged. A composition-local animation stores only the visual position across screen replacement.
- Order meal-details dialog has an entry animation (not a shared-image/card morph).
- Submit review has press feedback, contracts to a loading circle, prevents repeat submissions and returns to its normal state on failure.
- Existing review success dialog uses an animated check, strictly after the API returns `saved=true`. No artificial delay or synthetic success.
- Android animator duration scale controls Compose animations, including disabled animations. No outgoing-screen crossfade that retains duplicate side effects.

Scope: customer app only. Splash effects, provider app motion and richer shared-element transitions are not included in this pass. Existing local changes in MainActivity were preserved.

Manual device checks: rapidly switch tabs/back, open and close meal details, scroll/re-enter Home, test review success/error and rapid repeat taps, enable TalkBack, set animator duration scale to zero, and check large fonts on a small display. No live review should be posted just for testing.

Build in Windows CMD (no backend deployment required):

```cmd
cd /d C:\Users\HP\Documents\Codex\2026-08-09\build\zomeal
set "JAVA_HOME=C:\Program Files\Java\jdk-17"
gradlew.bat :app:assembleDebug
```

APK: `app\build\outputs\apk\debug\app-debug.apk`. Debug builds use the project's existing development-auth configuration; use your established signed-release process for customer distribution.
