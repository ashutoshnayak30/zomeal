package com.zomeal.provider

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.json.JSONObject
import java.math.BigDecimal
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Locale

private val ReviewGreen=Color(0xFF087F43)
private val ReviewRed=Color(0xFFAD3C35)
private val ReviewTint=Color(0xFFEAF5EE)
private fun reviewMoney(p:Long)=NumberFormat.getCurrencyInstance(Locale("en","IN")).format(BigDecimal(p).movePointLeft(2))
private fun moneyInput(p:Long)=BigDecimal(p).movePointLeft(2).toPlainString()
private fun monthName(book:JSONObject)=runCatching{SimpleDateFormat("MMMM yyyy",Locale.ENGLISH).format(SimpleDateFormat("yyyy-MM-dd",Locale.ENGLISH).parse(book.getString("month"))!!)}.getOrDefault(book.optString("month").take(7))
private val ReviewChecks=linkedMapOf("ingredients" to "Ingredient purchases complete", "Rent" to "Rent", "Staff" to "Salaries", "Utilities" to "Electricity & water", "Gas & fuel" to "Gas / fuel", "Delivery" to "Delivery", "Packaging" to "Packaging", "Cleaning" to "Cleaning", "Other" to "Other costs · no duplicate commission", "scope" to "Costs match the income recorded here")

@Composable private fun ReviewCard(title:String,content:@Composable ColumnScope.()->Unit){
 Surface(color=Color.White,shape=RoundedCornerShape(18.dp)){Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){Text(title,fontSize=14.sp,fontWeight=FontWeight.Bold);content()}}
}
@Composable private fun ReviewLine(label:String,value:Long,color:Color=Color(0xFF34493D)){
 Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)){Text(label,Modifier.weight(1f),fontSize=12.sp,color=Color(0xFF637268));Text(reviewMoney(value),fontSize=13.sp,fontWeight=FontWeight.SemiBold,color=color)}
}

