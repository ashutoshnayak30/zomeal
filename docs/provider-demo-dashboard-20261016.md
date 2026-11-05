# Preparation dashboard demo

Open demo now uses the provider preparation screen's green gradient header, white preparation card, green total-meals tile, amber paused tile and red cancelled tile. Sample labels remain visible; no real operations are performed.

Choose Admin setup (existing configured sample count), 100, 200 or 500 customers. Larger scenarios are generated locally only while an authorized, unexpired provider demo is enabled. No database migration or admin deployment is required for these preview options.

Each meal session has 10% paused and 5% cancelled, rounded down. Total preparation = customers minus paused minus cancelled. For 100 customers: 85 prepare, 10 paused, 5 cancelled. For 200: 170 / 20 / 10. For 500: 425 / 50 / 25. Dish totals, meal value and delivery progress use only active meals. Delivered meals remain in the preparation total. Lunch and dinner practice progress are separate.

Active, Paused, Cancelled and All filters display the relevant sample customers. Paused/cancelled customers cannot be prepared or delivered. Scenario changes and leaving the preview reset practice progress. No live orders, wallets, earnings or notifications are modified.

Build in Windows CMD:

```cmd
cd /d C:\Users\HP\Documents\Codex\2026-08-09\build\zomeal
set "JAVA_HOME=C:\Program Files\Java\jdk-17"
gradlew.bat :provider-app:assembleDebug
explorer provider-app\build\outputs\apk\debug
```

Install the updated provider debug APK, refresh Home, then Open demo. Physical-device acceptance: check each scenario's totals, switch lunch/dinner, filter paused/cancelled, practice delivery, return to live Home and confirm live counts remain unchanged. Check narrow screens and large fonts. No APK is built by the assistant.
