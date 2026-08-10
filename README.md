# Zomeal

A native Android marketplace for discovering and subscribing to monthly meal plans from nearby kitchens.

## Run locally

1. Open this directory in Android Studio.
2. Allow Gradle to sync.
3. Run the `app` configuration on an Android emulator or device (Android 7.0+).

Or build from a terminal on Windows:

```powershell
.\gradlew.bat assembleDebug
```

The first customer experience is the locality-gated provider discovery screen. It includes live search, dietary filters, rating sort, provider cards, an empty state, and bottom navigation, built with Kotlin and Jetpack Compose.
