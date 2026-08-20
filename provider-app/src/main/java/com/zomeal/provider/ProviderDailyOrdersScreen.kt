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
fun ProviderDailyOrdersScreen(repository: SupabaseProviderRepository, onDashboard: () -> Unit) {
    val context = LocalContext.current
    val isoDate = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false } }
    val friendlyDate = remember { SimpleDateFormat("EEE, dd MMM yyyy", Locale.ENGLISH) }
    var selectedDate by rememberSaveable { mutableStateOf(isoDate.format(Date())) }
    var slot by remember { mutableStateOf("LUNCH") }
    var dashboard by remember { mutableStateOf<JSONObject?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf("ALL") }
    var routeAssigning by remember { mutableStateOf(false) }
    var routeMessage by remember { mutableStateOf<String?>(null) }
    var pendingShare by remember { mutableStateOf<Pair<JSONObject, List<JSONObject>>?>(null) }
    fun moveDate(days:Int){
        val calendar=Calendar.getInstance().apply{time=isoDate.parse(selectedDate)?:Date();add(Calendar.DAY_OF_MONTH,days)}
        selectedDate=isoDate.format(calendar.time)
    }
    fun openCalendar(){
        val calendar=Calendar.getInstance().apply{time=isoDate.parse(selectedDate)?:Date()}
        DatePickerDialog(context,{_,year,month,day->
            selectedDate=isoDate.format(Calendar.getInstance().apply{set(year,month,day,12,0,0);set(Calendar.MILLISECOND,0)}.time)
        },calendar.get(Calendar.YEAR),calendar.get(Calendar.MONTH),calendar.get(Calendar.DAY_OF_MONTH)).show()
    }
    fun load() {
        loading = true; error = null
        repository.loadDailyDashboard(slot,selectedDate) { result, message -> dashboard = result; error = message; loading = false }
    }
    LaunchedEffect(slot,selectedDate) { search = ""; statusFilter = "ALL"; load() }
    val isFinal = dashboard?.optBoolean("is_final") == true
    val preview = dashboard?.optBoolean("preview_mode") == true
    val unlockedForTesting = isFinal || preview
    var manifest by remember(dashboard) { mutableStateOf(jsonObjects(dashboard?.optJSONArray("manifest"))) }
    val visible = manifest.filter { customer ->
        val address = addressText(customer.optJSONObject("address"))
        val matchesSearch = search.isBlank() || listOf(customer.optString("customer_name"), customer.optString("phone"), customer.optString("main_course"), address).any { it.contains(search, true) }
        val matchesStatus = statusFilter == "ALL" || customer.optString("status") == statusFilter
        matchesSearch && matchesStatus
    }
    val grouped = visible.groupBy { customer ->
        val address = customer.optJSONObject("address")
        listOf(address?.optString("locality").orEmpty(), address?.optString("pincode").orEmpty()).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Other addresses" }
    }
    val riderGroups = manifest.filter { it.optString("delivery_person").isNotBlank() }
        .groupBy { it.optString("delivery_person_id").ifBlank { it.optString("delivery_person") } }
        .map { (_, customers) -> customers.first() to customers }
    val unassignedCount = manifest.count { it.optString("delivery_person").isBlank() }
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
                    shareOnWhatsApp(
                        context,
                        rider.optString("delivery_person_phone"),
                        deliveryManifestText(slot, dashboard?.optString("date").orEmpty(), rider, customers, preview)
                    )
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
                    Column(Modifier.weight(1f)) { Text("Daily orders & routes", color = OInk, fontSize = 20.sp, fontWeight = FontWeight.Bold); Text("Final customer preparation list", color = OMuted, fontSize = 11.sp) }
                    IconButton(onClick = { load() }) { Icon(Icons.Outlined.Refresh, "Refresh", tint = OBrand) }
                }
            }
        },
        bottomBar = { OrdersBottomBar(onDashboard) }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(15.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            item {
                Surface(color=Color.White,shape=RoundedCornerShape(16.dp),shadowElevation=1.dp){
                    Row(Modifier.fillMaxWidth().padding(9.dp),verticalAlignment=Alignment.CenterVertically){
                        IconButton(onClick={moveDate(-1)}){Icon(Icons.Outlined.ChevronLeft,"Previous date",tint=OBrand)}
                        OutlinedButton(onClick={openCalendar()},modifier=Modifier.weight(1f).height(48.dp),shape=RoundedCornerShape(13.dp)){
                            Icon(Icons.Outlined.CalendarMonth,null,Modifier.size(18.dp));Spacer(Modifier.width(7.dp))
                            Text(friendlyDate.format(isoDate.parse(selectedDate)?:Date()),fontWeight=FontWeight.Bold,fontSize=12.sp)
                        }
                        IconButton(onClick={moveDate(1)}){Icon(Icons.Outlined.ChevronRight,"Next date",tint=OBrand)}
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    SlotOrderButton("Lunch", Icons.Outlined.WbSunny, slot == "LUNCH", Modifier.weight(1f)) { slot = "LUNCH" }
                    SlotOrderButton("Dinner", Icons.Outlined.DarkMode, slot == "DINNER", Modifier.weight(1f)) { slot = "DINNER" }
                }
            }
            if (loading) item { Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = OBrand) } }
            error?.let { item { OrderNotice(it, true) } }
            if (!loading && !isFinal && !preview) item { OrderNotice("Customer details for $selectedDate will unlock after ${if (slot == "LUNCH") "7:00 AM" else "4:00 PM"} IST. Until then, choices may still change.", false) }
            if (!loading && preview) item { OrderNotice("Preview mode: sample records stay on this device and do not represent real customers. Preview access does not bypass the real customer-data cutoff.", false) }
            if(!loading&&dashboard!=null){
                item{OrderBusinessSummary(slot,metrics,commission)}
                if(packageBreakdown.isNotEmpty())item{OrderPackageBreakdown(packageBreakdown)}
                if(choices.isNotEmpty())item{OrderPreparationSummary(choices)}
            }
            if (!loading && preview && manifest.isEmpty()) item {
                OutlinedButton(onClick = { manifest = sampleOrders(slot) }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Icon(Icons.Outlined.Science, null); Spacer(Modifier.width(7.dp)); Text("Load sample customer manifest") }
            }
            if (!loading && unlockedForTesting && manifest.isNotEmpty()) {
                item {
                    OutlinedTextField(
                        value = search, onValueChange = { search = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                        leadingIcon = { Icon(Icons.Outlined.Search, null) }, placeholder = { Text("Search name, phone, address or meal") }, shape = RoundedCornerShape(14.dp)
                    )
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("ALL" to "All", "SCHEDULED" to "Scheduled", "OUT_FOR_DELIVERY" to "On way").forEach { (code,label) ->
                            FilterChip(statusFilter == code, { statusFilter = code }, { Text(label, fontSize = 10.sp) }, modifier = Modifier.weight(1f))
                        }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(Color.White).padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Groups, null, tint = OBrand); Spacer(Modifier.width(9.dp)); Column(Modifier.weight(1f)) { Text("${visible.size} meals", color = OInk, fontSize = 18.sp, fontWeight = FontWeight.Bold); Text("${grouped.size} route areas", color = OMuted, fontSize = 11.sp) }
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
                        modifier = Modifier.fillMaxWidth().height(49.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = OBrand)
                    ) {
                        if (routeAssigning) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                        else Icon(Icons.Outlined.Route, null)
                        Spacer(Modifier.width(7.dp)); Text("Assign routes by area & pincode", fontWeight = FontWeight.Bold)
                    }
                    routeMessage?.let { Text(it, color = if (it.contains("could not", true)) Color(0xFFB23A32) else OBrand, fontSize = 10.sp, modifier = Modifier.padding(top = 5.dp)) }
                    Text("Customers in the same locality and pincode stay together. Each active delivery person receives up to 100 meals per route batch.", color = OMuted, fontSize = 10.sp, lineHeight = 14.sp, modifier = Modifier.padding(top = 5.dp))
                }
                if (riderGroups.isNotEmpty() || unassignedCount > 0) {
                    item {
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Share, null, tint = OBrand)
                                Spacer(Modifier.width(8.dp))
                                Column { Text("Share delivery lists", color = OInk, fontSize = 15.sp, fontWeight = FontWeight.Bold); Text("One private WhatsApp list per assigned rider", color = OMuted, fontSize = 10.sp) }
                            }
                            riderGroups.forEach { (rider, customers) ->
                                val phone = rider.optString("delivery_person_phone")
                                val areas = customers.map { routeLabel(it.optJSONObject("address")) }.distinct()
                                HorizontalDivider(color = Color(0xFFE5ECE7))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(rider.optString("delivery_person"), color = OInk, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                        Text("${customers.size} meals · ${areas.size} areas · ${indianPhoneDisplay(phone)}", color = OMuted, fontSize = 10.sp)
                                    }
                                    Button(
                                        onClick = { pendingShare = rider to customers },
                                        enabled = phone.isNotBlank(),
                                        contentPadding = PaddingValues(horizontal = 11.dp, vertical = 7.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF128C4A))
                                    ) { Icon(Icons.Outlined.Send, null, Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)); Text("WhatsApp", fontSize = 10.sp) }
                                }
                            }
                            if (unassignedCount > 0) Text("$unassignedCount orders are not assigned and will not be shared.", color = Color(0xFFB35B00), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                grouped.forEach { (area, customers) ->
                    item { Text(area, color = OBrand, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 5.dp)) }
                    items(customers, key = { it.optString("meal_id") }) { customer -> CustomerOrderCard(customer) }
                }
            }
            if (!loading && isFinal && !preview && manifest.isEmpty()) item { OrderNotice("No active ${slot.lowercase()} customers were found for today.", false) }
            item { Spacer(Modifier.height(5.dp)) }
        }
    }
}

