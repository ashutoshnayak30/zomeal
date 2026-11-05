package com.zomeal.provider

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

@Composable
internal fun KitchenProgress(data: JSONObject, session: String, repository: SupabaseProviderRepository, onRefresh: () -> Unit) {
    val metrics = data.optJSONObject("metrics") ?: return
    val active = metrics.optInt("active")
    if (active <= 0) return
    val date = data.optString("date")
    var pending by remember(date, session) { mutableStateOf<String?>(null) }
    var busy by remember(date, session) { mutableStateOf(false) }
    var error by remember(date, session) { mutableStateOf<String?>(null) }
    val requests = remember { OperationsRequestGate() }
    DisposableEffect(date, session) { onDispose { requests.invalidate() } }
    val steps = listOf("PREPARING" to "Food is cooking", "PACKING" to "Packaging started", "OUT_FOR_DELIVERY" to "Out for delivery")
    val final = data.optBoolean("is_final")
    val preparing = metrics.optInt("preparing")
    val packing = metrics.optInt("packing") + metrics.optInt("ready")
    val dispatched = metrics.optInt("out_for_delivery") + metrics.optInt("delivered")
    val scheduled = (active - preparing - packing - dispatched).coerceAtLeast(0)
    pending?.let { code ->
        AlertDialog(onDismissRequest = { if (!busy) pending = null }, title = { Text(steps.first { it.first == code }.second + "?") },
            text = { Text("Update eligible $session meals for $date. Paused, cancelled and delivered meals stay unchanged." + if (code == "OUT_FOR_DELIVERY") " Confirm the meals have been handed to their assigned riders." else "") },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                busy = true; error = null
                val request = requests.next()
                repository.advanceKitchenStage(session.uppercase(), date, code) { result ->
                    if (requests.accepts(request)) {
                        busy = false; pending = null
                        if (result.success) onRefresh() else error = result.message ?: "Could not update stage. Retry."
                    }
                }
            }) { Text(if (busy) "Saving…" else "Confirm") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { pending = null }) { Text("Cancel") } })
    }
    Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp), color = Color.White, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Kitchen progress", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            steps.forEachIndexed { index, (code, title) ->
                val count = when(code) { "PREPARING" -> preparing; "PACKING" -> packing; else -> metrics.optInt("out_for_delivery") }
                val allowed = when(code) {
                    "PREPARING" -> scheduled > 0
                    "PACKING" -> preparing > 0 && scheduled == 0
                    else -> packing > 0 && scheduled == 0 && preparing == 0 && metrics.optInt("unassigned_delivery") == 0
                }
                OutlinedButton(onClick = { pending = code }, enabled = final && allowed && !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) {
                    Text("${index + 1}", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(10.dp))
                    Text(title, Modifier.weight(1f))
                    Text(count.toString(), fontWeight = FontWeight.Bold)
                }
            }
            if (!final) Text("Available after the meal cutoff", fontSize = 12.sp)
            if (metrics.optInt("unassigned_delivery") > 0) Text("Assign riders in Orders before dispatch", color = Color(0xFF936015), fontSize = 12.sp)
            error?.let { Text(it, color = Color(0xFFB3261E), fontSize = 12.sp) }
        }
    }
}
