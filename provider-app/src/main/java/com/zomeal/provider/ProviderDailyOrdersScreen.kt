package com.zomeal.provider

import android.content.Intent
import android.content.Context
import android.app.DatePickerDialog
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val OBrand = Color(0xFF087F43)
private val OInk = Color(0xFF14221B)
private val OMuted = Color(0xFF68736D)
private val OMist = Color(0xFFF0F7F2)

@Composable
fun ProviderDailyOrdersScreen(repository: SupabaseProviderRepository, onDashboard: () -> Unit, onProfile: () -> Unit, onEarnings: () -> Unit, onCosts: () -> Unit, initialSlot: String = "AUTO") {
    val context = LocalContext.current
    val operationsZone = remember { java.util.TimeZone.getTimeZone("Asia/Kolkata") }
    val isoDate = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false; timeZone = operationsZone } }
    val friendlyDate = remember { SimpleDateFormat("EEE, dd MMM yyyy", Locale.ENGLISH).apply { timeZone = operationsZone } }
    var selectedDate by rememberSaveable { mutableStateOf(isoDate.format(Date())) }
    var slot by remember(initialSlot) { mutableStateOf(initialSlot.takeIf { it in setOf("LUNCH", "DINNER") } ?: currentAutomaticMealSlot()) }
    var automaticSession by rememberSaveable { mutableStateOf(currentAutomaticMealSlot()) }
    var observedToday by rememberSaveable { mutableStateOf(isoDate.format(Date())) }
    var dashboard by remember { mutableStateOf<JSONObject?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf("ALL") }
    var routeAssigning by remember { mutableStateOf(false) }
    var routeMessage by remember { mutableStateOf<String?>(null) }
    var pendingShare by remember { mutableStateOf<Pair<JSONObject, List<JSONObject>>?>(null) }
    val requests = remember { OperationsRequestGate() }
    DisposableEffect(Unit) { onDispose { requests.invalidate() } }
    fun moveDate(days:Int){
        if (routeAssigning) return
        val calendar=Calendar.getInstance(operationsZone).apply{time=isoDate.parse(selectedDate)?:Date();add(Calendar.DAY_OF_MONTH,days)}
        selectedDate=isoDate.format(calendar.time)
    }
    fun openCalendar(){
        if (routeAssigning) return
        val calendar=Calendar.getInstance(operationsZone).apply{time=isoDate.parse(selectedDate)?:Date()}
        DatePickerDialog(context,{_,year,month,day->
            selectedDate=isoDate.format(Calendar.getInstance(operationsZone).apply{set(year,month,day,12,0,0);set(Calendar.MILLISECOND,0)}.time)
        },calendar.get(Calendar.YEAR),calendar.get(Calendar.MONTH),calendar.get(Calendar.DAY_OF_MONTH)).show()
    }
    fun load(quiet: Boolean = false) {
        val request = requests.next()
        val requestedSlot = slot
        val requestedDate = selectedDate
        if (!quiet) { loading = true; dashboard = null; pendingShare = null }
        error = null
        repository.loadDailyDashboard(requestedSlot,requestedDate) { result, message ->
            if (requests.accepts(request) && slot == requestedSlot && selectedDate == requestedDate) {
                if (result != null || !quiet) dashboard = result
                error = message ?: if (result == null) "Could not load orders. Please refresh." else null
                loading = false
            }
        }
    }
    LaunchedEffect(slot,selectedDate) { search = ""; statusFilter = "ALL"; routeMessage = null; load() }
    // Switch at 02:30 / 14:30 IST, independent of customer change cutoffs.
    // Poll only while visible; fresh data unlocks the manifest at either cutoff.
    LaunchedEffect(slot, selectedDate) {
        while (true) {
            kotlinx.coroutines.delay(15_000L)
            val activity = context as? androidx.activity.ComponentActivity
            if (activity != null && !activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) continue
            if (routeAssigning || pendingShare != null || loading) continue
            val now = Calendar.getInstance(operationsZone)
            val today = isoDate.format(now.time)
            if (observedToday != today) {
                val followedToday = selectedDate == observedToday
                observedToday = today
                if (followedToday) { selectedDate = today; continue }
            }
            val nextSession = automaticMealSlot(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE))
            if (selectedDate == today && automaticSession != nextSession) {
                automaticSession = nextSession
                if (slot != nextSession) { slot = nextSession; continue }
            }
            load(quiet = true)
        }
    }
    val isFinal = dashboard?.optBoolean("is_final") == true
    val preview = dashboard?.optBoolean("preview_mode") == true
    val unlockedForTesting = isFinal || preview
    var manifest by remember(dashboard) { mutableStateOf(jsonObjects(dashboard?.optJSONArray("manifest"))) }
    val visible = manifest.filter { customer ->
        val address = addressText(customer.optJSONObject("address"))
        val matchesSearch = search.isBlank() || listOf(customer.optString("customer_name"), customer.optString("main_course"), address).any { it.contains(search, true) }
        val matchesStatus = statusFilter == "ALL" || customer.optString("status") == statusFilter
        matchesSearch && matchesStatus
    }
    val grouped = visible.sortedBy { when(it.optString("status")) { "PAUSED" -> 2; "CANCELLED" -> 3; "DELIVERED" -> 1; else -> 0 } }
        .groupBy { when(it.optString("status")) { "PAUSED" -> "Paused"; "CANCELLED" -> "Cancelled"; "DELIVERED" -> "Delivered"; else -> "Active meals" } }
    val riderGroups = manifest.filter { isPreparationMeal(it) && it.optString("delivery_person").isNotBlank() && it.optString("status") in setOf("SCHEDULED", "PREPARING", "PACKING", "READY", "OUT_FOR_DELIVERY") }
        .groupBy { it.optString("delivery_person_id").ifBlank { it.optString("delivery_person") } }
        .map { (_, customers) -> customers.first() to customers }
    val unassignedCount = manifest.count { isPreparationMeal(it) && it.optString("status") != "DELIVERED" && it.optString("delivery_person").isBlank() }
    val metrics=dashboard?.optJSONObject("metrics")?:JSONObject()
    val commission=dashboard?.optJSONObject("commission")?:JSONObject()
    val packageBreakdown=jsonObjects(dashboard?.optJSONArray("package_breakdown"))
    val choices=jsonObjects(dashboard?.optJSONArray("choices"))

    pendingShare?.let { (rider, customers) ->
        AlertDialog(
            onDismissRequest = { pendingShare = null },
            icon = { Icon(Icons.Outlined.PrivacyTip, null, tint = OBrand) },
            title = { Text("Share private delivery data?") },
            text = { Text("This sends ${customers.size} assigned customer records only to ${rider.optString("delivery_person")}. Confirm that this is the active delivery person for this route.") },
            confirmButton = {
                Button(onClick = {
                    routeAssigning = true
                    repository.loadDeliveryShare(slot, selectedDate, rider.optString("delivery_person_id")) { result, message ->
                        routeAssigning = false
                        if (result == null) routeMessage = message
                        else {
                            val fresh = jsonObjects(result.optJSONArray("customers"))
                            if (fresh.isEmpty()) routeMessage = "No eligible meals remain for this rider. Refresh orders."
                            else shareOnWhatsApp(context, result.optString("rider_phone"), deliveryManifestText(slot, selectedDate, rider, fresh, false))
                        }
                    }
                    pendingShare = null
                }) { Text("Open WhatsApp") }
            },
            dismissButton = { TextButton(onClick = { pendingShare = null }) { Text("Cancel") } }
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
        containerColor = Color(0xFFF7FAF7),
        topBar = {
            Surface(color = Color.White, shadowElevation = 2.dp) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDashboard) { Icon(Icons.Outlined.ArrowBack, "Dashboard", tint = OBrand) }
                    Column(Modifier.weight(1f)) { Text("Daily orders & routes", color = OInk, fontSize = 20.sp, fontWeight = FontWeight.Bold); Text(if (loading) "Loading meal list…" else if (isFinal) "Final preparation list" else "Provisional preparation list", color = OMuted, fontSize = ProviderType.caption) }
                    IconButton(onClick = { load() }) { Icon(Icons.Outlined.Refresh, "Refresh", tint = OBrand) }
                }
            }
        },
        bottomBar = { ProviderNavigation("Orders", onDashboard, {}, onEarnings, onProfile, onCosts) }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(15.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            item {
                Surface(color=Color.White,shape=RoundedCornerShape(16.dp),shadowElevation=1.dp){
                    Row(Modifier.fillMaxWidth().padding(9.dp),verticalAlignment=Alignment.CenterVertically){
                        IconButton(onClick={moveDate(-1)}){Icon(Icons.Outlined.ChevronLeft,"Previous date",tint=OBrand)}
                        OutlinedButton(onClick={openCalendar()},enabled=!routeAssigning,modifier=Modifier.weight(1f).heightIn(min=48.dp),shape=RoundedCornerShape(13.dp)){
                            Icon(Icons.Outlined.CalendarMonth,null,Modifier.size(18.dp));Spacer(Modifier.width(7.dp))
                            Text(friendlyDate.format(isoDate.parse(selectedDate)?:Date()),fontWeight=FontWeight.Bold,fontSize=12.sp)
                        }
                        IconButton(onClick={moveDate(1)}){Icon(Icons.Outlined.ChevronRight,"Next date",tint=OBrand)}
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    SlotOrderButton("Lunch", Icons.Outlined.WbSunny, slot == "LUNCH", Modifier.weight(1f), !routeAssigning) { slot = "LUNCH" }
                    SlotOrderButton("Dinner", Icons.Outlined.DarkMode, slot == "DINNER", Modifier.weight(1f), !routeAssigning) { slot = "DINNER" }
                }
            }
            if (loading) item { Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = OBrand) } }
            error?.let { item { OrderNotice(it, true); TextButton(onClick = { load() }) { Text("Retry loading", fontSize = ProviderType.body) } } }
            if (!loading && dashboard != null && !isFinal && !preview) item { OrderNotice("Customer details for $selectedDate unlock after ${operationsCutoff(dashboard)}. Until then, choices may still change.", false) }
            if (!loading && preview) item { OrderNotice("Preview mode is active. Customer details remain hidden until the production cutoff.", false) }
            if(!loading&&dashboard!=null){
                item{OrderBusinessSummary(slot,metrics,commission)}
                if(packageBreakdown.isNotEmpty())item{OrderPackageBreakdown(packageBreakdown)}
                if(choices.isNotEmpty())item{OrderPreparationSummary(choices)}
            }
            if (!loading && unlockedForTesting && manifest.isNotEmpty()) {
                item {
                    OutlinedTextField(
                        value = search, onValueChange = { search = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                        leadingIcon = { Icon(Icons.Outlined.Search, null) }, placeholder = { Text("Search name, area or meal") }, shape = RoundedCornerShape(14.dp)
                    )
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("ALL" to "All", "PAUSED" to "Paused", "CANCELLED" to "Cancelled").forEach { (code,label) ->
                            FilterChip(statusFilter == code, { statusFilter = code }, { Text(label, fontSize = ProviderType.caption) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp))
                        }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(Color.White).padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Groups, null, tint = OBrand); Spacer(Modifier.width(9.dp)); Column(Modifier.weight(1f)) { Text("${visible.size} meals", color = OInk, fontSize = 18.sp, fontWeight = FontWeight.Bold); Text("Active meals first", color = OMuted, fontSize = ProviderType.caption) }
                        Text(slot.lowercase().replaceFirstChar(Char::uppercase), color = OBrand, fontWeight = FontWeight.Bold)
                    }
                }
                item {
                    Button(
                        onClick = {
                            if (preview) {
                                routeMessage = "Sample routes are already assigned by locality and pincode."
                            } else {
                                routeAssigning = true; routeMessage = null
                                repository.autoAssignRoutes(slot,selectedDate) { result ->
                                    routeAssigning = false
                                    routeMessage = result.message ?: if (result.success) "Routes assigned successfully" else "Routes could not be assigned"
                                    if (result.success) load()
                                }
                            }
                        },
                        enabled = !routeAssigning,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 49.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = OBrand)
                    ) {
                        if (routeAssigning) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                        else Icon(Icons.Outlined.Route, null)
                        Spacer(Modifier.width(7.dp)); Text("Assign routes by area & pincode", fontWeight = FontWeight.Bold)
                    }
                    routeMessage?.let { Text(it, color = if (it.contains("could not", true)) Color(0xFFB23A32) else OBrand, fontSize = ProviderType.caption, modifier = Modifier.padding(top = 5.dp)) }
                    Text("Customers in the same locality and pincode stay together. Each active delivery person receives up to 100 meals per route batch.", color = OMuted, fontSize = ProviderType.caption, lineHeight = 14.sp, modifier = Modifier.padding(top = 5.dp))
                }
                if (riderGroups.isNotEmpty() || unassignedCount > 0) {
                    item {
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Share, null, tint = OBrand)
                                Spacer(Modifier.width(8.dp))
                                Column { Text("Share delivery lists", color = OInk, fontSize = 15.sp, fontWeight = FontWeight.Bold); Text("One private WhatsApp list per assigned rider", color = OMuted, fontSize = ProviderType.caption) }
                            }
                            riderGroups.forEach { (rider, customers) ->
                                val phone = rider.optString("delivery_person_phone")
                                val areas = customers.map { routeLabel(it.optJSONObject("address")) }.distinct()
                                HorizontalDivider(color = Color(0xFFE5ECE7))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(rider.optString("delivery_person"), color = OInk, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                        Text("${customers.size} meals · ${areas.size} areas · ${indianPhoneDisplay(phone)}", color = OMuted, fontSize = ProviderType.caption)
                                    }
                                    Button(
                                        onClick = { pendingShare = rider to customers },
                                        enabled = phone.isNotBlank() && !routeAssigning,
                                        contentPadding = PaddingValues(horizontal = 11.dp, vertical = 7.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF128C4A))
                                    ) { Icon(Icons.Outlined.Send, null, Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)); Text("WhatsApp", fontSize = ProviderType.caption) }
                                }
                            }
                            if (unassignedCount > 0) Text("$unassignedCount orders are not assigned and will not be shared.", color = Color(0xFFB35B00), fontSize = ProviderType.caption, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                grouped.forEach { (area, customers) ->
                    item { Text(area, color = OBrand, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 5.dp)) }
                    items(customers, key = { it.optString("meal_id") }) { customer -> CustomerOrderCard(customer) }
                }
            }
            if (!loading && isFinal && !preview && manifest.isEmpty()) item { OrderNotice("No ${slot.lowercase()} meals found for $selectedDate.", false) }
            item { Spacer(Modifier.height(5.dp)) }
        }
    }
}