@Composable internal fun KitchenStatementCard(book:JSONObject,onReview:()->Unit){
 val s=book.optJSONObject("statement")?:return
 val r=s.optJSONObject("review")
 val profit=s.optLong("profit_to_date_paise",s.optLong("profit_paise"))
 val costs=s.optLong("costs_to_date_paise",s.optLong("operating_costs_paise"))
 val fixed=s.optLong("monthly_overhead_paise",s.optLong("fixed_paise"))
 val allocated=s.optLong("allocated_fixed_paise",fixed)
 val current=s.optBoolean("is_current_month")
 val trend=book.optJSONArray("trend");val other=trend?.optJSONObject(trend.length()-1)?.optLong("other_sales_paise")?:0
 var details by remember{mutableStateOf(false)};var stockDetails by remember{mutableStateOf(false)}
 Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
  Surface(color=if(profit<0)Color(0xFFFFEFEB) else ReviewTint,shape=RoundedCornerShape(22.dp)){
   Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
    Row(verticalAlignment=Alignment.CenterVertically){Icon(Icons.Outlined.Assessment,null,Modifier.size(22.dp),tint=ReviewGreen);Spacer(Modifier.width(8.dp));Text(monthName(book),fontSize=14.sp,fontWeight=FontWeight.Bold)}
    Text(if(current)"Estimated profit / loss so far" else "Estimated month result",fontSize=12.sp,color=Color.DarkGray)
    Text(reviewMoney(profit),fontSize=30.sp,fontWeight=FontWeight.Bold,color=if(profit<0)ReviewRed else ReviewGreen)
    Text(if(current)"Includes ${s.optInt("elapsed_days")} of ${s.optInt("days_in_month")} days of monthly overhead" else "Full month's income and operating costs",fontSize=11.sp,color=Color.DarkGray)
    Surface(color=Color.White.copy(alpha=.7f),shape=RoundedCornerShape(8.dp)){Text(when(s.optString("status")){"REVIEWED"->"✓ Month reviewed";"NEEDS_REVIEW"->"Figures changed · review again";else->"Estimate · stock check improves accuracy"},Modifier.padding(8.dp),fontSize=11.sp,color=ReviewGreen)}
   }
  }
  Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){StatementStat("Net earnings",s.optLong("net_paise"),Modifier.weight(1f),ReviewGreen);StatementStat(if(current)"Costs so far" else "Operating costs",costs,Modifier.weight(1f),ReviewRed)}
  ReviewCard("How your result is calculated"){
   ReviewLine("Net Zomeal earnings",s.optLong("net_paise"),ReviewGreen)
   if(other!=0L)ReviewLine("Previously recorded other income",other)
   ReviewLine(if(s.optBoolean("stock_entered")&&s.optBoolean("stock_valid"))"Daily costs · stock adjusted" else "Daily costs · purchases",costs-allocated,ReviewRed)
   ReviewLine("Monthly overhead used so far",allocated,ReviewRed)
   HorizontalDivider();ReviewLine("Estimated result",profit,if(profit<0)ReviewRed else ReviewGreen)
   TextButton(onClick={details=!details}){Text(if(details)"Hide earnings breakdown" else "Show sales, commission & refunds",fontSize=12.sp)}
   if(details){ReviewLine("Gross meal sales",s.optLong("gross_paise"));ReviewLine("Commission",-s.optLong("commission_paise"));ReviewLine("Refunds / adjustments",s.optLong("adjustments_paise"))}
  }
  ReviewCard("Monthly costs, spread fairly"){
   ReviewLine("Monthly commitment",fixed);Text("${reviewMoney(fixed)} ÷ ${s.optInt("days_in_month")} days",fontSize=12.sp,color=Color.Gray)
   ReviewLine("Per-day share",s.optLong("fixed_per_day_paise"),ReviewGreen);ReviewLine("${s.optInt("elapsed_days")} days counted",allocated)
   Text("No extra charge is created. Paid bills stay in cash records; only the daily share is used in profit so far.",fontSize=11.sp,color=Color.Gray)
  }
  ReviewCard("Ingredients left in your kitchen"){
   Text(if(!s.optBoolean("stock_valid"))"Purchases changed. Please check stock again." else if(!s.optBoolean("stock_entered"))"Not checked yet. Count what you had at the start and what remains." else "Last saved: ${r?.optString("updated_at")?.take(10)}",fontSize=12.sp,color=Color.DarkGray)
   Button(onClick=onReview,modifier=Modifier.fillMaxWidth()){Icon(Icons.Outlined.Inventory2,null,Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text("Check ingredient stock",fontSize=12.sp)}
   TextButton(onClick={stockDetails=!stockDetails}){Text(if(stockDetails)"Hide stock calculation" else "See stock calculation",fontSize=12.sp)}
   if(stockDetails){ReviewLine("At start of month",r?.optLong("opening_stock_paise")?:0);ReviewLine("+ Purchases",s.optLong("food_purchases_paise"));ReviewLine("− Remaining",-(r?.optLong("closing_stock_paise")?:0));ReviewLine("Ingredients used",s.optLong("food_used_paise"))}
  }
  ReviewCard("Payments—not profit"){
   ReviewLine("Payouts received this month",book.optLong("payouts_received_paise"));ReviewLine("Unsettled ledger · all months",s.optLong("unsettled_paise"))
   Text("Includes held earnings; see Earnings for withdrawable balance.",fontSize=11.sp,color=Color.Gray)
  }
  Text("Management estimate, not an audited/tax statement. Without a stock check, ingredient costs follow purchases. Use the download icon above for your statement.",fontSize=11.sp,color=Color.Gray)
 }
}
@Composable private fun StatementStat(label:String,value:Long,modifier:Modifier,color:Color){Surface(modifier,color=Color.White,shape=RoundedCornerShape(16.dp)){Column(Modifier.padding(14.dp)){Text(label,fontSize=11.sp,color=Color.Gray);Text(reviewMoney(value),fontSize=19.sp,fontWeight=FontWeight.Bold,color=color)}}}

