package com.zomeal.provider

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Physical counts are not purchases and never post financial entries. */
@Composable internal fun BikiDailyStockSheet(repository:SupabaseProviderRepository,onClose:()->Unit,onSaved:()->Unit,initialAction:String="") {
 var book by remember { mutableStateOf<JSONObject?>(null) }
 var error by remember { mutableStateOf<String?>(null) }
 var busy by remember { mutableStateOf(false) }
 var action by rememberSaveable { mutableStateOf("") }
 var cycle by rememberSaveable { mutableStateOf("{}") }
 var rows by rememberSaveable { mutableStateOf("[]") }
 var counted by rememberSaveable { mutableStateOf("") }
 var pending by rememberSaveable { mutableStateOf<String?>(null) }
 var voice by remember { mutableStateOf(false) }
 var plan by remember { mutableStateOf(false) }
 var purchases by remember { mutableStateOf(false) }
 var discard by remember { mutableStateOf(false) }
 var launched by rememberSaveable { mutableStateOf(false) }
 var savedMessage by rememberSaveable { mutableStateOf("") }
 var changeTime by remember { mutableStateOf(false) }
 var compareCycle by remember { mutableStateOf<String?>(null) }
 val alive=remember { java.util.concurrent.atomic.AtomicBoolean(true) }
 DisposableEffect(Unit){onDispose{alive.set(false)}}
 fun load(){repository.stockCycles(){data,problem->if(alive.get()){book=data;error=problem}}}
 LaunchedEffect(Unit){load()}
 fun now()=LocalDateTime.now(ZoneId.of("Asia/Kolkata")).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))
 fun start(kind:String,c:JSONObject){action=kind;cycle=c.toString();counted=now();rows="[]";error=null}
 LaunchedEffect(book){
  if(!launched&&book!=null&&initialAction.isNotEmpty()){
   launched=true
   val all=book!!.optJSONArray("cycles")?:JSONArray()
   val active=(0 until all.length()).map{all.getJSONObject(it)}.firstOrNull{it.isNull("closed_at")}
   when{
    initialAction=="OPEN"&&active==null->{start("OPEN",JSONObject().put("id",UUID.randomUUID().toString()));voice=true}
    initialAction=="CLOSE"&&active!=null->{start("CLOSE",active);voice=true}
    initialAction=="OPEN"->error="You already have an opening count. Record its closing stock before starting another."
    else->error="Record opening stock first so BIKI can reuse its prices when you close."
   }
  }
 }
 fun edit(i:Int,key:String,value:String){val a=JSONArray(rows);a.getJSONObject(i).put(key,value);rows=a.toString()}
 fun close(){if(!busy){if(action.isNotEmpty())discard=true else onClose()}}
 if(voice) KitchenVoiceDialog(repository,if(action=="OPEN")"OPENING_STOCK" else "CLOSING_STOCK",counted.take(10),onDismiss={voice=false},onApply={incoming->
  val a=JSONArray(rows)
  for(i in 0 until incoming.length()){val r=incoming.getJSONObject(i);val at=(0 until a.length()).firstOrNull{shoppingKey(a.getJSONObject(it).optString("item"))==shoppingKey(r.optString("item"))};if(at==null)a.put(r) else a.put(at,r)}
  rows=a.toString();voice=false
 },quantityOnly=action=="CLOSE",directDraft=true)
 if(plan) BikiShoppingSheet(repository,onClose={plan=false})
 if(purchases) KitchenCostSheet(now().take(10),repository=repository,onDismiss={purchases=false},onSave={r,done->repository.saveKitchenSheet(r){_,problem->if(alive.get()){done(problem);if(problem==null){purchases=false;onSaved()}}}})
 compareCycle?.let { id -> BikiStockReconciliation(repository,"{}",onClose={compareCycle=null},cycleId=id) }
 if(!voice&&!plan&&!purchases&&compareCycle==null) Dialog(onDismissRequest=::close,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)){
  Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.ime)),color=Color(0xFFF5F9F6)){
   Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp).padding(bottom=56.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
    Text(if(action.isEmpty())"BIKI · Stock history" else "BIKI · Check your stock",style=MaterialTheme.typography.headlineSmall,color=Color(0xFF087F43))
    if(savedMessage.isNotEmpty())Surface(color=Color(0xFFE2F1E7),shape=RoundedCornerShape(16.dp)){Text(savedMessage,Modifier.fillMaxWidth().padding(18.dp))}
    if(action.isEmpty()){
     if(book==null&&error==null){LinearProgressIndicator(Modifier.fillMaxWidth());Text("Loading your kitchen stock…")}
     Text("Opening and closing can be on different days. Nothing resets at midnight.")
     Text("Saved counts are available in the shopping plan as review-only references. Monthly valuation stays separate.",style=MaterialTheme.typography.bodySmall)
     OutlinedButton(onClick={load()}){Text("Refresh saved cycles")}
     val all=book?.optJSONArray("cycles")?:JSONArray()
     val active=(0 until all.length()).map{all.getJSONObject(it)}.firstOrNull{it.isNull("closed_at")}
     Button(enabled=book!=null&&active==null,onClick={start("OPEN",JSONObject().put("id",UUID.randomUUID().toString()));voice=true}){Text("Record opening stock")}
     if(active!=null){
      val days=runCatching{java.time.temporal.ChronoUnit.DAYS.between(java.time.OffsetDateTime.parse(active.getString("opened_at")).atZoneSameInstant(ZoneId.of("Asia/Kolkata")).toLocalDate(),java.time.LocalDate.now(ZoneId.of("Asia/Kolkata")))}.getOrDefault(0).coerceAtLeast(0)
      Surface(color=Color(0xFFFFF3D9),shape=RoundedCornerShape(16.dp)){Column(Modifier.fillMaxWidth().padding(16.dp)){
       Text(if(days==0L)"Closing stock is pending for this cycle" else "Closing stock pending · opened $days days ago")
       Text("Nothing resets at midnight. Record the actual count time; BIKI won't assume the missing stock was consumed.",style=MaterialTheme.typography.bodySmall)
       Button(onClick={start("CLOSE",active);voice=true}){Text("Record closing stock")}
      }}
     }
     Text("Recent cycles · up to 60",style=MaterialTheme.typography.titleMedium)
     for(i in 0 until all.length()){
      val c=all.getJSONObject(i)
      HorizontalDivider()
      fun local(key:String)=runCatching{java.time.OffsetDateTime.parse(c.getString(key)).atZoneSameInstant(ZoneId.of("Asia/Kolkata")).format(DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm"))}.getOrDefault("Not closed")
      Text("Opened ${local("opened_at")} IST • Closed ${local("closed_at")} IST")
      Text("Submitted ${local("created_at")} • Last saved ${local("updated_at")} IST",style=MaterialTheme.typography.bodySmall)
      if(!c.isNull("closed_at"))OutlinedButton(onClick={compareCycle=c.getString("id")}){Text("Compare stock · purchases & usage")}
      for(key in listOf("opening_rows","closing_rows")){
       val a=c.optJSONArray(key)?:continue
       Text(if(key=="opening_rows")"Opening" else "Closing",style=MaterialTheme.typography.titleSmall)
       for(j in 0 until a.length()){val r=a.getJSONObject(j);Text("${r.optString("item")}: ${r.optString("quantity")} ${r.optString("unit")} · ${if(r.isNull("amount"))"price unknown" else "₹${r.optString("amount")}"}")}
      }
     }
    }else{
     val c=JSONObject(cycle);val opening=c.optJSONArray("opening_rows")?:JSONArray()
     val prices=(0 until opening.length()).mapNotNull{i->val r=opening.getJSONObject(i);val q=r.optString("quantity").toBigDecimalOrNull();val p=r.optString("amount").toBigDecimalOrNull();if(q==null||p==null)null else OpeningStockPrice(r.optString("item"),q,r.optString("unit"),p)}
     val editable=pending==null&&!busy
     Text(if(action=="OPEN")"Opening stock · review quantities and total costs" else "Closing stock · opening prices reused")
     TextButton(onClick={changeTime=!changeTime},enabled=editable){Text("Counted ${counted.replace('T',' ')} IST · Change")}
     if(changeTime)OutlinedTextField(counted,{counted=it},enabled=editable,label={Text("YYYY-MM-DDTHH:mm · IST")},supportingText={Text("Actual count time, even if you report later.")},modifier=Modifier.fillMaxWidth())
     OutlinedButton(onClick={voice=true},enabled=editable){Text("Record more items")}
     if(action=="OPEN"&&editable&&JSONArray(rows).length()==0) BikiStockReference(repository,closingOnly=true){draft->rows=draft.toString();error=null}
     if(action=="CLOSE"){
      Text("Tap an opening item to add it, then enter its remaining quantity. Omitted items are unreported, not zero.")
      for(i in 0 until opening.length()){val r=opening.getJSONObject(i);TextButton(enabled=editable,onClick={val a=JSONArray(rows);if((0 until a.length()).none{shoppingKey(a.getJSONObject(it).optString("item"))==shoppingKey(r.optString("item"))}){a.put(JSONObject().put("item",r.optString("item")).put("unit",r.optString("unit")).put("quantity",""));rows=a.toString()}}){Text(r.optString("item"))}}
     }
     val a=JSONArray(rows)
     Row(Modifier.fillMaxWidth().padding(8.dp)){Text("Item",Modifier.weight(1.5f));Text("Qty",Modifier.weight(.8f));Text("Unit",Modifier.weight(.9f))}
     for(i in 0 until a.length()){val r=a.getJSONObject(i)
      Surface(color=if(i%2==0)Color.White else Color(0xFFEAF5EE),shape=RoundedCornerShape(12.dp)){
       Column(Modifier.padding(8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
        Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
         OutlinedTextField(r.optString("item"),{edit(i,"item",it)},enabled=editable,modifier=Modifier.weight(1.5f),label={Text("Item")})
         OutlinedTextField(r.optString("quantity"),{edit(i,"quantity",it)},enabled=editable,modifier=Modifier.weight(.8f),label={Text("Qty")},singleLine=true)
         OutlinedTextField(r.optString("unit"),{edit(i,"unit",it)},enabled=editable,modifier=Modifier.weight(.9f),label={Text("Unit")},singleLine=true)
        }
        if(r.optString("unit") in listOf("packet","bag")){
         BikiPackSize(r.optString("item"),r.optString("quantity"),editable){size,unit->
          val q=r.optString("quantity").toBigDecimalOrNull();val converted=q?.let{stockPackQuantity(it,size)}
          if(converted==null)error="Enter a valid quantity and pack size (up to 3 decimal places)." else {val n=JSONArray(rows);n.getJSONObject(i).put("quantity",converted.toPlainString()).put("unit",unit);rows=n.toString();error=null}
         }
        }
        Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){
         if(action=="OPEN")OutlinedTextField(r.optString("amount",""),{edit(i,"amount",it)},enabled=editable,modifier=Modifier.weight(1f),label={Text("Total ₹ · optional")},singleLine=true)
         else Text(r.optString("quantity").toBigDecimalOrNull()?.let{closingStockValue(r.optString("item"),it,r.optString("unit"),prices)}?.let{"₹$it · opening rate"}?:"Price unknown",Modifier.weight(1f))
         TextButton(enabled=editable,onClick={val n=JSONArray(rows);n.remove(i);rows=n.toString()}){Text("Remove")}
        }
       }
      }
     }
     Text("Units: kg, g, litre, ml, piece. Use 0 only when nothing remains.",style=MaterialTheme.typography.bodySmall)
     TextButton(enabled=editable&&a.length()<100,onClick={a.put(JSONObject().put("item","").put("quantity","").put("unit","kg").put("amount",""));rows=a.toString()}){Text("+ Add item")}
     if(action=="CLOSE"){
      val missing=(0 until opening.length()).count{i->(0 until a.length()).none{shoppingKey(a.getJSONObject(it).optString("item"))==shoppingKey(opening.getJSONObject(i).optString("item"))}}
      Text("$missing opening items unreported. Closing values use opening rates only; later purchases may have different prices.")
     }
     Button(enabled=!busy&&a.length()>0,onClick={
      if(pending==null){
       val invalid=(0 until a.length()).firstOrNull{val r=a.getJSONObject(it);val q=r.optString("quantity").toBigDecimalOrNull();r.optString("item").isBlank()||r.optString("unit") !in listOf("kg","g","litre","ml","piece")||q==null||q.signum()<0}
       if(invalid!=null){val r=a.getJSONObject(invalid);error="Row ${invalid+1} (${r.optString("item")}): enter a quantity in kg, g, litre, ml or piece. For packets or bags, enter their total measured contents; BIKI won't guess the pack size.";return@Button}
      }
      val p=pending?.let(::JSONObject)?:JSONObject().put("id",c.getString("id")).put("request_id",UUID.randomUUID().toString()).put("action",action).put("counted_at",counted).put("revision",c.optInt("revision")).put("rows",a)
      pending=p.toString();busy=true;error=null
      repository.saveStockCycle(p){result,problem,rejected->if(alive.get()){busy=false;error=problem;if(rejected)pending=null;if(problem==null&&result?.has("cycle")==true){savedMessage="${if(action=="OPEN")"Opening" else "Closing"} stock saved · Counted ${counted.replace('T',' ')} IST. No expense was added.";pending=null;action="";rows="[]";load();onSaved()}}}
     },modifier=Modifier.fillMaxWidth()){Text(if(busy)"Saving…" else if(pending!=null)"Retry unchanged count" else "Save reviewed ${action.lowercase()} count")}
     if(pending!=null&&!busy) Text("If validation failed, discard this draft to correct it. For a connection error, retry first to avoid duplicate submissions.",style=MaterialTheme.typography.bodySmall)
    }
    error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
    OutlinedButton(onClick=::close,enabled=!busy,modifier=Modifier.fillMaxWidth()){Text("Close")}
   }
  }
 }
 if(discard) AlertDialog(onDismissRequest={discard=false},title={Text("Leave stock draft?")},text={Text("Unsaved rows will be discarded. A request already received by the server may have saved; refresh before entering it again.")},confirmButton={TextButton(onClick={discard=false;action="";rows="[]";pending=null;load()}){Text("Discard draft & refresh")}},dismissButton={TextButton(onClick={discard=false}){Text("Keep editing")}})
}