@Composable
private fun OrderBusinessSummary(slot:String,metrics:JSONObject,commission:JSONObject){
    val active=metrics.optInt("active")
    val gross=metrics.optLong("gross_paise")
    val net=commission.optLong("provider_net_paise",gross)
    val rate=commission.optDouble("rate_percent",14.0)
    Surface(color=Color.White,shape=RoundedCornerShape(17.dp),shadowElevation=1.dp){
        Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(11.dp)){
            Row(verticalAlignment=Alignment.CenterVertically){
                Surface(color=OMist,shape=CircleShape){Icon(if(slot=="LUNCH")Icons.Outlined.WbSunny else Icons.Outlined.DarkMode,null,tint=OBrand,modifier=Modifier.padding(9.dp).size(19.dp))}
                Spacer(Modifier.width(9.dp));Column(Modifier.weight(1f)){Text("${slot.lowercase().replaceFirstChar(Char::uppercase)} order summary",color=OInk,fontSize=15.sp,fontWeight=FontWeight.Bold);Text("Final preparation and commercial totals",color=OMuted,fontSize=10.sp)}
            }
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                OrderMetric("Orders",active.toString(),Icons.Outlined.Groups,Modifier.weight(1f))
                OrderMetric("Gross",formatOrderRupees(gross),Icons.Outlined.Payments,Modifier.weight(1f))
            }
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                OrderMetric("Your earnings",formatOrderRupees(net),Icons.Outlined.AccountBalanceWallet,Modifier.weight(1f))
                OrderMetric("Delivery areas",metrics.optInt("areas").toString(),Icons.Outlined.LocationOn,Modifier.weight(1f))
            }
            Text("Zomeal commission: ${formatRate(rate)}% · Earnings become payable according to your configured payout hold period.",color=OMuted,fontSize=9.sp,lineHeight=13.sp)
        }
    }
}

