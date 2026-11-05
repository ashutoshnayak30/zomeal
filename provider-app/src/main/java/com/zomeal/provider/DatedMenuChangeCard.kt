package com.zomeal.provider

import android.app.DatePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import org.json.JSONArray
import java.time.LocalDate
import java.time.ZoneId

@Composable
internal fun DatedMenuChangeCard(repository:SupabaseProviderRepository) {
    var open by remember { mutableStateOf(false) };var data by remember { mutableStateOf(JSONObject()) }
    var message by remember { mutableStateOf<String?>(null) };var busy by remember { mutableStateOf(false) }
    var slot by remember { mutableStateOf("LUNCH") };var dishId by remember { mutableStateOf("") }
    var dishName by remember { mutableStateOf("") };var name by remember { mutableStateOf("") }
    val dates=remember { mutableStateListOf<String>() };var selecting by remember { mutableStateOf(false) }
    val context=LocalContext.current
    fun load(){busy=true;repository.datedMenuChanges { result,error->busy=false;message=error;if(result!=null)data=result }}
    Surface(color=Color.White,shape=RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Change a meal on specific dates",fontWeight=FontWeight.Bold)
            Text("For one-off shortages or substitutions. Your regular seven-day menu stays unchanged.",fontSize=12.sp)
            TextButton(onClick={open=true;load()}){Text("Request a dated menu change")}
        }
    }
    if(open) AlertDialog(onDismissRequest={if(!busy)open=false},title={Text("Temporary main-course change")},text={
        Column(Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("Admin approval required. The replacement keeps the original dish’s veg/non-veg category. Submit before preparation cutoff.",fontSize=12.sp)
            Row { listOf("LUNCH","DINNER").forEach { value->FilterChip(selected=slot==value,onClick={slot=value},label={Text(value)}) } }
            OutlinedButton(onClick={selecting=true}){Text(dishName.ifBlank { "Choose the dish to replace" })}
            if(selecting){val dishes=data.optJSONArray("dishes")?:JSONArray();for(i in 0 until dishes.length()){val dish=dishes.getJSONObject(i);TextButton(onClick={dishId=dish.getString("id");dishName="${dish.getString("name")} · ${dish.getString("diet")}";selecting=false}){Text("${dish.getString("name")} · ${dish.getString("diet")}")}}}
            OutlinedTextField(value=name,onValueChange={if(it.length<=100)name=it},label={Text("Replacement dish name")},singleLine=true)
            Text("Choose up to 7 dates (next 30 days)",fontSize=12.sp)
            dates.toList().forEach { date -> TextButton(onClick={dates.remove(date)}) { Text("$date × Remove") } }
            OutlinedButton(enabled=dates.size<7,onClick={val today=LocalDate.now(ZoneId.of("Asia/Kolkata"));DatePickerDialog(context,{_,y,m,d->val selected=LocalDate.of(y,m+1,d).toString();if(selected !in dates)dates.add(selected)},today.year,today.monthValue-1,today.dayOfMonth).show()}){Text("Add date")}
            message?.let { Text(it,color=Color(0xFFB3261E),fontSize=12.sp) }
            if(busy)LinearProgressIndicator()
            Text("Recent requests",fontWeight=FontWeight.Bold)
            val rows=data.optJSONArray("items")?:JSONArray()
            for(i in 0 until rows.length()){val row=rows.getJSONObject(i);Text("${row.optString("old_name")} → ${row.optString("new_name")}\n${row.optString("meal_slot")} · ${row.optJSONArray("service_dates")}\n${row.optString("status")}",fontSize=12.sp)}
        }
    },confirmButton={Button(enabled=!busy&&dishId.isNotBlank()&&name.trim().length>=2&&dates.isNotEmpty(),onClick={
        busy=true;repository.datedMenuChanges(JSONObject().put("old_item",dishId).put("new_name",name.trim()).put("slot",slot).put("dates",JSONArray(dates.toList()))) { result,error->busy=false;message=error?:"Sent to admin for approval.";if(result!=null){data=result;dates.clear();name=""} }
    }){Text("Send for approval")}},dismissButton={TextButton(enabled=!busy,onClick={open=false}){Text("Close")}})
}
