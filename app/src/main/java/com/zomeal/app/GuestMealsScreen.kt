package com.zomeal.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

@Composable
internal fun GuestMealsScreen(repository: SupabaseCustomerRepository, subscriptionId: String, onBack: () -> Unit) {
    var rows by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    val edits = remember { mutableStateMapOf<String,List<String>>() }
    var busy by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }
    fun reload() {
        repository.guestMenu(subscriptionId) { result, error ->
            busy = false
            message = error
            rows = result?.optJSONArray("items")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
        }
    }
    LaunchedEffect(subscriptionId) { reload() }
    val total = rows.sumOf { it.optLong("price_paise") * (edits[it.optString("meal_id")]?.size ?: 0) }
    val green = Color(0xFF087F43)
    Scaffold(containerColor = Color(0xFFF7FAF7), modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
        topBar = { Row(Modifier.fillMaxWidth().padding(12.dp)) {
            TextButton(onClick = onBack, enabled = !busy) { Text("Back") }
            Text("Guest meals", fontSize = 20.sp, modifier = Modifier.padding(12.dp))
        } },
        bottomBar = { Button(onClick = { confirm = true }, enabled = !busy && edits.isNotEmpty() && edits.values.all { values -> values.none { it.isBlank() } },
            modifier = Modifier.fillMaxWidth().padding(16.dp), colors = ButtonDefaults.buttonColors(containerColor = green)) { Text("Review guest meals") } }
    ) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("Up to 3 extra portions per lunch or dinner. Choose each guest’s dish. Delivery is to your plan’s address.", fontSize = 14.sp) }
            item { Text("Each guest meal costs your meal price + ₹10. Deducted daily, not now. Changes close at 8 AM for lunch and 6 PM for dinner (IST).", color = green, fontSize = 13.sp) }
            if(busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            message?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            if(!busy && rows.isEmpty() && message == null) item { Text("No upcoming meals are available in this plan.") }
            items(rows, key = { it.getString("meal_id") }) { row ->
                val id = row.getString("meal_id")
                val guests = row.getJSONArray("guests")
                val existing = (0 until guests.length()).map { guests.getJSONObject(it).optString("item_id") }
                val selected = edits[id] ?: existing
                val choices = row.getJSONArray("choices")
                var expanded by remember(id) { mutableStateOf(false) }
                Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${row.getString("date")} · ${row.getString("slot").lowercase().replaceFirstChar { it.uppercase() }}", fontSize = 16.sp)
                        Text("₹${"%.2f".format(row.getLong("price_paise") / 100.0)} per guest · includes ₹10 extra", color = green, fontSize = 13.sp)
                        TextButton(onClick = { expanded = !expanded }) { Text(if(expanded) "Hide choices" else "${selected.size} guest meals · View / edit") }
                        if(guests.length()>0) Text((0 until guests.length()).joinToString(" · ") { n -> "Guest ${guests.getJSONObject(n).optInt("number")}: ${guests.getJSONObject(n).optString("status").lowercase()}" }, fontSize = 12.sp)
                        if(expanded) {
                            if(!row.optBoolean("editable")) Text("Changes closed", fontSize = 13.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { (0..3).forEach { count ->
                                FilterChip(selected = selected.size == count, enabled = row.optBoolean("editable") && !busy,
                                    onClick = { edits[id] = List(count) { selected.getOrElse(it) { "" } } }, label = { Text("$count") })
                            } }
                            selected.forEachIndexed { index, chosen ->
                                var menu by remember(id,index) { mutableStateOf(false) }
                                val dish = (0 until choices.length()).map { choices.getJSONObject(it) }.firstOrNull { it.optString("id") == chosen }
                                Box {
                                    OutlinedButton(onClick = { menu = true }, enabled = row.optBoolean("editable") && !busy, modifier = Modifier.fillMaxWidth()) {
                                        Text("Guest ${index+1}: ${dish?.optString("name") ?: "Choose dish"}", fontSize = 13.sp)
                                    }
                                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                        (0 until choices.length()).forEach { n -> val option = choices.getJSONObject(n)
                                            DropdownMenuItem(text = { Text("${option.getString("name")} · ${option.getString("diet")}") }, onClick = {
                                                edits[id] = selected.toMutableList().apply { set(index, option.getString("id")) }; menu = false
                                            })
                                        }
                                    }
                                }
                            }
                            if(edits.containsKey(id)) TextButton(onClick = { edits.remove(id) }) { Text("Undo changes") }
                        }
                    }
                }
            }
        }
    }
    if(confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Confirm guest meals") },
        text = { Text("Total for edited dates: ₹${"%.2f".format(total / 100.0)}. Includes ₹10 extra per guest meal. Your wallet is charged daily, not upfront. Selecting 0 removes guest meals for that slot. Keep enough balance for your own and guest meals.") },
        confirmButton = { TextButton(onClick = {
            confirm = false; busy = true
            val selections = JSONArray().apply { edits.forEach { (id, dishes) -> put(JSONObject().put("meal_id",id).put("items",JSONArray(dishes)).put("expected_price_paise",rows.first { it.getString("meal_id")==id }.getLong("price_paise"))) } }
            repository.saveGuestMeals(subscriptionId,selections) { _, error ->
                busy = false
                if(error == null) { edits.clear(); reload() } else message = error
            }
        }) { Text("Confirm · pay daily") } }, dismissButton = { TextButton(onClick = { confirm = false }) { Text("Back") } })
}
