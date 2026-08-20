package com.zomeal.provider

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import org.json.JSONArray
import java.text.NumberFormat
import java.util.Locale

private val DBrand = Color(0xFF087F43)
private val DInk = Color(0xFF14221B)
private val DMuted = Color(0xFF68736D)
private val DMist = Color(0xFFF0F7F2)

@Composable
fun ProviderDashboardScreen(repository: SupabaseProviderRepository, onOrders: () -> Unit, onEarnings: () -> Unit, onProfile: () -> Unit, onNotifications: () -> Unit, onSignOut: () -> Unit) {
    var slot by remember { mutableStateOf("LUNCH") }
    var data by remember { mutableStateOf<JSONObject?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var updating by remember { mutableStateOf(false) }
    var updateMessage by remember { mutableStateOf<String?>(null) }
    var assigningPerson by remember { mutableStateOf<JSONObject?>(null) }
    var assignmentCount by remember { mutableStateOf("100") }
    var assignmentMessage by remember { mutableStateOf<String?>(null) }
    var unreadNotifications by remember { mutableIntStateOf(0) }
    fun load() {
        loading = true; error = null
        repository.loadDailyDashboard(slot) { result, message -> data = result; error = message; loading = false }
    }
    LaunchedEffect(slot) { load(); repository.loadNotifications { result, _ -> unreadNotifications=result?.optInt("unread_count")?:0 } }
    val metrics = data?.optJSONObject("metrics") ?: JSONObject()
    val isFinal = data?.optBoolean("is_final") == true
    val active = metrics.optInt("active")
    val capacity = metrics.optInt("capacity")
    val gross = metrics.optLong("gross_paise")
    val commission = data?.optJSONObject("commission") ?: JSONObject()
    val commissionRate = commission.optDouble("rate_percent",14.0)
    val commissionPaise = commission.optLong("commission_paise",(gross*commissionRate/100.0).toLong())
    val previewMode = data?.optBoolean("preview_mode") == true
    // Kept behind one flag so the post-MVP live tracking workflow can be restored without a rewrite.
    val liveOrderStatusEnabled = false
    fun loadSamplePreview() {
        val copy = JSONObject(data.toString())
        val sampleMetrics = copy.optJSONObject("metrics") ?: JSONObject().also { copy.put("metrics", it) }
        val sampleActive = if (slot == "LUNCH") 130 else 142
        val packageBreakdown = if (slot == "LUNCH") {
            JSONArray()
                .put(JSONObject().put("package_kind", "LUNCH_ONLY").put("label", "Lunch-only customers").put("customers", 50).put("average_value_paise", 6_000L).put("gross_paise", 300_000L))
                .put(JSONObject().put("package_kind", "LUNCH_AND_DINNER").put("label", "Both package · lunch share").put("customers", 80).put("average_value_paise", 5_500L).put("gross_paise", 440_000L))
        } else {
            JSONArray()
                .put(JSONObject().put("package_kind", "DINNER_ONLY").put("label", "Dinner-only customers").put("customers", 62).put("average_value_paise", 5_000L).put("gross_paise", 310_000L))
                .put(JSONObject().put("package_kind", "LUNCH_AND_DINNER").put("label", "Both package · dinner share").put("customers", 80).put("average_value_paise", 4_500L).put("gross_paise", 360_000L))
        }
        val sampleGrossPaise = (0 until packageBreakdown.length()).sumOf { packageBreakdown.optJSONObject(it)?.optLong("gross_paise") ?: 0L }
        val firstChoice = if (slot == "LUNCH") 78 else 82
        sampleMetrics.put("active", sampleActive).put("capacity", 150).put("remaining", 150 - sampleActive)
            .put("paused", if (slot == "LUNCH") 7 else 5).put("cancelled", 2).put("areas", 6)
            .put("preparing", sampleActive).put("packing", 0).put("ready", 0)
            .put("out_for_delivery", 0).put("delivered", 0).put("unassigned_delivery", 0)
            .put("gross_paise", sampleGrossPaise)
        copy.put("package_breakdown", packageBreakdown)
        copy.put("choices", JSONArray().put(JSONObject().put("name", "Main course 1").put("count", firstChoice))
            .put(JSONObject().put("name", "Main course 2").put("count", sampleActive - firstChoice)))
        data = copy
        updateMessage = "Sample preview loaded: $sampleActive ${slot.lowercase()} meals across single-meal and both packages. These numbers are not saved."
    }
    fun updateTracking(status: String) {
        if (previewMode) {
            val copy = JSONObject(data.toString())
            val copyMetrics = copy.optJSONObject("metrics") ?: JSONObject().also { copy.put("metrics", it) }
            listOf("preparing", "packing", "ready", "out_for_delivery", "delivered").forEach { copyMetrics.put(it, 0) }
            copyMetrics.put(status.lowercase(), copyMetrics.optInt("active"))
            data = copy
            updateMessage = "Preview updated to ${status.replace('_', ' ').lowercase()}. No customer was notified."
        } else {
            updating = true; updateMessage = null
            repository.updateDailyMealStatus(slot, status) { result ->
                updating = false
                if (result.success) { updateMessage = "Customer tracking updated."; load() }
                else updateMessage = result.message
            }
        }
    }
    fun assignBatch() {
        val person = assigningPerson ?: return
        val count = assignmentCount.toIntOrNull()?.coerceIn(1, 100) ?: return
        if (previewMode) {
            val copy = JSONObject(data.toString())
            val people = copy.optJSONArray("delivery_people")
            if (people != null) for (index in 0 until people.length()) {
                val item = people.optJSONObject(index)
                if (item?.optString("id") == person.optString("id")) item.put("assigned", count.coerceAtMost(metrics.optInt("active")))
            }
            data = copy; assignmentMessage = "Preview batch assigned. Nothing was saved."; assigningPerson = null
        } else {
            updating = true; assignmentMessage = null
            repository.assignDeliveryBatch(person.optString("id"), slot, count) { result ->
                updating = false
                if (result.success) { assignmentMessage = "Delivery batch assigned successfully."; assigningPerson = null; load() }
                else assignmentMessage = result.message
            }
        }
    }

    assigningPerson?.let { person ->
        AlertDialog(
            onDismissRequest = { if (!updating) assigningPerson = null },
            icon = { Icon(Icons.Outlined.DeliveryDining, null, tint = DBrand) },
            title = { Text("Assign meals to ${person.optString("name").ifBlank { "delivery person" }}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("Choose how many meals this person will carry on the ${slot.lowercase()} route. Maximum 100 meals per batch.", fontSize = 12.sp, color = DMuted)
                    OutlinedTextField(value = assignmentCount, onValueChange = { assignmentCount = it.filter(Char::isDigit).take(3) }, label = { Text("Number of meals") }, singleLine = true)
                }
            },
            confirmButton = { Button(onClick = { assignBatch() }, enabled = !updating && assignmentCount.toIntOrNull()?.let { it in 1..100 } == true) { Text("Assign batch") } },
            dismissButton = { TextButton(onClick = { assigningPerson = null }) { Text("Cancel") } }
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
        containerColor = Color(0xFFF7FAF7),
        bottomBar = { ProviderBottomBar(onOrders, onEarnings, onProfile) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Column(
                    Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(Color(0xFF047A45), Color(0xFF54AF45)))).padding(20.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("zomeal partner", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                            Text(data?.optString("provider_name").orEmpty().ifBlank { "Provider dashboard" }, color = Color.White.copy(alpha = .88f), fontSize = 13.sp)
                        }
                        IconButton(onClick = { load() }, colors = IconButtonDefaults.iconButtonColors(containerColor = Color.White.copy(alpha = .16f))) {
                            Icon(Icons.Outlined.Refresh, "Refresh", tint = Color.White)
                        }
                        BadgedBox(badge={if(unreadNotifications>0)Badge(containerColor=Color(0xFFE53935)){Text(if(unreadNotifications>99)"99+" else unreadNotifications.toString())}}){IconButton(onClick=onNotifications,colors=IconButtonDefaults.iconButtonColors(containerColor=Color.White.copy(alpha=.16f))){Icon(Icons.Outlined.Notifications,"Notifications",tint=Color.White)}}
                        IconButton(onClick = onSignOut, colors = IconButtonDefaults.iconButtonColors(containerColor = Color.White.copy(alpha = .16f))) {
                            Icon(Icons.Outlined.Logout, "Sign out", tint = Color.White)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("Today's meal operations", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Text(data?.optString("date").orEmpty(), color = Color.White.copy(alpha = .82f), fontSize = 12.sp)
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MealSlotButton("LUNCH", "Lunch", Icons.Outlined.WbSunny, slot == "LUNCH", Modifier.weight(1f)) { slot = "LUNCH" }
                    MealSlotButton("DINNER", "Dinner", Icons.Outlined.DarkMode, slot == "DINNER", Modifier.weight(1f)) { slot = "DINNER" }
                }
            }
            if (loading) item { Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = DBrand) } }
            error?.let { message -> item { DashboardNotice(message, true) } }
            if (!loading && data != null) {
                item {
                    DashboardNotice(
                        if (previewMode) "Dashboard preview · Your listing is not active, so no customers are notified and operational totals may be zero."
                        else if (isFinal) "Final ${slot.lowercase()} list · Customer changes are locked for today."
                        else "Live ${slot.lowercase()} data · Customers may still change choices until ${if (slot == "LUNCH") "7:00 AM" else "4:00 PM"} IST.",
                        false
                    )
                }
                item {
                    DashboardSection("Meal summary", "Live subscriptions and today's available capacity") {
                        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            DashboardMetric("Active meals", active.toString(), Icons.Outlined.Groups, Modifier.weight(1f))
                            DashboardMetric("Capacity", capacity.toString(), Icons.Outlined.Inventory2, Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            DashboardMetric("Remaining", metrics.optInt("remaining").toString(), Icons.Outlined.EventAvailable, Modifier.weight(1f))
                            DashboardMetric("Service areas", metrics.optInt("areas").toString(), Icons.Outlined.LocationOn, Modifier.weight(1f))
                        }
                        if (previewMode && active == 0) {
                            OutlinedButton(onClick = { loadSamplePreview() }, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Outlined.Science, null); Spacer(Modifier.width(7.dp)); Text("Load sample data for testing")
                            }
                        }
                    }
                }
                item {
                    DashboardSection("Main-course choices", "Exact quantities to prepare for ${slot.lowercase()}") {
                        val choices = data?.optJSONArray("choices")
                        if (choices == null || choices.length() == 0) {
                            EmptyDashboardLine("No approved main courses or customer selections found for today.")
                        } else for (index in 0 until choices.length()) {
                            val choice = choices.optJSONObject(index) ?: continue
                            ChoiceCount(choice.optString("name"), choice.optInt("count"), active)
                        }
                    }
                }
                item {
                    DashboardSection("Exceptions", "Meals excluded from today's preparation count") {
                        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            DashboardMetric("Paused", metrics.optInt("paused").toString(), Icons.Outlined.PauseCircle, Modifier.weight(1f))
                            DashboardMetric("Cancelled", metrics.optInt("cancelled").toString(), Icons.Outlined.Cancel, Modifier.weight(1f))
                        }
                    }
                }
                if (liveOrderStatusEnabled) item {
                    DashboardSection("Kitchen & delivery progress", "Update these from the daily orders workspace") {
                        ProgressLine("Preparing", metrics.optInt("preparing"), active, Icons.Outlined.SoupKitchen)
                        ProgressLine("Packing", metrics.optInt("packing"), active, Icons.Outlined.Inventory2)
                        ProgressLine("Ready", metrics.optInt("ready"), active, Icons.Outlined.TaskAlt)
                        ProgressLine("Out for delivery", metrics.optInt("out_for_delivery"), active, Icons.Outlined.DeliveryDining)
                        ProgressLine("Delivered", metrics.optInt("delivered"), active, Icons.Outlined.CheckCircle)
                        if (metrics.optInt("unassigned_delivery") > 0) {
                            Text("⚠ ${metrics.optInt("unassigned_delivery")} meals need a delivery-person assignment", color = Color(0xFFB35B00), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                if (liveOrderStatusEnabled) item {
                    DashboardSection("Update customer tracking", "A single update applies to today's entire ${slot.lowercase()} batch and will appear in the customer app") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TrackingButton("Preparing", "PREPARING", Modifier.weight(1f), updating) { updateTracking("PREPARING") }
                            TrackingButton("Packing", "PACKING", Modifier.weight(1f), updating) { updateTracking("PACKING") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TrackingButton("On the way", "OUT_FOR_DELIVERY", Modifier.weight(1f), updating) { updateTracking("OUT_FOR_DELIVERY") }
                            TrackingButton("Delivered", "DELIVERED", Modifier.weight(1f), updating) { updateTracking("DELIVERED") }
                        }
                        updateMessage?.let { Text(it, color = if (it.contains("could not", true)) MaterialTheme.colorScheme.error else DBrand, fontSize = 11.sp) }
                    }
                }
                item {
                    DashboardSection("Delivery team", "Route batches: one delivery person can carry and deliver up to 100 meals") {
                        val people = data?.optJSONArray("delivery_people")
                        if (people == null || people.length() == 0) EmptyDashboardLine("No active delivery people found.")
                        else for (index in 0 until people.length()) {
                            val person = people.optJSONObject(index) ?: continue
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(38.dp).clip(CircleShape).background(DMist), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Person, null, tint = DBrand) }
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(person.optString("name").ifBlank { "Delivery partner" }, color = DInk, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Text(person.optString("phone"), color = DMuted, fontSize = 11.sp)
                                }
                                Text("${person.optInt("assigned")} meals", color = DBrand, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Spacer(Modifier.width(5.dp))
                                TextButton(onClick = { assignmentCount = "100"; assigningPerson = person }, contentPadding = PaddingValues(horizontal = 7.dp)) { Text("Assign", fontSize = 10.sp) }
                            }
                        }
                        assignmentMessage?.let { Text(it, color = DBrand, fontSize = 11.sp) }
                    }
                }
                if (isFinal) item {
                    DashboardSection("Final customer manifest", "Released after the ${if (slot == "LUNCH") "7:00 AM" else "4:00 PM"} cutoff for route preparation") {
                        val manifest = data?.optJSONArray("manifest")
                        if (manifest == null || manifest.length() == 0) EmptyDashboardLine("No finalized customer meals found for this ${slot.lowercase()} batch.")
                        else for (index in 0 until manifest.length()) {
                            val customer = manifest.optJSONObject(index) ?: continue
                            val address = customer.optJSONObject("address")
                            val addressText = if (address == null) "Address unavailable" else listOf(
                                address.optString("house_number"), address.optString("address_line"), address.optString("locality"),
                                address.optString("city"), address.optString("pincode")
                            ).filter { it.isNotBlank() }.joinToString(", ").ifBlank { address.toString() }
                            Surface(color = DMist, shape = RoundedCornerShape(13.dp)) {
                                Column(Modifier.fillMaxWidth().padding(11.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Row { Text(customer.optString("customer_name").ifBlank { "Customer" }, color = DInk, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); Text(customer.optString("meal_type").lowercase().replaceFirstChar(Char::uppercase), color = DBrand, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                                    Text(customer.optString("phone"), color = DMuted, fontSize = 11.sp)
                                    Text(addressText, color = DMuted, fontSize = 11.sp, lineHeight = 15.sp)
                                    Text("Menu: ${customer.optString("main_course").ifBlank { "Default main course" }}", color = DInk, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                        Text("For MVP, the app is the primary manifest. A WhatsApp summary/link can be added after connecting an approved WhatsApp Business provider.", color = DMuted, fontSize = 10.sp, lineHeight = 14.sp)
                    }
                }
                item {
                    DashboardSection("Estimated earnings", "Your agreed commission is deducted once from delivered meal value") {
                        val breakdown = data?.optJSONArray("package_breakdown")
                        if (breakdown != null) for (index in 0 until breakdown.length()) {
                            breakdown.optJSONObject(index)?.let { item ->
                                PackageEarningLine(item)
                            }
                        }
                        if (breakdown != null && breakdown.length() > 0) HorizontalDivider(color = Color(0xFFE1EAE3))
                        MoneyLine("Gross meal value", gross)
                        MoneyLine("Zomeal commission (${dashboardRate(commissionRate)}%)", -commissionPaise)
                        HorizontalDivider(color = Color(0xFFE1EAE3))
                        MoneyLine("Estimated provider earnings", gross - commissionPaise, true)
                        Text("This estimate excludes refunds, approved discounts and payment adjustments.", color = DMuted, fontSize = 10.sp)
                        OutlinedButton(onClick = onEarnings, modifier = Modifier.fillMaxWidth().height(44.dp), shape = RoundedCornerShape(12.dp)) {
                            Icon(Icons.Outlined.AccountBalanceWallet, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(7.dp)); Text("View earnings & payouts", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackingButton(label: String, status: String, modifier: Modifier, disabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = !disabled, modifier = modifier.height(43.dp), shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 5.dp)) {
        Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MealSlotButton(code: String, label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = modifier.height(52.dp), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = if (selected) DBrand else Color.White, contentColor = if (selected) Color.White else DInk)) {
        Icon(icon, code); Spacer(Modifier.width(7.dp)); Text(label, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun DashboardSection(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(18.dp)).background(Color.White).padding(15.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
        Text(title, color = DInk, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, color = DMuted, fontSize = 11.sp)
        content()
    }
}

@Composable
private fun DashboardMetric(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier) {
    Row(modifier.clip(RoundedCornerShape(13.dp)).background(DMist).padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = DBrand, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(8.dp))
        Column { Text(value, color = DInk, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold); Text(label, color = DMuted, fontSize = 10.sp) }
    }
}

@Composable
private fun ChoiceCount(name: String, count: Int, total: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row { Text(name, color = DInk, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); Text("$count meals", color = DBrand, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
        LinearProgressIndicator(progress = { if (total == 0) 0f else count.toFloat() / total }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape), color = DBrand, trackColor = DMist)
    }
}

@Composable
private fun ProgressLine(label: String, count: Int, total: Int, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = DBrand, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(9.dp)); Text(label, color = DInk, fontSize = 12.sp, modifier = Modifier.weight(1f)); Text("$count / $total", color = DBrand, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}

@Composable
private fun DashboardNotice(text: String, error: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(14.dp)).background(if (error) Color(0xFFFFEDEA) else Color(0xFFEAF5EC)).padding(13.dp), verticalAlignment = Alignment.Top) {
        Icon(if (error) Icons.Outlined.ErrorOutline else Icons.Outlined.Schedule, null, tint = if (error) Color(0xFFB23A32) else DBrand, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text(text, color = if (error) Color(0xFF8E302A) else Color(0xFF345C43), fontSize = 11.sp, lineHeight = 16.sp)
    }
}

@Composable private fun EmptyDashboardLine(text: String) { Text(text, color = DMuted, fontSize = 12.sp, lineHeight = 17.sp) }

@Composable
private fun PackageEarningLine(item: JSONObject) {
    val customers = item.optInt("customers")
    val unitValue = NumberFormat.getCurrencyInstance(Locale("en", "IN")).format(item.optLong("average_value_paise") / 100.0)
    val subtotal = NumberFormat.getCurrencyInstance(Locale("en", "IN")).format(item.optLong("gross_paise") / 100.0)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(item.optString("label"), color = DInk, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text("$customers customers × $unitValue meal value", color = DMuted, fontSize = 10.sp)
        }
        Text(subtotal, color = DBrand, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MoneyLine(label: String, paise: Long, strong: Boolean = false) {
    val amount = NumberFormat.getCurrencyInstance(Locale("en", "IN")).format(kotlin.math.abs(paise) / 100.0)
    Row { Text(label, color = if (strong) DInk else DMuted, fontSize = if (strong) 14.sp else 12.sp, fontWeight = if (strong) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f)); Text((if (paise < 0) "- " else "") + amount, color = if (strong) DBrand else DInk, fontSize = if (strong) 16.sp else 12.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun ProviderBottomBar(onOrders: () -> Unit, onEarnings: () -> Unit, onProfile: () -> Unit) {
    NavigationBar(containerColor = Color.White, tonalElevation = 6.dp, modifier = Modifier.height(64.dp)) {
        NavigationBarItem(true, {}, { Icon(Icons.Outlined.Dashboard, null) }, label = { Text("Dashboard", fontSize = 9.sp) }, colors = NavigationBarItemDefaults.colors(selectedIconColor = DBrand, selectedTextColor = DBrand, indicatorColor = DMist))
        NavigationBarItem(false, onOrders, { Icon(Icons.Outlined.ReceiptLong, null) }, label = { Text("Orders", fontSize = 9.sp) })
        NavigationBarItem(false, onEarnings, { Icon(Icons.Outlined.AccountBalanceWallet, null) }, label = { Text("Earnings", fontSize = 9.sp) })
        NavigationBarItem(false, onProfile, { Icon(Icons.Outlined.AccountCircle, null) }, label = { Text("Profile", fontSize = 9.sp) })
    }
}
private fun dashboardRate(value:Double):String=if(value%1.0==0.0)value.toInt().toString() else String.format(Locale.US,"%.2f",value).trimEnd('0').trimEnd('.')