@Composable private fun OrderMetric(label:String,value:String,icon:androidx.compose.ui.graphics.vector.ImageVector,modifier:Modifier){
    Surface(modifier,color=OMist,shape=RoundedCornerShape(13.dp)){Column(Modifier.padding(11.dp)){Icon(icon,null,tint=OBrand,modifier=Modifier.size(18.dp));Spacer(Modifier.height(5.dp));Text(value,color=OInk,fontSize=15.sp,fontWeight=FontWeight.Bold);Text(label,color=OMuted,fontSize=9.sp)}}
}

@Composable private fun OrderPackageBreakdown(rows:List<JSONObject>){
    Surface(color=Color.White,shape=RoundedCornerShape(17.dp),shadowElevation=1.dp){Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){
        Text("Package-wise orders",color=OInk,fontSize=14.sp,fontWeight=FontWeight.Bold)
        Text("Lunch-only, dinner-only and combined-plan values are calculated separately.",color=OMuted,fontSize=9.sp)
        rows.forEachIndexed{index,row->
            if(index>0)HorizontalDivider(color=Color(0xFFE5ECE7))
            Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(row.optString("label"),color=OInk,fontSize=11.sp,fontWeight=FontWeight.SemiBold);Text("${row.optInt("customers")} customers · ${formatOrderRupees(row.optLong("average_value_paise"))}/meal",color=OMuted,fontSize=9.sp)};Text(formatOrderRupees(row.optLong("gross_paise")),color=OBrand,fontSize=12.sp,fontWeight=FontWeight.Bold)}
        }
    }}
}

