package com.zomeal.provider

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private val KitchenGreen = Color(0xFF087F43)
private val KitchenInk = Color(0xFF14221B)
private val KitchenMuted = Color(0xFF68736D)

@Composable
fun ProviderDashboardScreen(repository: SupabaseProviderRepository, bannerMessage: String? = null, onDismissBanner: () -> Unit = {}, onOrders: () -> Unit, onEarnings: () -> Unit, onProfile: () -> Unit, onManageBusiness: () -> Unit, onOrdersForSession: (String) -> Unit = { onOrders() }, onNotifications: () -> Unit, onCosts: () -> Unit, onSignOut: () -> Unit) {
    var selectedSession by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(
        if (currentAutomaticMealSlot() == "DINNER") "Dinner" else "Lunch"
    ) }
    var sessionMenuOpen by remember { mutableStateOf(false) }
    var lunch by remember { mutableStateOf<JSONObject?>(null) }
    var dinner by remember { mutableStateOf<JSONObject?>(null) }
    var lunchUpdated by remember { mutableStateOf("") }
    var dinnerUpdated by remember { mutableStateOf("") }
    var lunchError by remember { mutableStateOf<String?>(null) }
    var dinnerError by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var demoAvailable by remember { mutableStateOf(false) }
    var showDemo by remember { mutableStateOf(false) }
    var automaticSession by remember { mutableStateOf(currentAutomaticMealSlot()) }
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {
        val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("Asia/Kolkata") }
        var observedDay = dayFormat.format(Date())
        while (true) {
            kotlinx.coroutines.delay(15_000L)
            val activity = context as? androidx.activity.ComponentActivity
            if (activity != null && !activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) continue
            val session = currentAutomaticMealSlot()
            val day = dayFormat.format(Date())
            if (session != automaticSession) {
                automaticSession = session
                selectedSession = if (session == "DINNER") "Dinner" else "Lunch"
                refresh++
            } else if (day != observedDay) {
                refresh++
            }
            observedDay = day
        }
    }
    var unread by remember { mutableIntStateOf(0) }
    var today by remember { mutableStateOf("") }
    var profile by remember { mutableStateOf<JSONObject?>(null) }
    val requests = remember { OperationsRequestGate() }
    DisposableEffect(Unit) { onDispose { requests.invalidate() } }
    LaunchedEffect(refresh) {
        val request = requests.next()
        repository.loadDemoPreview { result, _ -> if(requests.accepts(request)) demoAvailable = result?.optBoolean("enabled") == true }
        today = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("Asia/Kolkata") }.format(Date())
        lunch = null; dinner = null; lunchError = null; dinnerError = null
        profile = null
        repository.loadProviderProfileHub { result, _ -> if (requests.accepts(request)) profile = result }
        repository.loadDailyDashboard("LUNCH", today) { result, error ->
            if (requests.accepts(request)) { lunch = result; lunchUpdated = kitchenTimestamp(); lunchError = error ?: if (result == null) "Lunch counts could not be loaded." else null }
        }
        repository.loadDailyDashboard("DINNER", today) { result, error ->
            if (requests.accepts(request)) { dinner = result; dinnerUpdated = kitchenTimestamp(); dinnerError = error ?: if (result == null) "Dinner counts could not be loaded." else null }
        }
        repository.loadNotifications { result, _ -> if (requests.accepts(request)) unread = result?.optInt("unread_count") ?: 0 }
    }
    if(showDemo) { ProviderDemoScreen(repository) { showDemo = false; refresh++ }; return }
    val selectedData = if (selectedSession == "Lunch") lunch else dinner
    val selectedError = if (selectedSession == "Lunch") lunchError else dinnerError
    val updated = if (selectedSession == "Lunch") lunchUpdated else dinnerUpdated
    val name = (lunch ?: dinner)?.optString("provider_name").orEmpty().ifBlank { "Your kitchen" }
    Scaffold(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
        containerColor = Color(0xFFF7FAF7),
        bottomBar = { ProviderNavigation("Dashboard", {}, onOrders, onEarnings, onProfile, onCosts) }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                Column(Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(Color(0xFF047A45), Color(0xFF48A743)))).padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("$selectedSession preparation", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                            Text(name, color = Color.White.copy(alpha = .9f), fontSize = 13.sp)
                        }
                        IconButton(onClick = { refresh++ }) { Icon(Icons.Outlined.Refresh, "Refresh meal counts", tint = Color.White) }
                        IconButton(onClick = onNotifications) {
                            BadgedBox(badge = { if (unread > 0) Badge { Text(if (unread > 99) "99+" else unread.toString()) } }) {
                                Icon(Icons.Outlined.NotificationsNone, "Notifications", tint = Color.White)
                            }
                        }
                    }
                    Text(kitchenDate(today), color = Color.White.copy(alpha = .85f), fontSize = 12.sp)
                }
            }
            bannerMessage?.let { message -> item {
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(message, Modifier.weight(1f), color = KitchenGreen, fontSize = 12.sp)
                    IconButton(onClick = onDismissBanner) { Icon(Icons.Outlined.Close, "Dismiss message") }
                }
            } }
            if(demoAvailable) item {
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFE6F4EA))) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Try your kitchen demo", color = KitchenGreen, fontWeight = FontWeight.Bold)
                        Text("Explore sample customers and practice preparing, packing and delivery. Live orders stay separate.", fontSize = 12.sp)
                        Button(onClick = { showDemo = true }) { Text("Open demo") }
                    }
                }
            }
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box {
                        OutlinedButton(onClick = { sessionMenuOpen = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(14.dp)) {
                            Icon(if (selectedSession == "Lunch") Icons.Outlined.WbSunny else Icons.Outlined.DarkMode, null, Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("$selectedSession preparation", modifier = Modifier.weight(1f), fontSize = 14.sp)
                            Icon(Icons.Outlined.ExpandMore, "Choose cooking session")
                        }
                        DropdownMenu(expanded = sessionMenuOpen, onDismissRequest = { sessionMenuOpen = false }) {
                            listOf("Lunch", "Dinner").forEach { session ->
                                DropdownMenuItem(
                                    text = { Text("$session preparation · " + if (session == "Lunch") "Morning" else "Afternoon") },
                                    onClick = { selectedSession = session; sessionMenuOpen = false },
                                    leadingIcon = { Icon(if (session == "Lunch") Icons.Outlined.WbSunny else Icons.Outlined.DarkMode, null) }
                                )
                            }
                        }
                    }
                }
            }
            item {
                KitchenPreparation(selectedSession, selectedData, selectedError, updated, { refresh++ }, repository, profile)
            }
            if (selectedData != null && selectedError == null && !selectedData.optBoolean("preview_mode") && selectedData.optJSONObject("metrics")?.has("active") == true) {
                item { KitchenDelivery(selectedData) { onOrdersForSession(selectedSession.uppercase(Locale.US)) } }
                item { KitchenProgress(selectedData, selectedSession, repository) { refresh++ } }
            }
            item {
                TextButton(onClick = { onOrdersForSession(selectedSession.uppercase(Locale.US)) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = 48.dp)) {
                    Icon(Icons.Outlined.ReceiptLong, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("View $selectedSession orders")
                }
            }
        }
    }
}