@Composable internal fun KitchenReviewDialog(book:JSONObject,onDismiss:()->Unit,onSave:(JSONObject,(String?)->Unit)->Unit,repository:SupabaseProviderRepository?=null,initialVoiceStep:Int?=null){
 val s=book.getJSONObject("statement");val review=s.optJSONObject("review")
 var opening by rememberSaveable { mutableStateOf(review?.optLong("opening_stock_paise")?.let(::moneyInput).orEmpty()) }
 var closing by rememberSaveable { mutableStateOf(review?.optLong("closing_stock_paise")?.let(::moneyInput).orEmpty()) }
 var openingRows by rememberSaveable { mutableStateOf(review?.optJSONArray("opening_rows")?.toString()?:"[]") }
 var checks by rememberSaveable { mutableStateOf(review?.optJSONObject("checks")?.toString()?:"{}") }
 var step by rememberSaveable { mutableIntStateOf(initialVoiceStep?:0) };var showChecks by rememberSaveable { mutableStateOf(false) }
 var busy by remember { mutableStateOf(false) };var error by remember{mutableStateOf<String?>(null)};var discard by remember{mutableStateOf(false)}
 var voiceStep by remember { mutableStateOf(initialVoiceStep) }
 voiceStep?.let { selected ->
  if(repository!=null)KitchenVoiceDialog(repository,if(selected==0)"OPENING_STOCK" else "CLOSING_STOCK",monthName(book),onDismiss={voiceStep=null},onApply={rows->
   val total=(0 until rows.length()).sumOf{kitchenPaise(rows.getJSONObject(it).optString("amount"))?:0L}
   if(selected==0){opening=moneyInput(total);openingRows=rows.toString()} else closing=moneyInput(total)
   voiceStep=null;error=null
  },openingStockRows=openingRows)
 }
 val checked=JSONObject(checks);val op=kitchenPaise(opening);val cl=kitchenPaise(closing)
 val valid=op!=null&&cl!=null&&cl<=op+s.optLong("food_purchases_paise")
 fun close(){if(!busy)discard=true}
 fun save(finalize:Boolean){
  if(!valid){error="Enter both values (0 is allowed). Remaining stock cannot exceed opening stock plus purchases.";return}
  busy=true;error=null
  onSave(JSONObject().put("month",book.getString("month")).put("opening_stock_paise",op).put("closing_stock_paise",cl).put("opening_rows",org.json.JSONArray(openingRows)).put("checks",checked).put("revision",review?.optInt("revision")?:0).put("source_signature",s.getString("source_signature")).put("close",finalize)){problem->busy=false;error=problem}
 }
 // Do not open a second native window over the voice recorder.
 if(voiceStep==null || repository==null)Dialog(onDismissRequest=::close,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)){
  Scaffold(modifier=Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.ime)),contentWindowInsets=WindowInsets(0,0,0,0),containerColor=Color(0xFFF7FAF7),
   topBar={Surface(color=Color.White){Row(Modifier.fillMaxWidth().padding(8.dp),verticalAlignment=Alignment.CenterVertically){IconButton(onClick=::close,enabled=!busy){Icon(Icons.Outlined.Close,"Close stock check")};Column{Text("Ingredient stock check",fontSize=18.sp,fontWeight=FontWeight.Bold);Text(monthName(book),fontSize=11.sp,color=ReviewGreen)}}}}
  ){padding->val scroll=rememberScrollState();LaunchedEffect(step){scroll.scrollTo(0)};Column(Modifier.fillMaxSize().padding(padding).verticalScroll(scroll).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
   Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){listOf("1 Start","2 Remaining","3 Review").forEachIndexed{i,label->Surface(color=if(step==i)ReviewGreen else ReviewTint,shape=RoundedCornerShape(10.dp),modifier=Modifier.weight(1f)){Text(label,Modifier.padding(9.dp),fontSize=11.sp,color=if(step==i)Color.White else ReviewGreen)}}}
   error?.let{Text(it,color=ReviewRed,fontSize=12.sp)}
   Text("Step ${step+1} of 3 · ${if(step==0)"Enter what you had on the 1st" else if(step==1)"Enter ingredients still unused" else "Check the totals and save"}",fontWeight=FontWeight.SemiBold,fontSize=13.sp,color=ReviewGreen)
   if(step<2){
    val start=step==0
    ReviewCard(if(start)"What did you already have?" else if(s.optBoolean("can_close"))"What was left on the last day?" else "What is left right now?"){
     Text(if(start)"Count ingredients you already owned on the 1st. Do not include purchases made later this month." else "Count unused ingredients—not food already cooked or sold.",fontSize=12.sp)
     Text("Include rice, atta, dal, vegetables, oil, spices, meat and dairy. Leave out rent, gas and equipment.",fontSize=11.sp,color=Color.Gray)
     OutlinedTextField(if(start)opening else closing,{if(start){opening=it;openingRows="[]"} else closing=it},enabled=!busy,label={Text(if(start)"Total starting stock value ₹" else "Total remaining stock value ₹",fontSize=12.sp)},singleLine=true,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(12.dp),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal))
     Text("${org.json.JSONArray(openingRows).length()} opening items with reusable prices. Save the stock check to keep them across devices. Editing the opening total manually clears item-price details.",fontSize=11.sp,color=ReviewGreen)
     Text("Use the price you paid, not your meal selling price.",fontSize=11.sp,color=ReviewGreen)
     if(repository?.voiceConfigured==true)OutlinedButton(onClick={voiceStep=step},enabled=!busy,modifier=Modifier.fillMaxWidth()){Icon(Icons.Outlined.Mic,null,Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text(if(start)"Speak opening stock" else "Speak remaining stock",fontSize=12.sp)}
     OutlinedButton(enabled=!busy,onClick={if(start){opening="0";openingRows="[]"} else closing="0"},modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(10.dp)){Text(if(start)"No starting stock · use ₹0" else "No stock remaining · use ₹0",fontSize=12.sp)}
     if(start&&!s.isNull("suggested_opening_paise"))OutlinedButton(onClick={opening=moneyInput(s.optLong("suggested_opening_paise"));openingRows="[]"},enabled=!busy){Text("Use last month: ${reviewMoney(s.optLong("suggested_opening_paise"))}",fontSize=11.sp)}
    }
    key(step){StockValueHelper(enabled=!busy,onAdd={amount->val current=kitchenPaise(if(start)opening else closing)?:0;if(current+amount<=100000000){if(start){opening=moneyInput(current+amount);openingRows="[]"} else closing=moneyInput(current+amount);error=null;true}else{error="Stock total is too large.";false}})}
    Surface(color=ReviewTint,shape=RoundedCornerShape(12.dp)){Text("Example: 5 kg rice at ₹50/kg = ₹250 of stock. Add each ingredient's value to find your total.",Modifier.padding(12.dp),fontSize=12.sp,color=ReviewGreen)}
   }else{
    ReviewCard("Your ingredient cost"){
     ReviewLine("Starting stock",op?:0);ReviewLine("+ Purchases recorded",s.optLong("food_purchases_paise"));ReviewLine("− Remaining stock",-(cl?:0));HorizontalDivider();ReviewLine("Ingredients used",(op?:0)+s.optLong("food_purchases_paise")-(cl?:0),ReviewGreen)
     Text("Monthly costs are spread separately across the month's days.",fontSize=11.sp,color=Color.Gray)
    }
    ReviewCard("Finish the month when you're ready"){
     Text(if(s.optBoolean("can_close"))"Save your stock check now, or check costs below to mark the month reviewed." else "Save now. Update stock whenever needed; final review opens after month-end.",fontSize=12.sp)
     TextButton(onClick={showChecks=!showChecks}){Text(if(showChecks)"Hide cost checklist" else "Check monthly completeness",fontSize=12.sp)}
     if(showChecks){
      Text("Tick after confirming the full cost is entered, or that it does not apply.",fontSize=11.sp,color=Color.Gray)
      ReviewChecks.forEach{(k,label)->val amount=if(k=="ingredients")s.optLong("food_purchases_paise") else s.optJSONObject("categories")?.optLong(k)?:0
       Row(verticalAlignment=Alignment.CenterVertically){Checkbox(checked.optBoolean(k),{checks=JSONObject(checks).put(k,it).toString()},enabled=!busy);Column(Modifier.weight(1f)){Text(label,fontSize=12.sp);if(k!="scope")Text(if(amount==0L)"Not entered · or not applicable" else "Recorded ${reviewMoney(amount)}",fontSize=11.sp,color=Color.Gray)}}
      }
      Button(onClick={save(true)},enabled=!busy&&s.optBoolean("can_close")&&ReviewChecks.keys.all{checked.optBoolean(it)},modifier=Modifier.fillMaxWidth()){Text("Save & mark month reviewed",fontSize=12.sp)}
     }
    }
   }
   if(error!=null)Text(error!!,color=ReviewRed,fontSize=12.sp)
   Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
    if(step>0)OutlinedButton(onClick={step--;error=null},enabled=!busy){Text("Back")}
    Button(onClick={if(step<2){if((step==0&&op==null)||(step==1&&!valid)){error="Enter a valid stock value; remaining stock cannot exceed starting stock plus recorded purchases."}else{step++;error=null}}else save(false)},enabled=!busy,modifier=Modifier.weight(1f).heightIn(min=48.dp),shape=RoundedCornerShape(12.dp)){Text(if(busy)"Saving…" else if(step==0)"Next: remaining stock" else if(step==1)"Next: review totals" else "Save stock check",fontSize=13.sp)}
   }
   Text("This adjusts ingredient costs only. It does not deduct money or create purchases.",fontSize=11.sp,color=Color.Gray)
   Spacer(Modifier.height(48.dp))
  }}
 }
 if(discard)AlertDialog(onDismissRequest={discard=false},title={Text("Leave stock check?")},text={Text("Changes not saved will be lost. If a save failed, refresh the month before trying again.")},confirmButton={TextButton(onClick=onDismiss){Text("Leave")}},dismissButton={TextButton(onClick={discard=false}){Text("Keep editing")}})
}