@Composable
private fun OrderBusinessSummary(slot:String,metrics:JSONObject,commission:JSONObject){
    val active=metrics.optInt("active")
    val gross=metrics.optLong("gross_paise")
    val net=commission.optLong("provider_net_paise")
    val rate=commission.optDouble("rate_percent",Double.NaN)
    Surface(color=Color.White,shape=RoundedCornerShape(17.dp),shadowElevation=1.dp){
        Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(11.dp)){
            Row(verticalAlignment=Alignment.CenterVertically){
                Surface(color=OMist,shape=CircleShape){Icon(if(slot=="LUNCH")Icons.Outlined.WbSunny else Icons.Outlined.DarkMode,null,tint=OBrand,modifier=Modifier.padding(9.dp).size(19.dp))}
                Spacer(Modifier.width(9.dp));Column(Modifier.weight(1f)){Text("${slot.lowercase().replaceFirstChar(Char::uppercase)} order summary",color=OInk,fontSize=15.sp,fontWeight=FontWeight.Bold);Text("Meal value estimates · not your payout balance",color=OMuted,fontSize = ProviderType.caption)}
            }
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                OrderMetric("Orders",active.toString(),Icons.Outlined.Groups,Modifier.weight(1f))
                OrderMetric("Gross",formatOrderRupees(gross),Icons.Outlined.Payments,Modifier.weight(1f))
            }
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                OrderMetric("Estimated net",if (commission.has("provider_net_paise") && !commission.isNull("provider_net_paise")) formatOrderRupees(net) else "Unavailable",Icons.Outlined.AccountBalanceWallet,Modifier.weight(1f))
                OrderMetric("Delivery areas",metrics.optInt("areas").toString(),Icons.Outlined.LocationOn,Modifier.weight(1f))
            }
            Text("Zomeal commission: ${if (rate.isFinite()) formatRate(rate) + "%" else "unavailable"} · Earnings become payable according to your configured payout hold period.",color=OMuted,fontSize = ProviderType.caption,lineHeight=13.sp)
        }
    }
}

