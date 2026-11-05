package com.zomeal.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray

@Composable
internal fun MenuUpdateNotice(repository: SupabaseCustomerRepository) {
    var updates by remember { mutableStateOf(JSONArray()) }
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(repository) { repository.menuUpdateHistory { data,_ -> updates=data?.optJSONArray("items")?:JSONArray() } }
    if(updates.length()==0)return
    Surface(Modifier.fillMaxWidth().padding(horizontal=18.dp),color=Color(0xFFEAF5ED),shape=RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("Your menu was updated",color=Color(0xFF087F43),fontWeight=FontWeight.Bold)
            Text("Your kitchen updated some dishes. You don't need to set up the week again.",fontSize=13.sp)
            TextButton(onClick={expanded=true}) { Text("View menu changes") }
        }
    }
    if(expanded) AlertDialog(onDismissRequest={expanded=false},title={Text("Menu changes")},text={
        Column(Modifier.heightIn(max=440.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("Changes apply only to eligible upcoming meals. Past deliveries and charges stay unchanged.",fontSize=12.sp)
            for(i in 0 until updates.length()) {
                val update=updates.getJSONObject(i)
                Text(update.optString("provider_name"),fontWeight=FontWeight.Bold)
                Text(update.optString("created_at").take(10),fontSize=11.sp)
                val rows=update.optJSONArray("changes")?:JSONArray()
                val seen=mutableSetOf<String>()
                for(j in 0 until rows.length()) {
                    val row=rows.getJSONObject(j)
                    val key="${row.optString("category")}-${row.optString("old_name")}-${row.optString("new_name")}-${row.optInt("day")}-${row.optString("slot")}-${row.optString("old_item")}-${row.optString("new_item")}-${row.optString("date") .takeUnless { it=="null" }.orEmpty()}"
                    // Prefer recurring summaries, instead of repeating every scheduled date.
                    if(row.optString("kind").startsWith("daily") && (0 until rows.length()).any { k -> rows.getJSONObject(k).let { it.optString("kind")==row.optString("kind").replace("daily","weekly") && it.optInt("day")==row.optInt("day") && it.optString("slot")==row.optString("slot") } })continue
                    if(seen.add(key)) {
                        val day=listOf("","Monday","Tuesday","Wednesday","Thursday","Friday","Saturday","Sunday").getOrElse(row.optInt("day")){""}
                        Text("$day · ${row.optString("slot").lowercase()}" + row.optString("date").takeIf { it.isNotBlank()&&it!="null" }?.let { " · $it" }.orEmpty(),fontWeight=FontWeight.Medium,fontSize=12.sp)
                        Text((if(row.optString("category")=="SIDE") "Sides: " else "") + "${row.optString("old_name").ifBlank { "None" }} → ${row.optString("new_name").ifBlank { "None" }}",fontSize=13.sp)
                    }
                }
                HorizontalDivider()
            }
        }
    },confirmButton={TextButton(onClick={expanded=false}){Text("Done")}})
}