@Composable private fun StockValueHelper(enabled:Boolean,onAdd:(Long)->Boolean){
 var expanded by rememberSaveable{mutableStateOf(true)};var quantity by rememberSaveable{mutableStateOf("")};var rate by rememberSaveable{mutableStateOf("")};var added by remember{mutableStateOf<String?>(null)}
 ReviewCard("Optional: calculate one ingredient"){
  Text("Already entered the total above? Skip this calculator to avoid counting stock twice.",fontSize=11.sp,color=Color.Gray)
  OutlinedButton(onClick={expanded=!expanded}){Text(if(expanded)"Hide calculator" else "Open quantity × price calculator",fontSize=12.sp)}
  if(expanded){
   Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
    OutlinedTextField(quantity,{quantity=it},enabled=enabled,label={Text("Quantity",fontSize=11.sp)},singleLine=true,modifier=Modifier.weight(1f),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal))
    OutlinedTextField(rate,{rate=it},enabled=enabled,label={Text("₹ per kg / unit",fontSize=11.sp)},singleLine=true,modifier=Modifier.weight(1f),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal))
   }
   val value=kitchenStockValue(quantity,rate)
   Text("Use matching units: kg × price/kg, or litres × price/litre.",fontSize=11.sp,color=Color.Gray)
   OutlinedButton(onClick={value?.let{if(onAdd(it)){added="Added ${reviewMoney(it)} to the total above. Enter the next ingredient if needed.";quantity="";rate=""}}},enabled=enabled&&value!=null){Text(if(value==null)"Add ingredient value" else "+ Add ${reviewMoney(value)} to total",fontSize=12.sp)}
   added?.let{Text(it,fontSize=11.sp,color=ReviewGreen)}
  }
 }
}

