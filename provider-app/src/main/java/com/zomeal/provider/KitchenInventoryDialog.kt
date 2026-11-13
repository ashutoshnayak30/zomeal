package com.zomeal.provider

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Inventory observations never create expenses; catch-up purchases use the existing ledger. */
@Composable internal fun KitchenInventoryDialog(repository:SupabaseProviderRepository,onDismiss:()->Unit,onSaved:()->Unit) {
 val today=LocalDate.now(ZoneId.of("Asia/Kolkata")).toString()
 var period by rememberSaveable { mutableIntStateOf(7) }
 var report by remember { mutableStateOf<JSONObject?>(null) }
 var error by remember { mutableStateOf<String?>(null) }
 var busy by remember { mutableStateOf(false) }
 var draft by rememberSaveable { mutableStateOf("[]") }
 var date by rememberSaveable { mutableStateOf(today) }
 var slot by rememberSaveable { mutableStateOf("DINNER") }
 var key by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
 var pending by rememberSaveable { mutableStateOf<String?>(null) }
 var voice by remember { mutableStateOf(false) }
 var expenses by remember { mutableStateOf(false) }
 var discard by remember { mutableStateOf(false) }
 var comparison by remember { mutableStateOf<String?>(null) }
 val live=remember { java.util.concurrent.atomic.AtomicBoolean(true) }
 var serial by remember { mutableIntStateOf(0) }
 DisposableEffect(Unit) { live.set(true); onDispose { live.set(false) } }
 fun load() { val request=++serial; repository.inventoryReview(period){data,problem->if(live.get()&&request==serial){report=data;error=problem}} }
 LaunchedEffect(period) { load() }
 fun edit(i:Int,field:String,value:String) { val a=JSONArray(draft);a.getJSONObject(i).put(field,value);draft=a.toString() }
 fun close() { if(!busy) { if(JSONArray(draft).length()>0) discard=true else onDismiss() } }
 if(voice) KitchenVoiceDialog(repository,"CLOSING_STOCK",date,onDismiss={voice=false},onApply={incoming->
  val a=JSONArray(draft)
  for(i in 0 until incoming.length()) { val r=incoming.getJSONObject(i);val name=r.optString("item").trim();val existing=(0 until a.length()).firstOrNull { a.getJSONObject(it).optString("item").equals(name,true) }
   val n=JSONObject().put("item",name).put("quantity",r.optString("quantity")).put("unit",r.optString("unit")).put("per_meal","")
   if(existing==null)a.put(n) else a.put(existing,n)
  };draft=a.toString();voice=false
 },quantityOnly=true)
 if(expenses) KitchenCostSheet(date,repository=repository,onDismiss={expenses=false},onSave={rows,done->repository.saveKitchenSheet(rows){_,problem->if(live.get()){done(problem);if(problem==null){expenses=false;load();onSaved()}}}})
 comparison?.let { BikiStockReconciliation(repository,it){comparison=null} }
 if(!voice&&!expenses&&comparison==null) Dialog(onDismissRequest=::close,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
  Scaffold(modifier=Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.ime)).padding(bottom=56.dp),containerColor=Color(0xFFF5F9F6),contentWindowInsets=WindowInsets(0,0,0,0),
   topBar={Row(Modifier.fillMaxWidth().padding(16.dp)){Text("BIKI · Inventory review",Modifier.weight(1f),fontSize=18.sp);TextButton(onClick=::close,enabled=!busy){Text("Close")}}},
   bottomBar={Surface { Button(modifier=Modifier.fillMaxWidth().padding(12.dp),enabled=!busy&&JSONArray(draft).length()>0,onClick={
    val payload=pending?.let(::JSONObject)?:JSONObject().put("id",key).put("date",date).put("slot",slot).put("rows",JSONArray(draft))
    pending=payload.toString();busy=true;error=null
    repository.saveInventory(payload){_,problem->if(live.get()){busy=false;error=problem;if(problem==null){pending=null;draft="[]";key=UUID.randomUUID().toString();load();onSaved()}}}
   }){Text(if(busy)"Saving…" else if(pending!=null)"Retry same stock report" else "Save confirmed stock report")} } }
  ){padding->Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
   Row { listOf(7,15).forEach { n->FilterChip(selected=period==n,onClick={period=n},label={Text("$n-day review")});Spacer(Modifier.width(8.dp)) } }
   error?.let{Text(it,color=MaterialTheme.colorScheme.error,fontSize=12.sp)}
   Text(if(report?.isNull("last_report")!=false)"No confirmed inventory report yet. Add your remaining ingredients below." else "Last report: ${report!!.optString("last_report")} · ${report!!.optInt("days_since")} days ago",fontSize=14.sp)
   Text("Each ingredient has its own last-check date. Missing items are not zero. Gas/fuel needs purchase entries only, not daily stock counts.",fontSize=12.sp)
   val days=report?.optJSONArray("days")?:JSONArray()
   val unreported=(0 until days.length()).count{!days.getJSONObject(it).optBoolean("reported")}
   Surface(color=Color(0xFFE2F1E7),shape=MaterialTheme.shapes.medium){Column(Modifier.fillMaxWidth().padding(12.dp)){
    Text("$unreported days without a stock report",color=Color(0xFF087F43),fontSize=17.sp)
    Text("In this $period-day window. A report can cover only some ingredients; each item's date below still matters. Missing reports are not zero stock.",fontSize=12.sp)
   }}
   Column(Modifier.horizontalScroll(rememberScrollState())) {
    Row(Modifier.background(Color(0xFFE2F1E7)).padding(8.dp)){Text("Date",Modifier.width(120.dp));Text("Delivered",Modifier.width(90.dp));Text("Stock report",Modifier.width(120.dp))}
    for(i in 0 until days.length()){val d=days.getJSONObject(i);Row(Modifier.padding(8.dp)){Text(d.optString("date"),Modifier.width(120.dp),fontSize=12.sp);Text(d.optString("delivered"),Modifier.width(90.dp));Text(if(d.optBoolean("reported"))"Recorded" else "Not reported",Modifier.width(120.dp),fontSize=12.sp)}}
   }
   val items=report?.optJSONArray("items")?:JSONArray()
   for(i in 0 until items.length()){val r=items.getJSONObject(i);Surface(color=Color.White){Column(Modifier.fillMaxWidth().padding(12.dp)){
    Text("${r.optString("item")} · ${r.optString("quantity")} ${r.optString("unit")} confirmed",fontSize=14.sp)
    Text("${r.optString("last_date")} ${r.optString("slot")} · ${r.optInt("days_since")} days since check · ${r.optInt("delivered_since")} delivered meals since",fontSize=11.sp)
    Text(if(r.isNull("estimated_used"))"No estimate: add a reviewed per-meal quantity; check purchase units." else "Estimated used ${r.optString("estimated_used")} · estimated remaining ${r.optString("estimated_remaining")} ${r.optString("unit")}",fontSize=12.sp)
    TextButton(enabled=pending==null&&!busy,onClick={val a=JSONArray(draft);if((0 until a.length()).none{a.getJSONObject(it).optString("item").equals(r.optString("item"),true)}){a.put(JSONObject().put("item",r.optString("item")).put("unit",r.optString("unit")).put("quantity","").put("per_meal",if(r.isNull("per_meal"))"" else r.optString("per_meal")));draft=a.toString()}}){Text("Update this ingredient")}
   }}}
   Text(report?.optString("notice").orEmpty(),fontSize=11.sp)
   HorizontalDivider()
   Text("Record a stock check / catch up",fontSize=16.sp)
   OutlinedTextField(date,{if(pending==null)date=it},label={Text("Actual date · YYYY-MM-DD")},enabled=pending==null&&!busy,singleLine=true)
   Row { listOf("LUNCH","DINNER").forEach{s->FilterChip(selected=slot==s,onClick={slot=s},enabled=pending==null&&!busy,label={Text("After $s")})} }
   Text("Enter observed remaining stock, not an estimate. Blank per-meal quantity means no consumption prediction. Use kg, g, litre, ml or piece; convert trays/bags to their actual contents first.",fontSize=12.sp)
   Button(onClick={voice=true},enabled=pending==null&&!busy){Text("Speak remaining ingredients")}
   val rows=JSONArray(draft)
   Column(Modifier.horizontalScroll(rememberScrollState())) {
    Row(Modifier.background(Color(0xFFE2F1E7)).padding(4.dp)){listOf("Ingredient","Remaining","Base unit","Per meal (optional)").forEach { Text(it,Modifier.width(160.dp),fontSize=12.sp) }}
    for(i in 0 until rows.length()){val r=rows.getJSONObject(i);Row{listOf("item","quantity","unit","per_meal").forEach{f->OutlinedTextField(r.optString(f),{edit(i,f,it)},modifier=Modifier.width(160.dp).padding(4.dp),singleLine=true,enabled=pending==null&&!busy,keyboardOptions=KeyboardOptions(keyboardType=if(f in listOf("quantity","per_meal"))KeyboardType.Decimal else KeyboardType.Text))};TextButton(enabled=pending==null&&!busy,onClick={val a=JSONArray(draft);a.remove(i);draft=a.toString()}){Text("Remove")}}}
   }
   TextButton(enabled=pending==null&&!busy,onClick={val a=JSONArray(draft);a.put(JSONObject().put("item","").put("quantity","").put("unit","kg").put("per_meal",""));draft=a.toString()}){Text("+ Add ingredient row")}
   OutlinedButton(enabled=pending==null&&!busy&&rows.length()>0,onClick={comparison=JSONObject().put("date",date).put("slot",slot).put("rows",JSONArray(draft)).toString()},modifier=Modifier.fillMaxWidth()){Text("Compare with earlier stock · review only")}
   if(pending!=null) TextButton(enabled=!busy,onClick={pending=null;key=UUID.randomUUID().toString();load();error="Check existing reports before resubmitting; the previous request may have succeeded."}){Text("Unlock editing · check saved report first")}
   OutlinedButton(onClick={expenses=true},enabled=!busy&&pending==null){Text("Add missed purchases · voice or manual")}
   Text("Choose the original purchase date above. Add purchases for one date at a time. Check existing Entries first to avoid duplicates. Stock reports do not change the financial statement.",fontSize=12.sp)
  }}
 }
 if(discard) AlertDialog(onDismissRequest={discard=false},title={Text("Discard unsaved stock rows?")},text={Text("Saved reports remain unchanged.")},confirmButton={TextButton(onClick=onDismiss){Text("Discard")}},dismissButton={TextButton(onClick={discard=false}){Text("Keep editing")}})
}