@Composable private fun OrderMetric(label:String,value:String,icon:androidx.compose.ui.graphics.vector.ImageVector,modifier:Modifier){
    Surface(modifier,color=OMist,shape=RoundedCornerShape(13.dp)){Column(Modifier.padding(11.dp)){Icon(icon,null,tint=OBrand,modifier=Modifier.size(18.dp));Spacer(Modifier.height(5.dp));Text(value,color=OInk,fontSize=15.sp,fontWeight=FontWeight.Bold);Text(label,color=OMuted,fontSize = ProviderType.caption)}}
}

@Composable private fun OrderPackageBreakdown(rows:List<JSONObject>){
    Surface(color=Color.White,shape=RoundedCornerShape(17.dp),shadowElevation=1.dp){Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){
        Text("Package-wise orders",color=OInk,fontSize=14.sp,fontWeight=FontWeight.Bold)
        Text("Lunch-only, dinner-only and combined-plan values are calculated separately.",color=OMuted,fontSize = ProviderType.caption)
        rows.forEachIndexed{index,row->
            if(index>0)HorizontalDivider(color=Color(0xFFE5ECE7))
            Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(row.optString("label"),color=OInk,fontSize = ProviderType.caption,fontWeight=FontWeight.SemiBold);Text("${row.optInt("customers")} customers · ${formatOrderRupees(row.optLong("average_value_paise"))}/meal",color=OMuted,fontSize = ProviderType.caption)};Text(formatOrderRupees(row.optLong("gross_paise")),color=OBrand,fontSize=12.sp,fontWeight=FontWeight.Bold)}
        }
    }}
}

