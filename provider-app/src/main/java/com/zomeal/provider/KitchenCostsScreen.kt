package com.zomeal.provider

import android.app.DatePickerDialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.math.BigDecimal
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

private val KGreen=Color(0xFF087F43)
private val KRed=Color(0xFFB33C36)
private val KMuted=Color(0xFF68736D)
private val KBackground=Color(0xFFF7FAF7)
private val KCategories=listOf("Grains","Vegetables","Meat & eggs","Oil & spices","Dairy","Gas & fuel","Packaging","Delivery","Staff","Rent","Utilities","Cleaning","Other","Equipment / owner / loan")
private fun bookRows(json:JSONObject?,key:String):List<JSONObject> = json?.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
private fun rupees(paise:Long)=NumberFormat.getCurrencyInstance(Locale("en","IN")).apply{maximumFractionDigits=2}.format(BigDecimal(paise).movePointLeft(2))
private fun bookCalendar()=Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"))
private fun bookDate(c:Calendar,pattern:String)=SimpleDateFormat(pattern,Locale.ENGLISH).apply{timeZone=c.timeZone}.format(c.time)

@Composable internal fun KitchenCostsScreen(repository:SupabaseProviderRepository,onDashboard:()->Unit,onOrders:()->Unit,onEarnings:()->Unit,onProfile:()->Unit){
 MaterialTheme(colorScheme=lightColorScheme(primary=KGreen,onPrimary=Color.White,secondary=KGreen,secondaryContainer=Color(0xFFE5F2E9),onSecondaryContainer=KGreen,surface=Color.White,surfaceContainerHigh=Color.White,background=KBackground)) {
  KitchenCostsContent(repository,onDashboard,onOrders,onEarnings,onProfile)
 }
}