@Composable private fun OrderPreparationSummary(rows:List<JSONObject>){
    Surface(color=Color.White,shape=RoundedCornerShape(17.dp),shadowElevation=1.dp){Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text("Main-course preparation",color=OInk,fontSize=14.sp,fontWeight=FontWeight.Bold)
        rows.forEach{row->Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).background(OMist).padding(10.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Outlined.RestaurantMenu,null,tint=OBrand,modifier=Modifier.size(17.dp));Spacer(Modifier.width(7.dp));Text(row.optString("name"),color=OInk,fontSize=10.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f));Text("${row.optInt("count")} meals",color=OBrand,fontSize=10.sp,fontWeight=FontWeight.Bold)}}
    }}
}

@Composable
private fun CustomerOrderCard(customer: JSONObject) {
    val context = LocalContext.current
    val address = addressText(customer.optJSONObject("address"))
    val phone = customer.optString("phone")
    Surface(color = Color.White, shape = RoundedCornerShape(16.dp), shadowElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.size(39.dp).clip(CircleShape).background(OMist), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Person, null, tint = OBrand) }
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) { Text(customer.optString("customer_name").ifBlank { "Customer" }, color = OInk, fontSize = 14.sp, fontWeight = FontWeight.Bold); Text(phone, color = OMuted, fontSize = 11.sp) }
                StatusBadge(customer.optString("status"))
            }
            Row(verticalAlignment = Alignment.Top) { Icon(Icons.Outlined.LocationOn, null, tint = OBrand, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text(address, color = OMuted, fontSize = 11.sp, lineHeight = 15.sp) }
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.RestaurantMenu, null, tint = OBrand, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text("${customer.optString("meal_type").lowercase().replaceFirstChar(Char::uppercase)} · ${customer.optString("main_course").ifBlank { "Default main course" }}", color = OInk, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
            Row(verticalAlignment=Alignment.CenterVertically){Icon(Icons.Outlined.Inventory2,null,tint=OBrand,modifier=Modifier.size(17.dp));Spacer(Modifier.width(6.dp));Text("${customer.optString("package_name").ifBlank{"Meal package"}} · ${formatOrderRupees(customer.optLong("meal_value_paise"))}",color=OMuted,fontSize=10.sp)}
            Text("Delivery: ${customer.optString("delivery_person").ifBlank { "Not assigned" }}", color = if (customer.optString("delivery_person").isBlank()) Color(0xFFB35B00) else OBrand, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { if (phone.isNotBlank()) context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 6.dp)) { Icon(Icons.Outlined.Call, null, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text("Call", fontSize = 11.sp) }
                OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(address)}"))) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 6.dp)) { Icon(Icons.Outlined.Map, null, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text("Map", fontSize = 11.sp) }
            }
        }
    }
}

private fun formatOrderRupees(paise:Long):String="₹"+java.text.NumberFormat.getIntegerInstance(Locale("en","IN")).format(paise/100)
private fun formatRate(value:Double):String=if(value%1.0==0.0)value.toInt().toString() else String.format(Locale.US,"%.2f",value)

@Composable private fun StatusBadge(status: String) { Surface(color = OMist, shape = CircleShape) { Text(status.replace('_',' ').lowercase().replaceFirstChar(Char::uppercase), color = OBrand, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) } }

@Composable private fun SlotOrderButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, modifier: Modifier, onClick: () -> Unit) { Button(onClick, modifier.height(48.dp), shape = RoundedCornerShape(13.dp), colors = ButtonDefaults.buttonColors(containerColor = if(selected) OBrand else Color.White, contentColor = if(selected) Color.White else OInk)) { Icon(icon,null); Spacer(Modifier.width(6.dp)); Text(label,fontWeight=FontWeight.Bold) } }

@Composable private fun OrderNotice(text: String, error: Boolean) { Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if(error) Color(0xFFFFECEA) else Color(0xFFEAF5EC)).padding(13.dp)) { Icon(if(error) Icons.Outlined.ErrorOutline else Icons.Outlined.LockClock,null,tint=if(error) Color(0xFFB23A32) else OBrand); Spacer(Modifier.width(8.dp)); Text(text,color=if(error) Color(0xFF8D302A) else Color(0xFF345C43),fontSize=11.sp,lineHeight=16.sp) } }

