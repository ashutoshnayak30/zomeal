# Play Store bundle handoff

Build with `scripts\build-play-bundles.cmd` from Windows CMD. The script uses private signing properties outside this repository. Never recreate the upload keys for an update.

Outputs:
- Customer: `app/build/outputs/bundle/release/app-release.aab`, package `com.zomeal.app`, version 1.0.6 (6).
- Partner: `provider-app/build/outputs/bundle/release/provider-app-release.aab`, package `com.zomeal.provider`, version 0.1.0 (1).

Both compile/target API 36 and disable DEVELOPMENT_AUTH in release. Signing uses separate RSA upload certificates. Keys and passwords are in `D:\Codex\2026-08-09\build\Zomeal-Release-Keys`, outside Git and restricted to the Windows owner and SYSTEM. Keep an encrypted off-device backup of this entire folder. Share it only with authorized company maintainers through a secure channel. Upload the AABs to Play Console, not the private keystores or signing.properties.

Use separate Play Console app listings. Enable Play App Signing, retain the upload certificates, and record the Play app-signing certificate fingerprints for any services that restrict Android package/certificate identity. Future releases must increment each application's versionCode.

Start with internal testing. Signing and compilation do not certify production readiness: verify OTP login, payments, notifications, provider operations and voice consent/recording on real devices. Review Play Console's required privacy policy, Data safety, app access/reviewer instructions, content rating and account-specific testing requirements. No advertising campaign is required to upload.
