package com.zomeal.provider

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONObject

@Composable
internal fun ProviderBusinessReviewPanel(repository: SupabaseProviderRepository, onViewApproved: () -> Unit) {
    var status by remember { mutableStateOf<JSONObject?>(null) }
    var loading by remember { mutableStateOf(true) }
    var approvedAreas by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    val requests = remember { OperationsRequestGate() }
    DisposableEffect(Unit) { onDispose { requests.invalidate() } }
    LaunchedEffect(refresh) {
        val request = requests.next()
        loading = true; approvedAreas = null
        repository.loadApprovedServiceAreas { result, _ ->
            if (requests.accepts(request)) {
                val rows = result?.optJSONArray("servicePincodes")
                approvedAreas = if (rows == null) "Could not load approved areas — tap Refresh." else (0 until rows.length()).mapNotNull { rows.optJSONObject(it)?.optString("value")?.takeIf { pin -> pin.isNotBlank() } }.distinct().joinToString(" · ").ifBlank { "No approved service areas" }
            }
        }
        repository.loadApplicationStatus { result ->
            if (requests.accepts(request)) { status = result; loading = false }
        }
    }
    Surface(color = Color.White, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Published listing & review status", fontWeight = FontWeight.Bold, fontSize = ProviderType.section)
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            else if (status == null || status?.optString("provider_id").isNullOrBlank()) {
                Text("Review status could not be loaded. Your draft has not been changed.", fontSize = ProviderType.caption)
            } else {
                Text("Listing: ${status?.optString("status").orEmpty().replace('_', ' ')}", fontWeight = FontWeight.Bold, fontSize = ProviderType.body)
                val history = status?.optJSONArray("change_requests")
                if (history == null || history.length() == 0) Text("No submitted change requests. Saving a draft does not submit it for approval.", fontSize = ProviderType.caption)
                else for (index in 0 until minOf(history.length(), 5)) {
                    val entry = history.optJSONObject(index) ?: continue
                    val state = entry.optString("status")
                    Text("${entry.optString("type").replace('_', ' ')} · ${state.replace('_', ' ')}", fontWeight = FontWeight.SemiBold, fontSize = ProviderType.body)
                    val date = entry.optString("requested_at").take(10)
                    if (date.isNotBlank()) Text("Submitted $date", fontSize = ProviderType.caption)
                    entry.optString("reason").takeUnless { it.isBlank() || it == "null" }?.let {
                        Text("Admin note: $it", fontSize = ProviderType.caption, color = if (state == "REJECTED") Color(0xFFB3261E) else Color(0xFF68736D))
                    }
                    HorizontalDivider()
                }
            }
            Text("Approved service areas: ${approvedAreas ?: "Loading…"}", fontSize = ProviderType.caption, fontWeight = FontWeight.SemiBold)
            Text("The fields below are your editing workspace, not proof of approval. New service areas, prices, menus and photos need admin review.", fontSize = ProviderType.caption)
            OutlinedButton(onClick = onViewApproved, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("View approved customer-facing data") }
            TextButton(onClick = { refresh++ }, enabled = !loading) { Text("Refresh review status") }
        }
    }
}
