package com.zomeal.provider

import android.content.Context
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode

@Composable private fun RecipeChoice(value:String, choices:List<String>, enabled:Boolean, onChoose:(String)->Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.width(145.dp).padding(3.dp)) {
        OutlinedButton({expanded=true},enabled=enabled,modifier=Modifier.fillMaxWidth()){Text((value.ifBlank{"Choose"})+" ▾",fontSize=12.sp)}
        DropdownMenu(expanded,{expanded=false}) { choices.forEach{choice -> DropdownMenuItem(text={Text(if(choice=="batch") "Full batch cost" else choice)},onClick={onChoose(choice);expanded=false})} }
    }
}

/** Account-scoped device recipe notebook. Never writes purchases or stock. */
@Composable internal fun BikiRecipeSheet(repository: SupabaseProviderRepository, onClose: () -> Unit) {
    val context=LocalContext.current
    val account=context.getSharedPreferences("zomeal_provider_session",Context.MODE_PRIVATE).getString("user_id","").orEmpty()
    val prefs=remember { context.getSharedPreferences("biki_recipes",Context.MODE_PRIVATE) }
    val key="recipes_$account"
    var saved by remember(key) { mutableStateOf(prefs.getString(key,"[]")?:"[]") }
    var id by remember { mutableStateOf(java.util.UUID.randomUUID().toString()) }
    var name by remember { mutableStateOf("") }
    var batch by remember { mutableStateOf("9") }
    var target by remember { mutableStateOf("120") }
    var draft by remember { mutableStateOf("[]") }
    var voice by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var revision by remember { mutableStateOf(0) }
    var menuId by remember { mutableStateOf("") }
    var menuSearch by remember { mutableStateOf("") }
    var choosingMenu by remember { mutableStateOf(false) }
    var cloud by remember { mutableStateOf<JSONObject?>(null) }
    var busy by remember { mutableStateOf(false) }
    val alive=remember { java.util.concurrent.atomic.AtomicBoolean(true) }
    DisposableEffect(Unit){ onDispose { alive.set(false) } }
    fun refresh(){busy=true;repository.recipeBook { result,error -> if(alive.get()){busy=false;cloud=result;message=error?:"Cloud recipes loaded";if(result!=null)saved=result.optJSONArray("recipes")?.toString()?:"[]"} }}
    fun load(r:JSONObject){id=r.getString("id");name=r.getString("name");batch=r.getInt("batch").toString();draft=r.getJSONArray("rows").toString();revision=r.optInt("revision",0);menuId=if(r.isNull("menu_item_id")) "" else r.optString("menu_item_id");message="Recipe loaded"}
    LaunchedEffect(account){refresh()}
    val rows=JSONArray(draft)
    val b=batch.toIntOrNull()?.takeIf { it in 1..10000 }
    val t=target.toIntOrNull()?.takeIf { it in 1..10000 }
    val units=listOf("kg","g","litre","ml","piece")
    fun number(s:String)=s.toBigDecimalOrNull()?.takeIf { it>=BigDecimal.ZERO && it<=BigDecimal("1000000") }
    val menus=cloud?.optJSONArray("menus")?:JSONArray()
    val selectedMenu=(0 until menus.length()).map{menus.getJSONObject(it)}.find{it.getString("id")==menuId}
    fun rowCost(r:JSONObject)=recipeBatchCost(r.optString("quantity"),r.optString("unit"),r.optString("entered_price"),r.optString("price_basis"),r.optString("pack_quantity"),r.optString("pack_unit"))
    val valid=selectedMenu!=null && b!=null && rows.length() in 1..30 && (0 until rows.length()).all { i ->
        val r=rows.getJSONObject(i)
        r.optString("item").isNotBlank() && r.optString("item").length<=100 && r.optString("unit") in units &&
            number(r.optString("quantity"))?.let{it>BigDecimal.ZERO}==true &&
            r.has("price_basis") && (r.optString("entered_price").isBlank() || (number(r.optString("entered_price"))!=null && runCatching{rowCost(r)}.isSuccess))
    }
    fun edit(i:Int,k:String,v:String){val a=JSONArray(draft);a.getJSONObject(i).put(k,v);draft=a.toString();message=""}
    if(voice) KitchenVoiceDialog(repository,"RECIPE", "Recipe ingredients · confirm batch portions separately",{voice=false},{a ->
        val next=JSONArray(draft)
        for(i in 0 until a.length()) if(next.length()<30) { val r=a.getJSONObject(i);val u=r.optString("unit");next.put(JSONObject().put("item",r.optString("item")).put("quantity",r.optString("quantity")).put("unit",u).put("price","").put("entered_price","").put("price_basis",when(u){"g","kg"->"kg";"ml","litre"->"litre";else->"piece"})) }
        draft=next.toString();voice=false
    },quantityOnly=true)
    if(!voice) Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.ime)),color=Color(0xFFF5FAF6)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp).padding(bottom=56.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Text("Teach BIKI · Recipe sheet",fontSize=22.sp,color=Color(0xFF087F43))
                Text("Recipes for every dish in your active package menu: main courses, rice, roti, dal and included sides. Choose the menu dish; meal plans match by dish ID. No inventory or expenses are booked.",fontSize=12.sp)
                TextButton({refresh()},enabled=!busy){Text("Refresh cloud recipes")}
                if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                val recipes=runCatching{JSONArray(saved)}.getOrDefault(JSONArray())
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    TextButton({id=java.util.UUID.randomUUID().toString();name="";draft="[]";revision=0;menuId="";message=""},enabled=!busy) {Text("+ New")}
                    for(i in 0 until recipes.length()){val r=recipes.getJSONObject(i);TextButton({load(r)},enabled=!busy) {Text(r.getString("name"))}}
                }
                OutlinedTextField(if(choosingMenu) menuSearch else selectedMenu?.optString("name").orEmpty(),{menuSearch=it;choosingMenu=true},enabled=!busy,label={Text("Search & select your menu dish ▾")},singleLine=true,modifier=Modifier.fillMaxWidth())
                TextButton({choosingMenu=!choosingMenu;menuSearch=""},enabled=!busy){Text("Choose from my menu")}
                if(choosingMenu) Surface(color=Color.White,shape=MaterialTheme.shapes.medium) {
                    Column(Modifier.heightIn(max=220.dp).verticalScroll(rememberScrollState())) {
                        val matches=(0 until menus.length()).map{menus.getJSONObject(it)}.filter{it.getString("name").contains(menuSearch,true)}
                        if(matches.isEmpty()) Text("No matching approved menu dish. Update your menu first.",Modifier.padding(12.dp),fontSize=12.sp)
                        matches.forEach { m -> TextButton({
                            val existing=(0 until recipes.length()).map{recipes.getJSONObject(it)}.find{it.optString("menu_item_id")==m.getString("id")}
                            if(existing!=null) load(existing) else {menuId=m.getString("id");name=m.getString("name")}
                            choosingMenu=false;menuSearch=""
                        },modifier=Modifier.fillMaxWidth()){Text(m.getString("name"))} }
                    }
                }
                Text("Upcoming linked dishes · includes main courses and saved sides. Excludes paused, cancelled and delivered meals. Refresh after order changes.",fontSize=12.sp)
                val plans=cloud?.optJSONArray("plans")?:JSONArray()
                for(i in 0 until plans.length()){val plan=plans.getJSONObject(i);TextButton({for(j in 0 until recipes.length()){val r=recipes.getJSONObject(j);if(r.getString("id")==plan.getString("recipe_id")){load(r);target=plan.getInt("portions").toString()}}},enabled=!busy){Text("${plan.getString("service_date")} ${plan.getString("meal_slot")} · ${plan.getString("name")} · ${plan.getInt("portions")} portions")}}
                OutlinedTextField(batch,{batch=it;message=""},enabled=!busy,label={Text("This batch feeds how many people?")},singleLine=true,modifier=Modifier.fillMaxWidth())
                Text("One portion means one person's serving of this dish. For 2 rotis per person, enter ingredients for 18 rotis and set the batch to 9 people—not 18. Rice quantities are raw rice used for that many people.",fontSize=12.sp)
                Text("Enter ingredients for this batch. Choose ₹ per kg, litre, piece or packet—or enter the full batch ingredient cost. Example: 250 g onion at ₹20/kg costs ₹5. Never convert oil grams to litres without measuring it.",fontSize=12.sp)
                OutlinedButton({voice=true},enabled=rows.length()<30&&!busy&&selectedMenu!=null,modifier=Modifier.fillMaxWidth()){Text("Speak recipe ingredients")}
                Text("Voice requires the updated RECIPE backend. Review ingredients, batch size and prices before saving.",fontSize=11.sp)
                Column(Modifier.horizontalScroll(rememberScrollState())) {
                    Row(Modifier.background(Color(0xFFE2F1E7))) {listOf("Raw ingredient","Batch quantity","Unit","Price ₹","Price basis","Packet size","Packet unit").forEach{Text(it,Modifier.width(145.dp).padding(8.dp),fontSize=12.sp)}}
                    for(i in 0 until rows.length()){val r=rows.getJSONObject(i);Row {
                        listOf("item","quantity").forEach { f -> OutlinedTextField(r.optString(f),{edit(i,f,it)},enabled=!busy,singleLine=true,modifier=Modifier.width(145.dp).padding(3.dp)) }
                        RecipeChoice(r.optString("unit"),units,!busy){edit(i,"unit",it)}
                        OutlinedTextField(r.optString("entered_price"),{edit(i,"entered_price",it)},enabled=!busy,singleLine=true,modifier=Modifier.width(145.dp).padding(3.dp))
                        RecipeChoice(r.optString("price_basis"),listOf("kg","litre","piece","packet","batch"),!busy){edit(i,"price_basis",it)}
                        if(r.optString("price_basis")=="packet") {
                            OutlinedTextField(r.optString("pack_quantity"),{edit(i,"pack_quantity",it)},enabled=!busy,singleLine=true,modifier=Modifier.width(145.dp).padding(3.dp))
                            RecipeChoice(r.optString("pack_unit"),units,!busy){edit(i,"pack_unit",it)}
                        }
                        TextButton({val a=JSONArray(draft);a.remove(i);draft=a.toString();message=""},enabled=!busy) {Text("Remove")}
                    }}
                }
                Text("Choose a price basis for every row. Existing prices must be reviewed and re-entered once. Packet prices require packet size and unit. Blank price stays unknown, not zero.",fontSize=11.sp)
                TextButton({val a=JSONArray(draft);a.put(JSONObject().put("item","").put("quantity","").put("unit","kg").put("price","").put("entered_price","").put("price_basis","kg"));draft=a.toString()},enabled=rows.length()<30&&!busy&&selectedMenu!=null){Text("+ Ingredient")}
                Button({busy=true;val normalized=JSONArray(rows.toString());for(i in 0 until normalized.length()){val r=normalized.getJSONObject(i);r.put("price",rowCost(r)?.divide(r.getString("quantity").toBigDecimal(),12,RoundingMode.HALF_UP)?.toPlainString()?:"")};val payload=JSONObject().put("id",id).put("name",selectedMenu!!.getString("name")).put("batch",b).put("rows",normalized).put("revision",revision).put("menu_item_id",menuId);repository.saveRecipe(payload){result,error -> if(alive.get()){busy=false;if(result!=null){load(result);refresh()}else message=error?:"Cloud save failed; keep this sheet open and retry"}}},enabled=valid&&account.isNotBlank()&&!busy&&cloud!=null,modifier=Modifier.fillMaxWidth()){Text("Save reviewed recipe to cloud")}
                if(message.isNotBlank())Text(message)
                OutlinedTextField(target,{target=it},label={Text("Plan portions · e.g. 120")},singleLine=true,modifier=Modifier.fillMaxWidth())
                if(valid && b!=null && t!=null) {
                    var total=BigDecimal.ZERO;var missing=0
                    Text("Ingredient plan · $t portions",color=Color(0xFF087F43))
                    Text("Your batch serves $b people. Each quantity and batch ingredient cost is multiplied by $t ÷ $b. Display quantities are rounded; totals use full precision.",fontSize=12.sp)
                    for(i in 0 until rows.length()) {
                        val r=rows.getJSONObject(i);val q=recipeScale(r.getString("quantity"),b,t);val cost=rowCost(r)?.multiply(t.toBigDecimal())?.divide(b.toBigDecimal(),12,RoundingMode.HALF_UP)
                        if(cost==null)missing++ else total=total.add(cost)
                        Text("${r.getString("item")}: ${recipeQuantity(q,r.getString("unit"))} · ${if(cost==null) "price needed" else "₹"+cost.setScale(2,RoundingMode.HALF_UP)}\nPer person: ${recipeQuantity(recipeScale(r.getString("quantity"),b,1),r.getString("unit"))}",fontSize=13.sp)
                    }
                    Text("${if(missing>0) "Known ingredient subtotal" else "Ingredient total"}: ₹${total.setScale(2,RoundingMode.HALF_UP)} · $missing missing prices")
                    Text("Linear estimate only. Round whole eggs up for purchasing; review spices/oil at larger scale. Excludes gas, labour, rent, delivery and waste. This does not deduct inventory.",fontSize=12.sp)
                }
                OutlinedButton(onClose,modifier=Modifier.fillMaxWidth()){Text("Close")}
            }
        }
    }
}