@Composable private fun OrderPreparationSummary(rows:List<JSONObject>){
    Surface(color=Color.White,shape=RoundedCornerShape(17.dp),shadowElevation=1.dp){Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text("Main-course preparation",color=OInk,fontSize=14.sp,fontWeight=FontWeight.Bold)
        rows.forEach{row->Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).background(OMist).padding(10.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Outlined.RestaurantMenu,null,tint=OBrand,modifier=Modifier.size(17.dp));Spacer(Modifier.width(7.dp));Text(row.optString("name"),color=OInk,fontSize = ProviderType.caption,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f));Text("${row.optInt("count")} meals",color=OBrand,fontSize = ProviderType.caption,fontWeight=FontWeight.Bold)}}
    }}
}

@Composable
private fun CustomerOrderCard(customer: JSONObject) {
    val phone = maskedCustomerPhone(customer.optString("phone"))
    Surface(color = Color.White, shape = RoundedCornerShape(16.dp), shadowElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(11.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.size(39.dp).clip(CircleShape).background(OMist), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Person, null, tint = OBrand) }
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) { Text(customer.optString("customer_name").ifBlank { "Customer" }, color = OInk, fontSize = 14.sp, fontWeight = FontWeight.Bold); Text(phone, color = OMuted, fontSize = ProviderType.caption) }
                StatusBadge(customer.optString("status"))
            }
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.RestaurantMenu, null, tint = OBrand, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text("${customer.optString("meal_type").lowercase().replaceFirstChar(Char::uppercase)} · ${customer.optString("main_course").ifBlank { "Default main course" }}", color = OInk, fontSize = ProviderType.caption, fontWeight = FontWeight.SemiBold) }
            if (!isPreparationMeal(customer)) Text("Do not prepare or deliver", color = Color(0xFFB35B00), fontSize = ProviderType.caption, fontWeight = FontWeight.Bold)
            else Text("Delivery: ${customer.optString("delivery_person").ifBlank { "Not assigned" }}", color = if (customer.optString("delivery_person").isBlank()) Color(0xFFB35B00) else OBrand, fontSize = ProviderType.caption, fontWeight = FontWeight.SemiBold)
        }
    }
}