internal fun kitchenStatementCsv(book:JSONObject):String {
 val s=book.optJSONObject("statement")?:JSONObject();val review=s.optJSONObject("review")
 val lines=mutableListOf(listOf("Zomeal kitchen management statement",book.optString("month")),listOf("Status",s.optString("status")),listOf("Days counted",s.optInt("elapsed_days").toString()+" / "+s.optInt("days_in_month")),listOf("Item","INR"))
 fun amount(label:String,value:Long){lines.add(listOf(label,moneyInput(value)))}
 amount("Gross meal sales",s.optLong("gross_paise"));amount("Commission deducted",-s.optLong("commission_paise"));amount("Refunds / adjustments",s.optLong("adjustments_paise"));amount("Net Zomeal earnings",s.optLong("net_paise"))
 val trend=book.optJSONArray("trend");val other=trend?.optJSONObject(trend.length()-1)?.optLong("other_sales_paise")?:0
 if(other!=0L)amount("Previously recorded other income",other)
 amount("Opening ingredient stock",review?.optLong("opening_stock_paise")?:0);amount("Ingredient purchases",s.optLong("food_purchases_paise"));amount("Remaining ingredient stock",review?.optLong("closing_stock_paise")?:0);amount("Ingredients used",s.optLong("food_used_paise"))
 amount("Full monthly overhead",s.optLong("monthly_overhead_paise"));amount("Daily overhead share (rounded)",s.optLong("fixed_per_day_paise"));amount("Overhead allocated through today",s.optLong("allocated_fixed_paise"));amount("Operating costs through today",s.optLong("costs_to_date_paise",s.optLong("operating_costs_paise")));amount("Estimated profit through today",s.optLong("profit_to_date_paise",s.optLong("profit_paise")))
 amount("Costs including full-month commitments",s.optLong("operating_costs_paise"));amount("Previous full month estimate",s.optLong("previous_profit_paise"))
 val categories=s.optJSONObject("categories")?:JSONObject();categories.keys().asSequence().sorted().forEach{amount("Recorded purchases / bills: $it",categories.optLong(it))}
 amount("Payouts received this month",book.optLong("payouts_received_paise"));amount("Current signed unsettled ledger balance (all months)",s.optLong("unsettled_paise"))
 val entries=book.optJSONArray("entries");val expenses=(0 until (entries?.length()?:0)).map{entries!!.getJSONObject(it)}.filter{it.optString("kind")=="EXPENSE"}
 amount("Expenses paid (including excluded capital/owner entries)",expenses.filter{it.optBoolean("paid")&&!it.optBoolean("staff_managed")}.sumOf{it.optLong("amount_paise")}+book.optLong("staff_cash_paid_paise"));amount("Unpaid expenses recorded",expenses.sumOf{it.optLong("amount_paise")-it.optLong("paid_amount_paise",if(it.optBoolean("paid"))it.optLong("amount_paise") else 0)})
 lines.add(listOf("Notes","Stock uses purchase prices. Category rows show full original purchases/bills, not extra costs. Overhead is allocated across calendar days; no extra cash entry. Automatic monthly copies are unpaid estimates. Without stock figures, ingredient costs follow purchases. Excludes depreciation/tax adjustments; not audited."))
 return "\uFEFF"+lines.joinToString("\r\n"){it.joinToString(",",transform=::kitchenCsvCell)}+"\r\n"
}
