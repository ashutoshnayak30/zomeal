const fs = require('node:fs');
const assert = require('node:assert/strict');
const root = 'provider-app/src/main/java/com/zomeal/provider/';
const shared = fs.readFileSync(root + 'ProviderOperationsUi.kt', 'utf8');
// Guard the actual Kotlin thresholds and evaluate their boundary semantics.
const bounds = shared.match(/hour \* 60 \+ minute in (\d+) until (\d+)/);
assert.ok(bounds, 'Session window must be explicit and shared');
const slot = (h, m) => h * 60 + m >= Number(bounds[1]) && h * 60 + m < Number(bounds[2]) ? 'LUNCH' : 'DINNER';
for (const [h, m, expected] of [[0,0,'DINNER'],[2,29,'DINNER'],[2,30,'LUNCH'],[14,29,'LUNCH'],[14,30,'DINNER'],[23,59,'DINNER']]) assert.equal(slot(h,m), expected);
assert.ok(shared.includes('Asia/Kolkata'));
const orders = fs.readFileSync(root + 'ProviderDailyOrdersScreen.kt','utf8');
assert.ok(orders.includes('automaticMealSlot(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE))'));
assert.ok(orders.includes('isPreparationMeal(it) && it.optString("delivery_person")'));
assert.ok(orders.includes('Do not prepare or deliver'));
assert.ok(orders.includes('observedToday != today'));
assert.ok(fs.readFileSync(root + 'ProviderDashboardScreen.kt','utf8').includes('takeIf(::isPreparationMeal)'));
console.log('PASS: source regression guards and IST session boundary model (02:30 / 14:30).');
