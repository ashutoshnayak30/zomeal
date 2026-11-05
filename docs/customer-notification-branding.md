# Customer notification branding fix

## Single-icon follow-up · version 1.0.3

Removed `setLargeIcon` at the user's request, so there is no duplicate logo on the right. Application, round icon and launcher activity now all reference the same density-specific green `ic_zomeal_app` PNG family. Notification small icon remains the transparent white `ic_zomeal_status`. This removes the activity's separate adaptive-icon override; OEM icon caching/display still requires a fresh installed-build check on the user's phone. Titles/messages and deployed data-only push behavior are unchanged.

## Follow-up correction · 25 September

Manual and scheduled customer payloads must omit BOTH `message.notification` and `message.android.notification`. The earlier change left Android-specific automatic-rendering settings behind. Regression tests now assert the latter is absent too; provider payloads keep their automatic-rendering settings.

Customer version 1.0.2 (code 2) uses explicit raster application icons in five densities for OEM notification-panel compatibility, while its launcher activity retains the adaptive vector icon. Status icons are transparent white PNGs at five densities. The local renderer prioritizes nonblank data title/body over the optional notification fields and does not post empty-body messages.

The customer notification builder now attaches the green-background logo using `setLargeIcon`, with the transparent monochrome companion as `setSmallIcon`. The launcher identity is unchanged from the 24 September logo build.

Customer messages from the manual, meal-debit and scheduled-campaign senders now use high-priority data payloads. This lets the installed FirebaseMessagingService construct branded notifications in foreground and background, instead of the Firebase automatic notification renderer bypassing the local large icon. Provider payloads remain unchanged.

Message titles, bodies, destinations and stable meal/campaign notification IDs are preserved. No database changes are needed. Devices still require notification permission and a valid token. Force-stopping the app or restrictive battery settings can prevent background delivery. OEM notification layouts control the placement of the large image; the status bar uses monochrome, not a full-colour background.

Validation: mocked FCM worker tests passed for customer data-only messages, provider automatic messages, authorization and retry handling. Test the new APK on the affected physical phone with a fresh notification after opening the app once; an older already-posted notification is not updated retroactively.