@Composable private fun OrdersBottomBar(onDashboard: () -> Unit) { NavigationBar(containerColor=Color.White,modifier=Modifier.height(64.dp)) { NavigationBarItem(false,onDashboard,{Icon(Icons.Outlined.Dashboard,null)},label={Text("Dashboard",fontSize=9.sp)}); NavigationBarItem(true,{}, {Icon(Icons.Outlined.ReceiptLong,null)},label={Text("Orders",fontSize=9.sp)},colors=NavigationBarItemDefaults.colors(selectedIconColor=OBrand,selectedTextColor=OBrand,indicatorColor=OMist)); NavigationBarItem(false,{}, {Icon(Icons.Outlined.Inventory,null)},label={Text("Capacity",fontSize=9.sp)}); NavigationBarItem(false,{}, {Icon(Icons.Outlined.AccountCircle,null)},label={Text("Profile",fontSize=9.sp)}) } }

private fun jsonObjects(array: JSONArray?): List<JSONObject> = if(array==null) emptyList() else (0 until array.length()).mapNotNull(array::optJSONObject)
private fun addressText(address: JSONObject?): String = if(address==null) "Address unavailable" else listOf(address.optString("house_number"),address.optString("address_line"),address.optString("locality"),address.optString("city"),address.optString("pincode")).filter{it.isNotBlank()}.joinToString(", ").ifBlank{address.toString()}
private fun sampleOrders(slot: String): List<JSONObject> = listOf(
    JSONObject().put("meal_id","sample-1").put("customer_name","Ananya Das").put("phone","9876543210").put("meal_type",slot).put("main_course","Paneer Butter Masala").put("status","SCHEDULED").put("delivery_person_id","sample-rider-1").put("delivery_person","Ashu").put("delivery_person_phone","7205586281").put("address",JSONObject().put("house_number","Plot 123").put("address_line","Near Jagamara Square").put("locality","Khandagiri").put("city","Bhubaneswar").put("pincode","751030")),
    JSONObject().put("meal_id","sample-2").put("customer_name","Rahul Nayak").put("phone","9123456780").put("meal_type",slot).put("main_course","Seasonal Mix Veg").put("status","SCHEDULED").put("delivery_person_id","sample-rider-2").put("delivery_person","Rakesh").put("delivery_person_phone","9876501234").put("address",JSONObject().put("house_number","House 42").put("address_line","KIIT Road").put("locality","Patia").put("city","Bhubaneswar").put("pincode","751024")),
    JSONObject().put("meal_id","sample-3").put("customer_name","Priya Sahu").put("phone","9988776655").put("meal_type",slot).put("main_course","Dal Tadka").put("status","OUT_FOR_DELIVERY").put("delivery_person_id","sample-rider-1").put("delivery_person","Ashu").put("delivery_person_phone","7205586281").put("address",JSONObject().put("house_number","Flat 3B").put("address_line","Arya Village").put("locality","Khandagiri").put("city","Bhubaneswar").put("pincode","751030"))
)

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
    if (preview) appendLine("*TEST / PREVIEW DATA*")
    appendLine("*Zomeal ${slot.lowercase().replaceFirstChar(Char::uppercase)} Delivery List*")
    appendLine("Date: ${date.ifBlank { "Today" }}")
    appendLine("Delivery partner: ${rider.optString("delivery_person")}")
    appendLine("Total meals: ${customers.size}")
    appendLine("Routes: ${customers.map { routeLabel(it.optJSONObject("address")) }.distinct().joinToString(", ")}")
    customers.forEachIndexed { index, customer ->
        val address = addressText(customer.optJSONObject("address"))
        appendLine()
        appendLine("*${index + 1}. ${customer.optString("customer_name").ifBlank { "Customer" }}*")
        appendLine("Call: ${indianPhoneDisplay(customer.optString("phone"))}")
        appendLine("Address: $address")
        appendLine("Meal: ${customer.optString("meal_type").lowercase().replaceFirstChar(Char::uppercase)}")
        appendLine("Main course: ${customer.optString("main_course").ifBlank { "Default main course" }}")
        appendLine("Status: ${customer.optString("status").replace('_', ' ').lowercase().replaceFirstChar(Char::uppercase)}")
        appendLine("Map: https://maps.google.com/?q=${Uri.encode(address)}")
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