private fun formatOrderRupees(paise:Long):String="₹"+java.text.NumberFormat.getIntegerInstance(Locale("en","IN")).format(paise/100)
private fun formatRate(value:Double):String=if(value%1.0==0.0)value.toInt().toString() else String.format(Locale.US,"%.2f",value)

@Composable private fun StatusBadge(status: String) { Surface(color = OMist, shape = CircleShape) { Text(status.replace('_',' ').lowercase().replaceFirstChar(Char::uppercase), color = OBrand, fontSize = ProviderType.caption, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) } }

@Composable private fun SlotOrderButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) { Button(onClick, modifier.heightIn(min = 48.dp), enabled = enabled, shape = RoundedCornerShape(13.dp), colors = ButtonDefaults.buttonColors(containerColor = if(selected) OBrand else Color.White, contentColor = if(selected) Color.White else OInk)) { Icon(icon,null); Spacer(Modifier.width(6.dp)); Text(label,fontWeight=FontWeight.Bold) } }

@Composable private fun OrderNotice(text: String, error: Boolean) { Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if(error) Color(0xFFFFECEA) else Color(0xFFEAF5EC)).padding(13.dp)) { Icon(if(error) Icons.Outlined.ErrorOutline else Icons.Outlined.LockClock,null,tint=if(error) Color(0xFFB23A32) else OBrand); Spacer(Modifier.width(8.dp)); Text(text,color=if(error) Color(0xFF8D302A) else Color(0xFF345C43),fontSize = ProviderType.caption,lineHeight=16.sp) } }