@Composable
private fun KitchenPreparation(label: String, data: JSONObject?, error: String?, updated: String, retry: () -> Unit, repository: SupabaseProviderRepository, profile: JSONObject?) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp), color = Color.White, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (label == "Lunch") Icons.Outlined.WbSunny else Icons.Outlined.DarkMode, null, tint = KitchenGreen, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text("Today's $label", color = KitchenInk, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            }
            when {
                error != null -> { Text(error, color = Color(0xFFB3261E), fontSize = 13.sp); TextButton(onClick = retry) { Text("Retry counts") } }
                data == null -> LinearProgressIndicator(Modifier.fillMaxWidth(), color = KitchenGreen)
                data.optBoolean("preview_mode") -> Text("Kitchen is not active. These are not confirmed cooking quantities.", color = KitchenMuted, fontSize = 13.sp)
                data.optJSONObject("metrics")?.has("active") != true || data.optJSONObject("metrics")?.isNull("active") == true -> {
                    Text("Meal totals are unavailable. Refresh before preparing food.", color = Color(0xFFB3261E), fontSize = 13.sp)
                    TextButton(onClick = retry) { Text("Retry counts") }
                }
                else -> {
                    val metrics = data.optJSONObject("metrics")
                    val active = metrics?.optInt("active") ?: 0
                    val final = data.optBoolean("is_final")
                    Surface(color = if(final) Color(0xFFEAF6EE) else Color(0xFFFFF3D9), shape = RoundedCornerShape(8.dp)) {
                        Text(if (final) "Final counts" else "Provisional · closes ${operationsCutoff(data)}",
                            color = if(final) KitchenGreen else Color(0xFF805200), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp))
                    }
                    Text("Updated $updated · IST", color = KitchenMuted, fontSize = 11.sp)
                    Surface(color = KitchenGreen, shape = RoundedCornerShape(16.dp)) {
                        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("TOTAL MEALS", color = Color.White.copy(alpha = .85f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Text(active.toString(), color = Color.White, fontSize = 48.sp, fontWeight = FontWeight.ExtraBold)
                                Text("Today's full preparation count", color = Color.White, fontSize = 12.sp)
                            }
                            Icon(Icons.Outlined.Restaurant, null, Modifier.size(42.dp), tint = Color.White.copy(alpha = .85f))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        KitchenStat("Paused", metrics?.optInt("paused") ?: 0, Color(0xFFFFF3DC), Color(0xFF936015), Modifier.weight(1f))
                        KitchenStat("Cancelled", metrics?.optInt("cancelled") ?: 0, Color(0xFFFDEEEB), Color(0xFFAD493C), Modifier.weight(1f))
                    }
                    Text("Paused & cancelled meals excluded", color = KitchenMuted, fontSize = 11.sp)
                    if ((metrics?.optInt("delivered") ?: 0) > 0) Text("Includes ${metrics?.optInt("delivered")} already delivered · not extra meals to cook", color = KitchenMuted, fontSize = 12.sp)
                    HorizontalDivider(color = Color(0xFFE5EDE7))
                    val portions = kitchenPortions(data)
                    val counted = portions.sumOf { it.second }
                    when {
                        active == 0 -> Text("No meals to prepare for $label today.", color = KitchenMuted, fontSize = 13.sp)
                        counted > active -> Text("Dish counts do not match the meal total. Refresh and check Orders before cooking.", color = Color(0xFFB3261E), fontSize = 13.sp)
                        else -> {
                            Text("What to cook", color = KitchenInk, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                            portions.forEach { (name, count) ->
                                Surface(color = Color(0xFFF2F7F1), shape = RoundedCornerShape(14.dp)) {
                                    Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        val photo = kitchenDishPhoto(profile, data.optString("date"), label, name)
                                        if (photo.isNotBlank()) {
                                            ProviderApprovedPhoto(repository, photo, name, Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)), compact = true)
                                        } else {
                                            Box(Modifier.size(64.dp).background(Color(0xFFE3EEDC), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                                                Icon(Icons.Outlined.Restaurant, "No photo for $name", tint = KitchenGreen, modifier = Modifier.size(30.dp))
                                            }
                                        }
                                        Text(name, Modifier.weight(1f), color = KitchenInk, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                        Column(horizontalAlignment = Alignment.End) {
                                            Text(count.toString(), color = KitchenGreen, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
                                            Text("portions", color = KitchenMuted, fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                            val servingNotes = kitchenServingNotes(profile, data.optString("date"), label)
                            if (servingNotes.isNotBlank()) {
                                Text("Serving size · per person", color = KitchenInk, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                Text(servingNotes, color = KitchenMuted, fontSize = 12.sp)
                            }
                            Text("Sides: check individual menus in Orders", color = KitchenMuted, fontSize = 12.sp)
                            // Exact side-item quantities are not supplied by the current meal data.
                            if (counted < active) Text("${active - counted} meals have no matched main course. Check Orders before preparing these portions.", color = Color(0xFFB3261E), fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

/** Final manifests count individual meals; provisional counts come from the server aggregate. */
internal fun kitchenDishPhoto(profile: JSONObject?, date: String, session: String, name: String): String {
    val day = runCatching { java.time.LocalDate.parse(date).dayOfWeek.value }.getOrNull() ?: return ""
    val rows = profile?.optJSONArray("weekly_menu") ?: return ""
    val matches = mutableSetOf<String>()
    for (index in 0 until rows.length()) {
        val row = rows.optJSONObject(index) ?: continue
        if (row.optInt("day_of_week") != day || !row.optString("meal_slot").equals(session, true)) continue
        val items = row.optJSONArray("items") ?: continue
        for (itemIndex in 0 until items.length()) {
            val item = items.optJSONObject(itemIndex) ?: continue
            if (!item.optString("category").equals("MAIN_COURSE", true) || !item.optString("name").trim().equals(name.trim(), true)) continue
            item.optString("photo_path").takeUnless { it.isBlank() || it == "null" }?.let(matches::add)
        }
    }
    // Never borrow another day's/slot's photo or choose an ambiguous same-name image.
    return matches.singleOrNull().orEmpty()
}

internal fun kitchenServingNotes(profile: JSONObject?, date: String, session: String): String {
    val day = runCatching { java.time.LocalDate.parse(date).dayOfWeek.value }.getOrNull() ?: return ""
    val rows = profile?.optJSONArray("weekly_menu") ?: return ""
    val notes = mutableSetOf<String>()
    for (index in 0 until rows.length()) {
        val row = rows.optJSONObject(index) ?: continue
        if (row.optInt("day_of_week") != day || !row.optString("meal_slot").equals(session, true)) continue
        val items = row.optJSONArray("items") ?: continue
        for (j in 0 until items.length()) {
            val item = items.optJSONObject(j) ?: continue
            val text = item.optString("name").substringAfter(" · Portions: ", "")
            if (item.optString("category") != "MAIN_COURSE" && text.isNotBlank()) notes += text
        }
    }
    return notes.joinToString("\n")
}

@Composable
private fun KitchenStat(label: String, count: Int, background: Color, ink: Color, modifier: Modifier = Modifier) {
    Surface(modifier, color = background, shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(count.toString(), fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = ink)
            Text(label, fontSize = 12.sp, color = ink)
        }
    }
}

internal fun kitchenPortions(data: JSONObject): List<Pair<String, Int>> {
    val manifestRows = data.optJSONArray("manifest")
    val manifest = manifestRows?.let { rows -> org.json.JSONArray().apply {
        for (index in 0 until rows.length()) rows.optJSONObject(index)?.takeIf(::isPreparationMeal)?.let { put(it) }
    } }
    val active = data.optJSONObject("metrics")?.optInt("active") ?: 0
    if (data.optBoolean("is_final") && manifest != null && manifest.length() == active && active > 0) {
        return (0 until manifest.length()).mapNotNull { index ->
            manifest.optJSONObject(index)?.optString("main_course")?.takeUnless { it.isBlank() || it == "null" }
        }.groupingBy { it }.eachCount().toList().sortedBy { it.first }
    }
    val choices = data.optJSONArray("choices") ?: return emptyList()
    return (0 until choices.length()).mapNotNull { index ->
        val row = choices.optJSONObject(index) ?: return@mapNotNull null
        val name = row.optString("name")
        if (name.isBlank() || name == "null") null else name to row.optInt("count").coerceAtLeast(0)
    }.sortedBy { it.first }
}

private fun kitchenTimestamp(): String = SimpleDateFormat("h:mm a", Locale.ENGLISH).apply {
    timeZone = TimeZone.getTimeZone("Asia/Kolkata")
}.format(Date())

private fun kitchenDate(value: String): String = runCatching {
    val zone = TimeZone.getTimeZone("Asia/Kolkata")
    val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = zone }.parse(value) ?: return@runCatching value
    SimpleDateFormat("EEE, d MMM yyyy", Locale.ENGLISH).apply { timeZone = zone }.format(date)
}.getOrDefault(value)

@Composable
private fun KitchenDelivery(data: JSONObject, onOrders: () -> Unit) {
    val metrics = data.optJSONObject("metrics") ?: return
    val active = metrics.optInt("active")
    if (active <= 0) return
    val unassigned = metrics.optInt("unassigned_delivery")
    Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp), color = Color(0xFFF0F5F1), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Delivery", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = KitchenInk)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                KitchenStat("No rider", unassigned, Color(0xFFFFF3DC), Color(0xFF936015), Modifier.weight(1f))
                KitchenStat("On route", metrics.optInt("out_for_delivery"), Color(0xFFEAF0FC), Color(0xFF395B9B), Modifier.weight(1f))
                KitchenStat("Delivered", metrics.optInt("delivered"), Color(0xFFE3F3E8), KitchenGreen, Modifier.weight(1f))
            }
            val manifest = data.optJSONArray("manifest")
            val riders = (0 until (manifest?.length() ?: 0)).mapNotNull { manifest?.optJSONObject(it) }
                .filter { isPreparationMeal(it) && it.optString("delivery_person").let { name -> name.isNotBlank() && name != "null" } }
                .groupBy { it.optString("delivery_person_id") to it.optString("delivery_person") }
            riders.forEach { (rider, meals) ->
                Text("${rider.second} · ${meals.size} meals", fontSize = 13.sp, color = KitchenInk)
            }
            TextButton(onClick = onOrders) { Text("Manage deliveries →") }
        }
    }
}
