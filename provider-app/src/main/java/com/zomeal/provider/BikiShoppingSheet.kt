package com.zomeal.provider

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

/** Read-only plan: no inventory, expense, delivery or wallet mutations. */
@Composable internal fun BikiShoppingSheet(repository:SupabaseProviderRepository,onClose:()->Unit) {
    val today=remember{LocalDate.now(ZoneId.of("Asia/Kolkata"))}
    var date by remember{mutableStateOf(today.plusDays(1).toString())}
    var slot by remember{mutableStateOf("BOTH")}
    var book by remember{mutableStateOf<JSONObject?>(null)}
    var stock by remember{mutableStateOf<JSONObject?>(null)}
    var error by remember{mutableStateOf<String?>(null)}
    var loading by remember{mutableStateOf(false)}
    var inventory by remember{mutableStateOf(false)}
    var recipesOpen by remember{mutableStateOf(false)}
    var generation by remember{mutableIntStateOf(0)}
    var preview by remember{mutableStateOf(false)}
    var previewRecipe by remember{mutableStateOf("")}
    var previewPortions by remember{mutableStateOf("100")}
    val amounts=remember{mutableStateMapOf<String,String>()}
    val confirmed=remember{mutableStateMapOf<String,Boolean>()}
    val live=remember{java.util.concurrent.atomic.AtomicBoolean(true)}
    DisposableEffect(Unit){onDispose{live.set(false)}}
    fun refresh(){
        val request=++generation;loading=true;error=null;book=null;stock=null;amounts.clear();confirmed.clear()
        repository.recipeBook{data,problem->if(live.get()&&request==generation){
            if(data==null||problem!=null){loading=false;error=problem?:"Recipes unavailable"}
            else {book=data;repository.inventoryReview(15){report,stockError->if(live.get()&&request==generation){stock=report;error=stockError;loading=false}}}
        }}
    }
    LaunchedEffect(Unit){refresh()}
    if(inventory) KitchenInventoryDialog(repository,{inventory=false;refresh()},{})
    if(recipesOpen) BikiRecipeSheet(repository,{recipesOpen=false;refresh()})
    if(!inventory&&!recipesOpen) Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.ime)),color=Color(0xFFF5FAF6)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp).padding(bottom=56.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Text("BIKI · Shopping planner",fontSize=21.sp,color=Color(0xFF087F43))
                Text("Recipe needs → check available stock → buy the shortage",fontSize=12.sp)
                Text("Main courses and every included menu side: rice, roti, dal and more. Missing recipes are listed below. Staff meals and waste need separate allowance.",fontSize=12.sp,color=Color(0xFF865C00))
                Row{OutlinedButton({refresh()},enabled=!loading){Text("Refresh")};TextButton(onClose){Text("Close")}}
                if(loading)LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    for(i in 0..7){val d=today.plusDays(i.toLong()).toString();FilterChip(date==d,{date=d;confirmed.clear();amounts.clear()},label={Text(d)},enabled=!loading)}
                }
                Row{listOf("BOTH","LUNCH","DINNER").forEach{s->FilterChip(slot==s,{slot=s;confirmed.clear();amounts.clear()},label={Text(s)},enabled=!loading)}}
                val recipeRows=book?.optJSONArray("recipes")?:JSONArray()
                val recipeMap=(0 until recipeRows.length()).map{recipeRows.getJSONObject(it)}.associateBy{it.getString("id")}
                Row{Checkbox(preview,{preview=it;amounts.clear();confirmed.clear()});Text("Practice with a recipe · no real orders",Modifier.padding(top=12.dp),fontSize=12.sp)}
                if(preview){
                    Text("PREVIEW ONLY · Choose a saved recipe and portion count. No customers, orders or expenses are created.",color=Color(0xFF865C00),fontSize=12.sp)
                    Row(Modifier.horizontalScroll(rememberScrollState())){recipeMap.values.forEach{r->FilterChip(previewRecipe==r.getString("id"),{previewRecipe=r.getString("id");confirmed.clear();amounts.clear()},label={Text(r.optString("name"))})}}
                    OutlinedTextField(previewPortions,{previewPortions=it;confirmed.clear();amounts.clear()},singleLine=true,label={Text("Practice portions · 1–10,000")})
                }
                val planRows=book?.optJSONArray("plans")?:JSONArray()
                val plans=if(preview){
                    val count=previewPortions.toIntOrNull()?.takeIf{it in 1..10000};val r=recipeMap[previewRecipe]
                    if(r==null||count==null)emptyList() else listOf(JSONObject().put("recipe_id",previewRecipe).put("name",r.optString("name")).put("meal_slot","PREVIEW").put("portions",count))
                }else (0 until planRows.length()).map{planRows.getJSONObject(it)}.filter{it.optString("service_date")==date&&(slot=="BOTH"||slot==it.optString("meal_slot"))}
                val raw=mutableListOf<ShoppingNeed>();var invalid=0
                plans.forEach{p->
                    val r=recipeMap[p.optString("recipe_id")]
                    if(r==null)invalid++ else {
                        val ingredients=r.optJSONArray("rows")?:JSONArray()
                        if(ingredients.length()==0)invalid++
                        for(i in 0 until ingredients.length())runCatching{
                            val row=ingredients.getJSONObject(i)
                            val q=recipeScale(row.getString("quantity"),r.getInt("batch"),p.getInt("portions"))
                            require(q>BigDecimal.ZERO&&recipeFamily(row.getString("unit"))!="unknown")
                            raw.add(ShoppingNeed(row.getString("item"),row.getString("unit"),q))
                        }.onFailure{invalid++}
                    }
                }
                val coverage=book?.optJSONArray("coverage")?:JSONArray()
                val mealCount=(0 until coverage.length()).map{coverage.getJSONObject(it)}.filter{it.optString("service_date")==date&&(slot=="BOTH"||slot==it.optString("meal_slot"))}.sumOf{it.optInt("meals")}
                Text(if(preview)"Practice · ${plans.sumOf{it.optInt("portions")}} dish servings" else "$mealCount meals · ${plans.sumOf{it.optInt("portions")}} linked dish servings",fontSize=16.sp)
                if(!preview&&book!=null&&!book!!.optBoolean("all_dishes"))Text("Server needs the all-dishes update. This list may omit side dishes.",color=MaterialTheme.colorScheme.error)
                val missingRows=book?.optJSONArray("missing_recipes")?:JSONArray()
                val missingDishes=if(preview)emptyList() else (0 until missingRows.length()).map{missingRows.getJSONObject(it)}.filter{it.optString("service_date")==date&&(slot=="BOTH"||slot==it.optString("meal_slot"))}
                if(missingDishes.isNotEmpty()) {
                    Text("Incomplete plan · ${missingDishes.size} dishes need recipes or menu data",color=MaterialTheme.colorScheme.error)
                    missingDishes.forEach{Text("${it.optString("meal_slot")} · ${it.optString("name")} · ${it.optInt("portions")} servings missing",fontSize=12.sp)}
                    Text("Add each missing recipe through Manage recipes. Missing side snapshots require menu/order data correction; BIKI will not guess them.",fontSize=12.sp)
                }
                plans.forEach{Text("${it.optString("meal_slot")} · ${it.optString("name")} · ${it.optInt("portions")} portions",fontSize=12.sp)}
                if(!loading&&plans.isEmpty())Text(if(preview)"Select a saved recipe and a valid portion count." else "No upcoming linked meals for this selection. Save and link recipes first; paused, cancelled and already delivered meals are excluded.",fontSize=12.sp)
                if(invalid>0)Text("$invalid ingredient rows need recipe correction. Shopping totals below are incomplete.",color=MaterialTheme.colorScheme.error)
                val needs=shoppingAggregate(raw)
                val snapshots=stock?.optJSONArray("items")?:JSONArray()
                val stocks=(0 until snapshots.length()).map{snapshots.getJSONObject(it)}
                Text("Check what is available for this meal. Old stock reports are reference only—not today's stock. Reserve enough for earlier meals before confirming. Selecting BOTH combines lunch + dinner so stock is subtracted only once.",fontSize=12.sp)
                needs.forEach{need->
                    val key=shoppingKey(need.item)+"|"+need.unit
                    val matches=stocks.filter{shoppingKey(it.optString("item"))==shoppingKey(need.item)&&recipeFamily(it.optString("unit"))==recipeFamily(need.unit)}
                    val snapshot=matches.singleOrNull()
                    Surface(color=Color.White,shape=MaterialTheme.shapes.medium){Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                        Text(need.item,fontSize=16.sp,color=Color(0xFF087F43))
                        Text("Recipe requirement: ${recipeQuantity(need.quantity,need.unit)}",fontSize=13.sp)
                        if(snapshot==null)Text("No exact matching stock record. Different spellings/languages are not automatically merged. Check this ingredient physically.",fontSize=11.sp)
                        else {
                            Text("Last counted: ${snapshot.optString("quantity")} ${snapshot.optString("unit")} · ${snapshot.optString("last_date")} after ${snapshot.optString("slot")}\n${snapshot.optInt("days_since")} days ago · ${snapshot.optInt("delivered_since")} deliveries since",fontSize=11.sp)
                            if(!snapshot.isNull("estimated_remaining"))Text("Unconfirmed estimate: ${snapshot.optString("estimated_remaining")} ${snapshot.optString("unit")}. Not deducted from this plan.",fontSize=11.sp)
                            TextButton({
                                val converted=runCatching{snapshot.getString("quantity").toBigDecimal()*recipeBase(snapshot.getString("unit"))}.getOrNull()
                                if(converted!=null){amounts[key]=converted.stripTrailingZeros().toPlainString();confirmed[key]=false}
                            }){Text("Copy last count · review before confirming",fontSize=11.sp)}
                        }
                        OutlinedTextField(amounts[key].orEmpty(),{amounts[key]=it;confirmed[key]=false},label={Text("Available for this plan (${need.unit})")},singleLine=true,modifier=Modifier.fillMaxWidth())
                        val available=amounts[key]?.toBigDecimalOrNull()?.takeIf{it>=BigDecimal.ZERO&&it<=BigDecimal("100000000")}
                        Row{Checkbox(confirmed[key]==true,{confirmed[key]=it},enabled=available!=null);Text("I checked this quantity for the selected meals",Modifier.padding(top=12.dp),fontSize=12.sp)}
                        val gap=shoppingShortfall(need.quantity,if(confirmed[key]==true)available else null,need.unit)
                        Text(if(gap==null)"To buy: check stock first" else if(gap.signum()==0)"Enough stock for this requirement" else "To buy: ${recipeQuantity(gap,need.unit)}",color=if(gap==null)Color(0xFF865C00) else Color(0xFF087F43))
                    }}
                }
                Text(if(missingDishes.isNotEmpty()||invalid>0)"Partial shopping list · missing recipes remain" else "Shopping list · reviewed shortages",fontSize=17.sp)
                val unknown=needs.count{confirmed[shoppingKey(it.item)+"|"+it.unit]!=true}
                Text("$unknown ingredients still need a stock check. Prices and packet sizes vary; no purchase or expense is saved here.",fontSize=12.sp)
                needs.forEach{n->val k=shoppingKey(n.item)+"|"+n.unit;val gap=shoppingShortfall(n.quantity,if(confirmed[k]==true)amounts[k]?.toBigDecimalOrNull() else null,n.unit);if(gap!=null&&gap>BigDecimal.ZERO)Text("• ${n.item}: ${recipeQuantity(gap,n.unit)}",fontSize=14.sp)}
                OutlinedButton({inventory=true},enabled=!loading,modifier=Modifier.fillMaxWidth()){Text("Record a physical stock check")}
                OutlinedButton({recipesOpen=true},enabled=!loading,modifier=Modifier.fillMaxWidth()){Text("Manage recipes")}
                Text("Plan confirmations are temporary and clear on refresh, date/meal changes or closing. Saved stock reports and accounts remain unchanged.",fontSize=11.sp)
                Button(onClose,modifier=Modifier.fillMaxWidth()){Text("Done")}
            }
        }
    }
}
