package com.zomeal.provider

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.util.Locale

/** Isolated presentation data. Never calls delivery, wallet or earnings mutations. */
@Composable
internal fun ProviderDemoScreen(repository: SupabaseProviderRepository, onBack: () -> Unit) {
    var data by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var slot by remember { mutableStateOf("Lunch") }
    var scenario by remember { mutableStateOf<Int?>(null) }
    var filter by remember { mutableStateOf("Active") }
    val stages = remember { mutableStateMapOf<String, Int>() }
    val gate = remember { OperationsRequestGate() }
    DisposableEffect(Unit) { onDispose { gate.invalidate() } }
    LaunchedEffect(Unit) {
        while(true) {
            val request = gate.next()
            repository.loadDemoPreview { result, failure ->
                if(gate.accepts(request)) { data = result; error = failure }
            }
            kotlinx.coroutines.delay(30_000)
        }
    }
    BackHandler(onBack = onBack)
    val green = Color(0xFF087F43)
    val configured = data?.optJSONArray("customers")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
    val customers = remember(configured.size, scenario) {
        if (scenario == null) configured else (1..scenario!!).map { n ->
            JSONObject().put("id", n).put("name", "Sample customer ${n.toString().padStart(3, '0')}")
                .put("address", "Demo building · Room $n").put("diet", if(n % 3 == 0) "NON_VEG" else "VEG")
        }
    }
    val paused = customers.size / 10
    val cancelled = customers.size / 20
    fun state(index: Int) = when { index < paused -> "Paused"; index < paused + cancelled -> "Cancelled"; else -> "Active" }
    val activeCustomers = customers.drop(paused + cancelled)
    val visibleCustomers = customers.filterIndexed { index, _ -> filter == "All" || state(index) == filter }
    val enabled = data?.optBoolean("enabled") == true && error == null
    Scaffold(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), containerColor = Color(0xFFF7FAF7),
        topBar = { Column(Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(green, Color(0xFF4AA43C)))).padding(16.dp)) {
            TextButton(onClick = onBack) { Text("‹ Back to live app", color = Color.White) }
            Text("$slot preparation", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text(data?.optString("provider_name").orEmpty(), color = Color.White, fontSize = 16.sp)
            Text("Sample day · demo only", color = Color.White, fontSize = 12.sp)
        } }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("Practice only · no real customers, deliveries, charges or earnings. Changes here reset when you leave.", color = green, fontSize = 13.sp) }
            if(!enabled) item { Text(error ?: if(data == null) "Loading demo…" else "This demo is disabled or expired. Ask your Zomeal administrator to enable it.") }
            else {
                item {
                    Text("Preview customer count", fontWeight = FontWeight.Bold)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf<Int?>(null, 100, 200, 500).forEach { n ->
                            FilterChip(selected = scenario == n, onClick = { scenario = n; stages.clear() }, label = { Text(n?.toString() ?: "Admin setup") })
                        }
                    }
                    Text("${customers.size} sample customers · 10% paused · 5% cancelled (rounded down). Preview changes are not saved.", fontSize = 12.sp)
                }
                item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { listOf("Lunch","Dinner").forEach { s -> FilterChip(selected = slot == s, onClick = { slot = s }, label = { Text(s) }) } } }
                item {
                    Surface(color = Color.White, shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Today's $slot", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Text("Sample preparation counts", color = green, fontSize = 12.sp)
                            Surface(color = green, shape = RoundedCornerShape(16.dp)) {
                                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("TOTAL MEALS", color = Color.White, fontSize = 12.sp)
                                        Text("${activeCustomers.size}", color = Color.White, fontSize = 48.sp, fontWeight = FontWeight.ExtraBold)
                                        Text("Today's full preparation count", color = Color.White, fontSize = 12.sp)
                                    }
                                    Icon(Icons.Outlined.Restaurant, null, Modifier.size(42.dp), tint = Color.White)
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                DemoCount("Paused", paused, Color(0xFFFFF3DC), Color(0xFF936015), Modifier.weight(1f))
                                DemoCount("Cancelled", cancelled, Color(0xFFFDEEEB), Color(0xFFAD493C), Modifier.weight(1f))
                            }
                            Text("Paused & cancelled meals excluded. Delivered meals remain in the total.", fontSize = 11.sp)
                            HorizontalDivider()
                            Text("What to cook", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                            listOf("VEG" to "Rice & paneer", "NON_VEG" to "Rice & chicken").forEach { (diet, dish) ->
                                Row(Modifier.fillMaxWidth().background(Color(0xFFF2F7F1), RoundedCornerShape(14.dp)).padding(12.dp)) {
                                    Text("Sample $dish", Modifier.weight(1f), fontSize = 14.sp)
                                    Text("${activeCustomers.count { it.optString("diet") == diet }} portions", color = green, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
                item {
                    val price = data?.optLong(if(slot == "Lunch") "lunch_paise" else "dinner_paise") ?: 0L
                    val packed = activeCustomers.count { (stages["$slot-${it.optInt("id")}"] ?: 0) >= 2 }
                    val delivered = activeCustomers.count { stages["$slot-${it.optInt("id")}"] == 3 }
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFE6F4EA))) {
                        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Preparation & delivery · $slot", color = green, fontWeight = FontWeight.Bold)
                            Text("$packed packed · $delivered delivered", fontSize = 14.sp)
                            LinearProgressIndicator(progress = { if(activeCustomers.isEmpty()) 0f else delivered.toFloat()/activeCustomers.size }, modifier = Modifier.fillMaxWidth(), color = green)
                            Text("Illustrative meal value: ₹${String.format(Locale.ENGLISH,"%.2f",price*activeCustomers.size/100.0)}", fontSize = 14.sp)
                            Text("Demo price ₹${String.format(Locale.ENGLISH,"%.2f",price/100.0)} per meal. Not live revenue or net earnings; commission is not calculated here.", fontSize = 12.sp)
                            TextButton(onClick = { stages.clear() }) { Text("Reset practice progress") }
                        }
                    }
                }
                item {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Active", "Paused", "Cancelled", "All").forEach { choice ->
                            FilterChip(selected = filter == choice, onClick = { filter = choice }, label = { Text(choice) })
                        }
                    }
                    Text("${visibleCustomers.size} sample customers", fontSize = 12.sp)
                }
                items(visibleCustomers, key = { it.optInt("id") }) { customer ->
                    val mealState = state(customers.indexOf(customer))
                    val key = "$slot-${customer.optInt("id")}"; val stage = stages[key] ?: 0
                    val statuses = listOf("Scheduled","Preparing","Packed","Delivered")
                    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(customer.optString("name"), fontWeight = FontWeight.Bold)
                            Text(customer.optString("address"), fontSize = 12.sp)
                            Text(if(customer.optString("diet") == "VEG") "Sample veg meal · rice & paneer" else "Sample non-veg meal · rice & chicken", fontSize = 13.sp)
                            Text(if(mealState == "Active") statuses[stage] else mealState, color = green, fontSize = 13.sp)
                            if(mealState == "Active") Button(onClick = { stages[key] = (stage+1).coerceAtMost(3) }, enabled = stage<3, colors = ButtonDefaults.buttonColors(containerColor = green)) {
                                Text(listOf("Practice preparing","Practice packing","Practice delivery","Completed")[stage])
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DemoCount(label: String, count: Int, background: Color, ink: Color, modifier: Modifier) {
    Surface(modifier, color = background, shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(count.toString(), color = ink, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text(label, color = ink, fontSize = 14.sp)
        }
    }
}