private fun jsonObjects(array: JSONArray?): List<JSONObject> = if(array==null) emptyList() else (0 until array.length()).mapNotNull(array::optJSONObject)
private fun addressText(address: JSONObject?): String = if(address==null) "Address unavailable" else listOf(address.optString("house_number"),address.optString("address_line"),address.optString("locality"),address.optString("city"),address.optString("pincode")).filter{it.isNotBlank()}.joinToString(", ").ifBlank{address.toString()}
private fun routeLabel(address: JSONObject?): String = listOf(address?.optString("locality").orEmpty(), address?.optString("pincode").orEmpty()).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Other" }

private fun indianPhoneDisplay(raw: String): String {
    val digits = raw.filter(Char::isDigit).takeLast(10)
    return if (digits.length == 10) "+91 ${digits.take(5)} ${digits.takeLast(5)}" else raw
}

private fun whatsappPhone(raw: String): String {
    val digits = raw.filter(Char::isDigit)
    return if (digits.length == 10) "91$digits" else digits
}

private fun deliveryManifestText(slot: String, date: String, rider: JSONObject, customers: List<JSONObject>, preview: Boolean): String = buildString {
    appendLine("*Total meals to deliver: ${customers.size}*")
    if (preview) appendLine("*TEST / PREVIEW DATA*")
    appendLine("*Zomeal ${slot.lowercase().replaceFirstChar(Char::uppercase)} Delivery List*")
    appendLine("Date: ${date.ifBlank { "Today" }}")
    appendLine("Delivery partner: ${rider.optString("delivery_person")}")
    customers.forEachIndexed { index, customer ->
        val address = addressText(customer.optJSONObject("address"))
        appendLine()
        appendLine("*${index + 1}. ${customer.optString("customer_name").ifBlank { "Customer" }}*")
        appendLine("Phone: ${customer.optString("phone").ifBlank { "Unavailable" }}")
        appendLine("Address: $address")
        appendLine("Main course: ${customer.optString("main_course").ifBlank { "Default main course" }}")
    }
    appendLine()
    append("Customer data is confidential and must be used only for today's assigned deliveries.")
}

private fun shareOnWhatsApp(context: Context, riderPhone: String, text: String) {
    val direct = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/${whatsappPhone(riderPhone)}?text=${Uri.encode(text)}"))
    runCatching { context.startActivity(direct) }.onFailure {
        runCatching {
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }, "Share delivery list"))
        }
    }
}
