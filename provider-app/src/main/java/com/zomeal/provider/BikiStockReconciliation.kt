package com.zomeal.provider

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

/** All edits here are preview-only. The caller owns the actual stock-report save. */
@Composable internal fun BikiStockReconciliation(repository:SupabaseProviderRepository,payload:String,cycleId:String?=null,onClose:()->Unit) {
    var report by remember{mutableStateOf<JSONObject?>(null)}
    var error by remember{mutableStateOf<String?>(null)}
    var loading by remember{mutableStateOf(true)}
    val purchased=remember{mutableStateMapOf<Int,String>()}
    val confirmed=remember{mutableStateMapOf<Int,Boolean>()}
    val alive=remember{java.util.concurrent.atomic.AtomicBoolean(true)}
    DisposableEffect(Unit){onDispose{alive.set(false)}}
    LaunchedEffect(payload,cycleId){
        val receive:(JSONObject?,String?)->Unit={data,problem->if(alive.get()){
            loading=false;report=data;error=problem
            val items=data?.optJSONArray("items")?:JSONArray()
            for(i in 0 until items.length()) {
                val row=items.getJSONObject(i)
                purchased[i]=if(row.optInt("purchase_review_count")>0||row.isNull("opening"))"" else row.optString("known_purchases")
            }
        }}
        if(cycleId==null)repository.reconcileInventory(JSONObject(payload),receive) else repository.compareStockCycle(cycleId,receive)
    }
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.ime)),color=Color(0xFFF5FAF6)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp).padding(bottom=56.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Row { Text("BIKI · Compare stock",Modifier.weight(1f),fontSize=20.sp,color=Color(0xFF087F43));TextButton(onClose){Text("Close")} }
                Text("Earlier physical count + purchases − new physical count",fontSize=14.sp)
                Text(if(cycleId==null)"Review only · This does not save your count, create expenses or change profit. Return to the stock sheet to save the actual count." else "Saved cycle · Compare opening and closing counts. Purchase confirmations here are temporary; no expenses, consumption or profit are changed.",fontSize=12.sp)
                if(loading)LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let{Text("Couldn't load comparison: $it\nYou can still save a physical count from the stock sheet. The comparison requires the stock-reconciliation server update.",color=MaterialTheme.colorScheme.error,fontSize=12.sp)}
                val items=report?.optJSONArray("items")?:JSONArray()
                val unreported=report?.optJSONArray("unreported")?:JSONArray()
                if(unreported.length()>0)Text("Missing closing quantities: "+(0 until unreported.length()).joinToString{unreported.optString(it)}+". These are unknown, not zero.",color=MaterialTheme.colorScheme.error,fontSize=12.sp)
                for(i in 0 until items.length()) {
                    val row=items.getJSONObject(i)
                    val opening=row.optString("opening").toBigDecimalOrNull()
                    val closing=row.optString("closing").toBigDecimalOrNull()
                    val unit=row.optString("unit")
                    Surface(color=Color.White,shape=MaterialTheme.shapes.medium) {
                        Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Text(row.optString("item"),fontSize=17.sp,color=Color(0xFF087F43))
                            if(opening==null) {
                                Text(row.optString("notice"),fontSize=12.sp)
                                Text("New count: ${row.optString("closing")} $unit · reduction unknown",fontSize=13.sp)
                            } else {
                                Text("From ${row.optString("opening_date")} after ${row.optString("opening_slot")} to ${report!!.optString("date")} after ${report!!.optString("slot")} · ${row.optInt("days")} days",fontSize=12.sp)
                                Text("Earlier: ${recipeQuantity(opening,unit)}   New: ${closing?.let{recipeQuantity(it,unit)}?:"Check quantity"}",fontSize=13.sp)
                                val entries=row.optJSONArray("purchases")?:JSONArray()
                                Column(Modifier.horizontalScroll(rememberScrollState())) {
                                    Row(Modifier.background(Color(0xFFE3F3E9)).padding(8.dp)) {
                                        Text("Purchase date",Modifier.width(115.dp),fontSize=12.sp)
                                        Text("Recorded quantity",Modifier.width(145.dp),fontSize=12.sp)
                                        Text("Timing / units",Modifier.width(130.dp),fontSize=12.sp)
                                    }
                                    for(j in 0 until entries.length()) {
                                        val entry=entries.getJSONObject(j)
                                        Row(Modifier.padding(8.dp)) {
                                            Text(entry.optString("date"),Modifier.width(115.dp),fontSize=12.sp)
                                            Text("${if(entry.isNull("quantity"))"Unknown" else entry.optString("quantity")} ${entry.optString("unit")}",Modifier.width(145.dp),fontSize=12.sp)
                                            Text(if(entry.optBoolean("needs_review"))"Check inclusion" else "Within interval",Modifier.width(130.dp),fontSize=12.sp)
                                        }
                                    }
                                }
                                Text("Known interior-day purchases: ${row.optString("known_purchases")} $unit. ${row.optInt("purchase_review_count")} entries need timing or unit review. No matching entries does not prove no purchases.",fontSize=11.sp)
                                Text("Same-day purchases may be before or after the count. Include only purchases between these two counts. Add any missed purchases separately to the expense ledger; typing below does not record them.",fontSize=11.sp)
                                OutlinedTextField(purchased[i].orEmpty(),{purchased[i]=it;confirmed[i]=false},label={Text("Total purchases in this interval ($unit)")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.fillMaxWidth())
                                val quantity=purchased[i]?.toBigDecimalOrNull()?.takeIf{it>=BigDecimal.ZERO&&it<=BigDecimal("100000000")}
                                Row {
                                    Checkbox(confirmed[i]==true,{confirmed[i]=it},enabled=quantity!=null)
                                    Text("I checked purchase timing, units and completeness",Modifier.padding(top=10.dp),fontSize=12.sp)
                                }
                                val reduction=closing?.let{bikiStockReduction(opening,quantity,it,confirmed[i]==true)}
                                Text(when {
                                    reduction==null->"Stock reduction: confirm purchases first"
                                    reduction.signum()<0->"Count discrepancy: ${recipeQuantity(reduction.abs(),unit)} more stock than explained"
                                    else->"Observed stock reduction: ${recipeQuantity(reduction,unit)}"
                                },color=if(reduction!=null&&reduction.signum()<0)MaterialTheme.colorScheme.error else Color(0xFF087F43),fontSize=14.sp)
                                Text("This difference may include cooking, staff meals, spoilage, transfers or counting errors. BIKI does not label it as waste or use it to change profit.",fontSize=11.sp)
                            }
                        }
                    }
                }
                Button(onClose,modifier=Modifier.fillMaxWidth()){Text("Back to stock sheet")}
                Text("Purchase corrections here are temporary and clear when closed. Only the physical-count sheet saves a stock observation.",fontSize=11.sp)
            }
        }
    }
}
