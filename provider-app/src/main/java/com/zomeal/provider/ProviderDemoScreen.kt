package com.zomeal.provider

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
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
    val customers = data?.optJSONArray("customers")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
    val enabled = data?.optBoolean("enabled") == true && error == null
    Scaffold(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), containerColor = Color(0xFFF7FAF7),
        topBar = { Row(Modifier.fillMaxWidth().padding(12.dp)) { TextButton(onClick = onBack) { Text("Back to live app") }; Text("Kitchen demo", Modifier.padding(12.dp), fontSize = 18.sp, fontWeight = FontWeight.Bold) } }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("Practice only · no real customers, deliveries, charges or earnings. Changes here reset when you leave.", color = green, fontSize = 13.sp) }
            if(!enabled) item { Text(error ?: if(data == null) "Loading demo…" else "This demo is disabled or expired. Ask your Zomeal administrator to enable it.") }
            else {
                item { Text(data?.optString("provider_name").orEmpty(), fontWeight = FontWeight.Bold, fontSize = 20.sp) }
                item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { listOf("Lunch","Dinner").forEach { s -> FilterChip(selected = slot == s, onClick = { slot = s }, label = { Text(s) }) } } }
                item {
                    val price = data?.optLong(if(slot == "Lunch") "lunch_paise" else "dinner_paise") ?: 0L
                    val packed = customers.count { (stages["$slot-${it.optInt("id")}"] ?: 0) >= 2 }
                    val delivered = customers.count { stages["$slot-${it.optInt("id")}"] == 3 }
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFE6F4EA))) {
                        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${customers.size} sample meals · $slot", color = green, fontWeight = FontWeight.Bold)
                            Text("$packed packed · $delivered delivered", fontSize = 14.sp)
                            LinearProgressIndicator(progress = { if(customers.isEmpty()) 0f else delivered.toFloat()/customers.size }, modifier = Modifier.fillMaxWidth(), color = green)
                            Text("Illustrative meal value: ₹${String.format(Locale.ENGLISH,"%.2f",price*customers.size/100.0)}", fontSize = 14.sp)
                            Text("Demo price ₹${String.format(Locale.ENGLISH,"%.2f",price/100.0)} per meal. Not live revenue or net earnings; commission is not calculated here.", fontSize = 12.sp)
                            TextButton(onClick = { stages.clear() }) { Text("Reset practice progress") }
                        }
                    }
                }
                items(customers, key = { it.optInt("id") }) { customer ->
                    val key = "$slot-${customer.optInt("id")}"; val stage = stages[key] ?: 0
                    val statuses = listOf("Scheduled","Preparing","Packed","Delivered")
                    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(customer.optString("name"), fontWeight = FontWeight.Bold)
                            Text(customer.optString("address"), fontSize = 12.sp)
                            Text(if(customer.optString("diet") == "VEG") "Sample veg meal · rice & paneer" else "Sample non-veg meal · rice & chicken", fontSize = 13.sp)
                            Text(statuses[stage], color = green, fontSize = 13.sp)
                            Button(onClick = { stages[key] = (stage+1).coerceAtMost(3) }, enabled = stage<3, colors = ButtonDefaults.buttonColors(containerColor = green)) {
                                Text(listOf("Practice preparing","Practice packing","Practice delivery","Completed")[stage])
                            }
                        }
                    }
                }
            }
        }
    }
}