@Composable private fun KitchenCostsContent(repository:SupabaseProviderRepository,onDashboard:()->Unit,onOrders:()->Unit,onEarnings:()->Unit,onProfile:()->Unit){
 var offset by remember { mutableIntStateOf(0) }
 val month=remember(offset){bookCalendar().apply{set(Calendar.DAY_OF_MONTH,1);add(Calendar.MONTH,offset)}}
 val monthKey=bookDate(month,"yyyy-MM-dd")
 var data by remember { mutableStateOf<JSONObject?>(null) }
 var loading by remember { mutableStateOf(true) }
 var error by remember { mutableStateOf<String?>(null) }
 var editor by remember { mutableStateOf<JSONObject?>(null) }
 var showSheet by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
 var sheetMonthly by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
 var voidEntry by remember { mutableStateOf<JSONObject?>(null) }
 var tab by remember { mutableIntStateOf(0) }
 var showGuide by remember { mutableStateOf(false) }
 var showReview by remember { mutableStateOf(false) }
 var showStaff by remember { mutableStateOf(false) }
 var showAttendance by remember { mutableStateOf(false) }
 var showDemo by remember { mutableStateOf(false) }
 var manjuDemoAvailable by remember { mutableStateOf(false) }
 LaunchedEffect(repository){repository.loadProviderProfileHub{profile,_->manjuDemoAvailable=profile?.optString("provider_id")=="9726ff16-eead-4e63-ba58-e797e8cb97ed"}}
 if(showDemo&&manjuDemoAvailable)KitchenDemoScreen(onClose={showDemo=false})
 val context=LocalContext.current
 var exportText by remember { mutableStateOf("") }
 var requestSerial by remember { mutableIntStateOf(0) }
 fun load(){val serial=++requestSerial;loading=true;error=null;repository.kitchenBook(monthKey){result,problem->if(serial==requestSerial){data=result;error=problem;loading=false}}}
 LaunchedEffect(monthKey){ data=null;load() }
 val entries=bookRows(data,"entries")
 val trend=bookRows(data,"trend")
 val summary=trend.lastOrNull()?:JSONObject()
 val statement=data?.optJSONObject("statement")?:JSONObject()
 val expenses=entries.filter{it.optString("kind")=="EXPENSE"&&it.optString("cost_type")!="EXCLUDED"}
 val sales=entries.filter{it.optString("kind")=="SALE"}
 val delivered=data?.optLong("delivered_meals")?:0
 val mealCount=delivered+sales.sumOf{it.optLong("meal_count")}
 val denominatorKnown=sales.none{it.optLong("meal_count")==0L}
 val income=summary.optLong("zomeal_paise")+summary.optLong("other_sales_paise")
 val totalCosts=statement.optLong("costs_to_date_paise",statement.optLong("operating_costs_paise",summary.optLong("expenses_paise")))
 val profit=income-totalCosts
 val paidOut=entries.filter{it.optString("kind")=="EXPENSE"&&it.optBoolean("paid")&&!it.optBoolean("staff_managed")}.sumOf{it.optLong("amount_paise")}+(data?.optLong("staff_cash_paid_paise")?:0)
 val otherCash=sales.filter{it.optBoolean("paid")}.sumOf{it.optLong("amount_paise")}
 val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")){uri->
  if(uri!=null) runCatching{context.contentResolver.openOutputStream(uri)?.use{it.write(exportText.toByteArray(Charsets.UTF_8))}?:throw IllegalStateException("Could not open file")}
   .onFailure{error="Export failed: ${it.message}"}
 }
 Scaffold(modifier=Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),containerColor=KBackground,
  topBar={Surface(color=Color.White){Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically){
   Column(Modifier.weight(1f)){Text("Kitchen Costs",fontSize=18.sp,fontWeight=FontWeight.Bold);Text("Income · expenses · profit",fontSize=11.sp,color=KMuted)}
   IconButton(onClick={load()},enabled=!loading){Icon(Icons.Outlined.Refresh,"Refresh",tint=KGreen)}
   IconButton(onClick={
    exportText="\uFEFF"+listOf(listOf("Month",monthKey),listOf("Net Zomeal earnings",rupees(summary.optLong("zomeal_paise"))),listOf("Other sales",rupees(summary.optLong("other_sales_paise"))),listOf("Operating expenses",rupees(totalCosts)),listOf("Estimated profit / loss",rupees(profit)),listOf("Date","Type","Category","Item","Amount INR","Paid","Quantity","Unit","Supplier","Cost type","Notes")).joinToString("\r\n"){it.joinToString(",",transform=::kitchenCsvCell)}+"\r\n"+
     entries.joinToString("\r\n"){e->val amountPaid=e.optLong("paid_amount_paise",if(e.optBoolean("paid"))e.optLong("amount_paise") else 0);listOf(e.optString("entry_date"),e.optString("kind"),e.optString("category"),e.optString("item"),BigDecimal(e.optLong("amount_paise")).movePointLeft(2).toPlainString(),if(amountPaid>=e.optLong("amount_paise"))"Yes" else if(amountPaid>0)"Part-paid" else "No",if(e.isNull("quantity"))"" else e.optString("quantity"),e.optString("unit"),e.optString("supplier"),e.optString("cost_type"),e.optString("note")).joinToString(",",transform=::kitchenCsvCell)}
    exportText=kitchenStatementCsv(data!!)+"\r\n"+exportText.removePrefix("\uFEFF")
    export.launch("Zomeal-kitchen-${monthKey.take(7)}.csv")
   },enabled=data!=null&&!loading){Icon(Icons.Outlined.FileDownload,"Export Excel-compatible CSV",tint=KGreen)}
  }}},bottomBar={ProviderNavigation("Kitchen Costs",onDashboard,onOrders,onEarnings,onProfile,{})}
 ){padding->LazyColumn(Modifier.padding(padding),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
  if(manjuDemoAvailable)item{BookCard("Manju Kitchen · practice with demo data"){
   Text("1–6 Oct · 100 lunches + 100 dinners daily · 68% cooking costs. Separate from live accounts.",fontSize=12.sp,color=KMuted)
   Button(onClick={showDemo=true},modifier=Modifier.fillMaxWidth()){Text("Open demo & monthly projection",fontSize=12.sp)}
  }}
  item{Row(verticalAlignment=Alignment.CenterVertically){IconButton(onClick={offset--},enabled=!loading&&monthKey>"2020-01-01"){Icon(Icons.Outlined.ChevronLeft,"Previous month")};Text(bookDate(month,"MMMM yyyy"),Modifier.weight(1f),fontSize=17.sp,fontWeight=FontWeight.Bold);IconButton(onClick={offset++},enabled=offset<0&&!loading){Icon(Icons.Outlined.ChevronRight,"Next month")}}}
  item{Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Button(onClick={sheetMonthly=bookRows(data,"monthly_costs").isEmpty();showSheet=true},enabled=!loading&&data!=null,modifier=Modifier.weight(1f),colors=ButtonDefaults.buttonColors(containerColor=KGreen)){Text("+ Add expenses",fontSize=12.sp)};OutlinedButton(onClick={showReview=true},enabled=!loading&&data?.has("statement")==true){Text("Stock check",color=KGreen,fontSize=12.sp)}}}
  item{Button(onClick={showAttendance=true},modifier=Modifier.fillMaxWidth().heightIn(min=52.dp),shape=RoundedCornerShape(12.dp)){Icon(Icons.Outlined.People,null,Modifier.size(20.dp));Spacer(Modifier.width(8.dp));Text("Staff & attendance",fontSize=14.sp,fontWeight=FontWeight.SemiBold)}}
  if(loading)item{LinearProgressIndicator(Modifier.fillMaxWidth(),color=KGreen)}
  error?.let{item{Text(it,color=KRed,fontSize=13.sp)}}
  if(data!=null){
   item{Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){listOf("Overview","Entries","Monthly setup","Statement").forEachIndexed{i,label->FilterChip(selected=tab==i,onClick={tab=i},label={Text(label,fontSize=12.sp)})}}}
   if(tab==0){
    item{Surface(color=if(profit<0)Color(0xFFFFEFED) else Color(0xFFE5F2E9),shape=RoundedCornerShape(18.dp)){Column(Modifier.fillMaxWidth().padding(16.dp)){Text(if(profit<0)"Estimated loss so far" else "Estimated profit so far",color=KMuted,fontSize=12.sp);Text(rupees(profit),color=if(profit<0)KRed else KGreen,fontSize=26.sp,fontWeight=FontWeight.Bold);Text("Monthly overhead spread across ${statement.optInt("days_in_month",month.getActualMaximum(Calendar.DAY_OF_MONTH))} days",color=KMuted,fontSize=11.sp)}}}
    item{BookCard("1 · Monthly setup → 2 · Daily expenses"){
     BookLine("Monthly overhead",rupees(statement.optLong("monthly_overhead_paise")))
     BookLine("Daily share",rupees(statement.optLong("fixed_per_day_paise")))
     BookLine("${statement.optInt("elapsed_days")} days counted",rupees(statement.optLong("allocated_fixed_paise")))
     TextButton(onClick={tab=2}){Text(if(bookRows(data,"monthly_costs").isEmpty())"Set rent, salary & bills first" else "Manage repeating monthly costs",fontSize=12.sp)}
    }}
    item{Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){BookStat("Net Zomeal earnings",rupees(summary.optLong("zomeal_paise")),Modifier.weight(1f));BookStat("Kitchen expenses",rupees(totalCosts),Modifier.weight(1f),KRed)}}
    item{Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){BookStat("Delivered meals",delivered.toString(),Modifier.weight(1f));BookStat("Cost per meal",if(mealCount>0&&denominatorKnown)rupees(totalCosts/mealCount) else "—",Modifier.weight(1f))}}
    item{Text(when(statement.optString("status")){"REVIEWED"->"Month reviewed · stock-adjusted estimate";"NEEDS_REVIEW"->"Costs or earnings changed · review this month again";else->if(statement.optBoolean("stock_entered"))"Draft · stock-adjusted estimate" else "Stock not entered · purchase-based estimate"},fontSize=12.sp,color=KGreen)}
    if(summary.optLong("other_sales_paise")!=0L)item{Text("Previously recorded other income: ${rupees(summary.optLong("other_sales_paise"))}. Included to preserve your existing accounts.",fontSize=11.sp,color=KMuted)}
    item{Column{TextButton(onClick={showGuide=!showGuide}){Text(if(showGuide)"Hide calculation notes" else "How totals work",fontSize=12.sp)};if(showGuide)Text("Net earnings already includes commission and refunds. Payouts are not income again. Enter opening and remaining ingredient stock in Month review to adjust purchases to consumption. Missing stock means purchase-based estimates. Depreciation and tax adjustments are not included. Equipment, owner withdrawals and loan principal are excluded from operating profit. Use costs attributable to the recorded kitchen income.",color=KMuted,fontSize=12.sp)}}
    item{BookCard("Cash movement — not bank balance"){
     BookLine("Zomeal payouts received",rupees(data!!.optLong("payouts_received_paise")))
     if(otherCash!=0L)BookLine("Previously recorded other receipts",rupees(otherCash));BookLine("Expenses paid",rupees(paidOut))
     BookLine("Current unsettled ledger · all months",rupees(statement.optLong("unsettled_paise")))
     Text("Includes earnings on hold. Not the amount available to withdraw; see Earnings for payout eligibility.",fontSize=11.sp,color=KMuted)
     HorizontalDivider();BookLine("Net cash movement",rupees(data!!.optLong("payouts_received_paise")+otherCash-paidOut))
     BookLine("Unpaid expenses · selected month",rupees(entries.filter{it.optString("kind")=="EXPENSE"}.sumOf{it.optLong("amount_paise")-it.optLong("paid_amount_paise",if(it.optBoolean("paid"))it.optLong("amount_paise") else 0)}))
    }}
    item{BookCard("Earnings vs expenses"){
     Text("Purchase-based trend · green: earnings · amber: costs. Stock-adjusted figures are in the monthly statement.",fontSize=11.sp,color=KMuted)
     val max=trend.maxOfOrNull{maxOf(it.optLong("zomeal_paise")+it.optLong("other_sales_paise"),it.optLong("expenses_paise"),1L)}?:1L
     trend.forEach{r->Text(r.optString("m").take(7),fontSize=12.sp);BookBar(r.optLong("zomeal_paise")+r.optLong("other_sales_paise"),max,KGreen);BookBar(r.optLong("expenses_paise"),max,Color(0xFFCD8B35))}
    }}
    item{BookCard("Recorded purchases & bills"){
     if(expenses.isEmpty())Text("Add your first expense to see the breakdown.",fontSize=13.sp,color=KMuted)
     expenses.groupBy{it.optString("category")}.mapValues{it.value.sumOf{e->e.optLong("amount_paise")}}.toList().sortedByDescending{it.second}.forEach{(category,value)->BookLine(category,rupees(value));BookBar(value,summary.optLong("expenses_paise").coerceAtLeast(1),KGreen)}
    }}
    item{BookCard("Purchase-based profit trend"){
     val max=trend.maxOfOrNull{kotlin.math.abs(it.optLong("profit_paise"))}?.coerceAtLeast(1)?:1
     trend.forEach{r->val value=r.optLong("profit_paise");BookLine(r.optString("m").take(7),rupees(value));BookBar(kotlin.math.abs(value),max,if(value<0)KRed else KGreen)}
    }}
    item{BreakEvenCard(monthKey,expenses.filter{it.optString("cost_type")=="FIXED"}.sumOf{it.optLong("amount_paise")},if(mealCount>0&&denominatorKnown)income/mealCount else 0,if(mealCount>0&&denominatorKnown)expenses.filter{it.optString("cost_type")=="VARIABLE"}.sumOf{it.optLong("amount_paise")}/mealCount else 0,month.getActualMaximum(Calendar.DAY_OF_MONTH))}
   }else if(tab==1){
    item{KitchenEntriesTable(entries,onEdit={if(it.optBoolean("staff_managed"))showStaff=true else editor=JSONObject(it.toString())},onRemove={if(it.optBoolean("staff_managed"))showStaff=true else voidEntry=it})}
   }else if(tab==2){
    item{BookCard("Set once. Repeat each month."){
     BookLine("Monthly overhead",rupees(statement.optLong("monthly_overhead_paise")))
     BookLine("÷ ${statement.optInt("days_in_month")} days",rupees(statement.optLong("fixed_per_day_paise"))+" / day")
     Text("New monthly rows repeat from next month. Copies appear automatically when you open that month, as unpaid estimates. Check electricity and other changing bills.",fontSize=12.sp,color=KMuted)
     Button(onClick={sheetMonthly=true;showSheet=true}){Text("Add monthly costs",fontSize=12.sp)}
    }}
    items(bookRows(data,"monthly_costs"),key={it.getString("id")}){e->BookCard(e.optString("item")){
     BookLine(e.optString("category"),rupees(e.optLong("amount_paise")))
     val salary=entries.any{it.optString("id")==e.optString("id")&&it.optBoolean("staff_managed")}
     if(salary){Text("Linked salary · payment history in Staff & salaries. Each month's salary is reviewed separately.",fontSize=11.sp);TextButton(onClick={showStaff=true}){Text("Open Staff & salaries")}}else{
     Text(if(e.optBoolean("paid"))"Payment recorded" else "Unpaid · confirm actual bill",fontSize=11.sp,color=KMuted)
     Row(verticalAlignment=Alignment.CenterVertically){TextButton(onClick={editor=JSONObject(e.toString())}){Text("Edit / mark paid",fontSize=12.sp)};Spacer(Modifier.weight(1f));Text("Repeat",fontSize=12.sp);Switch(checked=e.optBoolean("repeat_enabled"),enabled=!loading&&!e.isNull("repeat_root"),onCheckedChange={enabled->loading=true;repository.kitchenRepeatSetting(e.getString("repeat_root"),e.optInt("repeat_revision"),enabled){_,problem->if(problem==null)load()else{error=problem;loading=false}}})}
     }
    }}
   }else{
    item{KitchenStatementCard(data!!,onReview={showReview=true})}
   }
  }
 }}
 if(showStaff)StaffScreen(repository,onClose={showStaff=false;load()})
 if(showAttendance)StaffAttendanceScreen(repository,onClose={showAttendance=false;load()})
 if(showReview&&data?.has("statement")==true)KitchenReviewDialog(data!!,repository=repository,onDismiss={showReview=false},onSave={payload,done->repository.saveKitchenReview(payload){result,problem->done(problem);if(problem==null){data=result;showReview=false;load()}}})
 if(showSheet) KitchenCostSheet(if(offset==0)bookDate(bookCalendar(),"yyyy-MM-dd") else monthKey,startMonthly=sheetMonthly,repository=repository,onDismiss={showSheet=false;load()},onSave={rows,done->repository.saveKitchenSheet(rows){_,problem->done(problem);if(problem==null){showSheet=false;tab=if(sheetMonthly)0 else 1;load()}}})
 editor?.let{entry->KitchenEntryDialog(entry,onDismiss={editor=null},onSave={payload,done->repository.saveKitchenEntry(payload){_,problem->done(problem);if(problem==null){editor=null;load()}}})}
 voidEntry?.let{entry->AlertDialog(onDismissRequest={voidEntry=null},title={Text("Remove this entry?")},text={Text("It will be excluded from totals. An audit record is retained.")},confirmButton={TextButton(onClick={voidEntry=null;loading=true;repository.voidKitchenEntry(entry.getString("id"),entry.getInt("revision")){_,problem->if(problem==null)load() else{error=problem;loading=false}}}){Text("Remove",color=KRed)}},dismissButton={TextButton(onClick={voidEntry=null}){Text("Keep")}})}
}

@Composable private fun BookCard(title:String,content:@Composable ColumnScope.()->Unit){Surface(color=Color.White,shape=RoundedCornerShape(18.dp)){Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){Text(title,fontSize=15.sp,fontWeight=FontWeight.Bold);content()}}}
@Composable private fun BookStat(title:String,value:String,modifier:Modifier,color:Color=KGreen){Surface(modifier,color=Color.White,shape=RoundedCornerShape(16.dp)){Column(Modifier.padding(14.dp)){Text(title,fontSize=11.sp,color=KMuted);Text(value,fontSize=18.sp,fontWeight=FontWeight.Bold,color=color)}}}
@Composable private fun BookLine(label:String,value:String){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){Text(label,Modifier.weight(1f),fontSize=12.sp,color=KMuted);Text(value,fontSize=13.sp,fontWeight=FontWeight.SemiBold)}}
@Composable private fun BookBar(value:Long,max:Long,color:Color){LinearProgressIndicator(progress={(value.toFloat()/max.coerceAtLeast(1)).coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth().height(7.dp),color=color,trackColor=KBackground)}
@Composable private fun BookField(label:String,value:String,number:Boolean=false,onChange:(String)->Unit){OutlinedTextField(value,onChange,label={Text(label,fontSize=12.sp)},singleLine=true,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(12.dp),keyboardOptions=KeyboardOptions(keyboardType=if(number)KeyboardType.Decimal else KeyboardType.Text))}

@Composable private fun BreakEvenCard(key:String,fixed:Long,net:Long,variable:Long,days:Int){
 var expanded by remember{mutableStateOf(false)}
 var f by remember(key,fixed){mutableStateOf(BigDecimal(fixed).movePointLeft(2).toPlainString())}
 var n by remember(key,net){mutableStateOf(BigDecimal(net).movePointLeft(2).toPlainString())}
 var v by remember(key,variable){mutableStateOf(BigDecimal(variable).movePointLeft(2).toPlainString())}
 BookCard("Break-even calculator"){
  TextButton(onClick={expanded=!expanded}){Text(if(expanded)"Hide calculator" else "How many meals cover my costs?",color=KGreen)}
  if(expanded){
   Text("Editable planning estimate. Use a full month's fixed costs and typical per-meal costs; partial-month purchases can mislead.",fontSize=12.sp,color=KMuted)
   BookField("Monthly fixed costs ₹",f,true){f=it};BookField("Net earnings per meal ₹ (after commission)",n,true){n=it};BookField("Variable cost per meal ₹",v,true){v=it}
   val fp=kitchenPaise(f);val np=kitchenPaise(n);val vp=kitchenPaise(v)
   val count=if(fp!=null&&np!=null&&vp!=null)kitchenBreakEven(fp,np,vp) else null
   Text(if(count==null)"Enter valid amounts. Net earnings must exceed variable cost." else "$count meals/month · ${(count+days-1)/days} meals/day to break even",color=if(count==null)KRed else KGreen,fontWeight=FontWeight.Bold,fontSize=14.sp)
  }
 }
}

@Composable private fun KitchenEntryDialog(entry:JSONObject,onDismiss:()->Unit,onSave:(JSONObject,(String?)->Unit)->Unit){
 val context=LocalContext.current
 val sale=entry.optString("kind")=="SALE"
 val key=remember{entry.optString("id").ifBlank{UUID.randomUUID().toString()}}
 var item by remember{mutableStateOf(entry.optString("item"))}
 var amount by remember{mutableStateOf(if(entry.has("amount_paise"))BigDecimal(entry.optLong("amount_paise")).movePointLeft(2).toPlainString() else "")}
 var date by remember{mutableStateOf(entry.optString("entry_date").ifBlank{bookDate(bookCalendar(),"yyyy-MM-dd")})}
 var category by remember{mutableStateOf(if(sale)"Other sales" else entry.optString("category").ifBlank{"Vegetables"})}
 var costType by remember{mutableStateOf(entry.optString("cost_type").ifBlank{"VARIABLE"})}
 var paid by remember{mutableStateOf(entry.optBoolean("paid",true))}
 var recurring by remember{mutableStateOf(entry.optBoolean("recurring"))}
 var quantity by remember{mutableStateOf(if(entry.isNull("quantity"))"" else entry.optString("quantity"))}
 var unit by remember{mutableStateOf(entry.optString("unit"))}
 var supplier by remember{mutableStateOf(entry.optString("supplier"))}
 var note by remember{mutableStateOf(entry.optString("note"))}
 var meals by remember{mutableStateOf(entry.optInt("meal_count").takeIf{it>0}?.toString().orEmpty())}
 var more by remember{mutableStateOf(false)}
 var categories by remember{mutableStateOf(false)}
 var busy by remember{mutableStateOf(false)}
 var error by remember{mutableStateOf<String?>(null)}
 AlertDialog(onDismissRequest={if(!busy)onDismiss()},title={Text(if(sale)"Other kitchen sales" else "Kitchen expense",fontSize=19.sp)},
 text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(9.dp)){
  BookField(if(sale)"Sale description" else "Item / expense name",item){if(it.length<=100)item=it}
  BookField("Total amount ₹",amount,true){amount=it}
  Text("Enter the full bill amount, including applicable charges. Quantity is optional.",fontSize=11.sp,color=KMuted)
  OutlinedButton(onClick={val c=bookCalendar();DatePickerDialog(context,{_,y,m,d->date=String.format(Locale.US,"%04d-%02d-%02d",y,m+1,d)},c.get(Calendar.YEAR),c.get(Calendar.MONTH),c.get(Calendar.DAY_OF_MONTH)).apply{datePicker.maxDate=System.currentTimeMillis();show()}}){Text("Date: $date")}
  if(!sale){Box{OutlinedButton(onClick={categories=true},modifier=Modifier.fillMaxWidth()){Text(category)};DropdownMenu(categories,{categories=false}){KCategories.forEach{c->DropdownMenuItem(text={Text(c)},onClick={category=c;costType=if(c in listOf("Staff","Rent","Utilities"))"FIXED" else if(c=="Equipment / owner / loan")"EXCLUDED" else "VARIABLE";categories=false})}}}
   if(category!="Equipment / owner / loan") Row{FilterChip(costType=="VARIABLE",{costType="VARIABLE"},label={Text("Variable")});Spacer(Modifier.width(6.dp));FilterChip(costType=="FIXED",{costType="FIXED"},label={Text("Fixed")})}
  }else BookField("Number of portions sold (for cost/meal)",meals,true){meals=it.filter(Char::isDigit)}
  Row(verticalAlignment=Alignment.CenterVertically){Checkbox(paid,{paid=it});Text(if(sale)"Payment received" else "Paid",fontSize=13.sp)}
  if(!sale&&entry.isNull("source_template"))Row(verticalAlignment=Alignment.CenterVertically){Checkbox(recurring,{recurring=it});Text("Repeat automatically as unpaid",fontSize=12.sp)}
  TextButton(onClick={more=!more}){Text(if(more)"Hide optional details" else "+ Quantity, supplier & notes")}
  if(more){BookField("Quantity",quantity,true){quantity=it};BookField("Unit (kg, litre, packet, piece)",unit){if(it.length<=20)unit=it};BookField("Supplier / customer",supplier){if(it.length<=100)supplier=it};BookField("Notes",note){if(it.length<=500)note=it}}
  error?.let{Text(it,color=KRed,fontSize=12.sp)}
 }},confirmButton={Button(enabled=!busy,onClick={
  val paise=kitchenPaise(amount)
  if(item.isBlank()||paise==null||paise<=0){error="Enter a name and a valid positive amount.";return@Button}
  if(quantity.isNotBlank()&&(quantity.toBigDecimalOrNull()?.signum()!=1)){error="Quantity must be a positive number.";return@Button}
  if(sale&&meals.isNotBlank()&&(meals.toIntOrNull()==null||meals.toInt()>100000)){error="Check the portion count.";return@Button}
  busy=true;error=null
  onSave(JSONObject().put("id",key).put("revision",entry.optInt("revision",0)).put("date",date).put("kind",if(sale)"SALE" else "EXPENSE").put("category",category).put("item",item.trim()).put("amount_paise",paise).put("cost_type",costType).put("quantity",quantity).put("unit",unit).put("meal_count",if(sale)meals.toIntOrNull()?:0 else 0).put("paid",paid).put("supplier",supplier).put("note",note).put("recurring",!sale&&recurring)){problem->busy=false;error=problem}
 },colors=ButtonDefaults.buttonColors(containerColor=KGreen)){Text(if(busy)"Saving…" else "Save")}},dismissButton={TextButton(onClick=onDismiss,enabled=!busy){Text("Cancel")}})
}
