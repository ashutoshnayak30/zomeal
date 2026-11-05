package com.zomeal.provider

import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.json.JSONObject
import org.json.JSONArray
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

private val ChartGreen = Color(0xFF087F43)
private val ChartInk = Color(0xFF14221B)
private val ChartMuted = Color(0xFF68736D)
private val ChartRed = Color(0xFFB4433C)
private val chartColors = listOf(ChartGreen,Color(0xFF76AD76),Color(0xFFE1B34C),Color(0xFF719DA0),Color(0xFF9383AD),Color(0xFFCB8467))
private fun cash(value: Long) = "₹" + NumberFormat.getNumberInstance(Locale("en","IN")).apply { maximumFractionDigits=0 }.format(BigDecimal.valueOf(value,2))
private fun objects(a: JSONArray?) = (0 until (a?.length() ?: 0)).mapNotNull { a?.optJSONObject(it) }
private fun monthLabel(m: YearMonth) = m.format(DateTimeFormatter.ofPattern("MMMM yyyy",Locale.ENGLISH))

@Composable private fun ChartCard(title:String, content:@Composable ColumnScope.()->Unit) {
    Surface(color=Color.White,shape=RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(17.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(title,color=ChartInk,fontSize=15.sp,fontWeight=FontWeight.Bold); content()
        }
    }
}
@Composable private fun Metric(label:String,value:Long,modifier:Modifier,negative:Boolean=false) {
    Surface(modifier,color=if(negative) Color(0xFFFFF0ED) else Color(0xFFEAF5EE),shape=RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(13.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
            Text(label,color=ChartMuted,fontSize=11.sp)
            Text(cash(value),color=if(negative) ChartRed else ChartGreen,fontSize=21.sp,fontWeight=FontWeight.Bold)
        }
    }
}

@Composable internal fun EarningsInfographics(repository:SupabaseProviderRepository,refresh:Int,onCosts:()->Unit,commissionRate:Double) {
    val current=YearMonth.now(ZoneId.of("Asia/Kolkata"))
    var month by remember { mutableStateOf(current) }
    var book by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var retry by remember { mutableIntStateOf(0) }
    var demo by remember { mutableStateOf(false) }
    var demoDay by remember { mutableIntStateOf(6) }
    var editDemo by remember { mutableStateOf(false) }
    var demoRevision by remember { mutableIntStateOf(0) }
    val prefs=LocalContext.current.getSharedPreferences("manju-cost-demo-october-2026",0)
    if(editDemo) KitchenDemoScreen { editDemo=false;demoRevision++ }
    DisposableEffect(month,refresh,retry) {
        var active=true; loading=true; book=null; error=null
        repository.kitchenBook(month.atDay(1).toString()) { result,problem -> if(active){book=result;error=problem;loading=false} }
        onDispose { active=false }
    }
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            FilterChip(!demo,{demo=false},{Text("Live accounts")})
            FilterChip(demo,{demo=true},{Text("100-customer demo")})
        }
        if(demo) {
            Text("DEMO ONLY · 100 lunches × ₹65 + 100 dinners × ₹55 daily. Cooking + gas: 68%. Only this dashboard is simulated; payout and history sections below remain LIVE.",fontSize=12.sp,color=ChartGreen)
            Text("October preview · through day $demoDay of 31",fontSize=12.sp,fontWeight=FontWeight.Bold)
            Slider(demoDay.toFloat(),{demoDay=it.toInt().coerceIn(1,31)},valueRange=1f..31f,steps=29)
            OutlinedButton({editDemo=true}){Text("Set demo rent, salaries & other costs")}
        }
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            TextButton({month=month.minusMonths(1)}){Text("‹",fontSize=24.sp,color=ChartGreen)}
            Text(monthLabel(month),Modifier.weight(1f),color=ChartInk,fontSize=18.sp,fontWeight=FontWeight.Bold)
            TextButton({month=month.plusMonths(1)},enabled=month<current){Text("›",fontSize=24.sp)}
        }
        if(loading) LinearProgressIndicator(Modifier.fillMaxWidth(),color=ChartGreen)
        if(error!=null) { Text("Financial dashboard unavailable: $error",color=ChartRed); TextButton({retry++}){Text("Retry") } }
        val b=if(demo) remember(demoDay,demoRevision,commissionRate) {
            earningsDemoBook(demoDay,commissionRate,listOf("rent","salary","electricity","other").associateWith { kitchenPaise(prefs.getString(it,"").orEmpty()) ?: 0L })
        } else book
        val s=b?.optJSONObject("statement")
        if(s!=null) {
            val gross=s.optLong("gross_paise")
            val costs=s.optLong("costs_to_date_paise")
            val profit=s.optLong("profit_to_date_paise")
            Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xFF06492D),ChartGreen)),RoundedCornerShape(24.dp)).padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Text("YOUR KITCHEN · MONTHLY PERFORMANCE",color=Color(0xFFD2E8BA),fontSize=10.sp,fontWeight=FontWeight.Bold)
                Text("Total meal revenue",color=Color.White.copy(alpha=.8f),fontSize=12.sp)
                Text(cash(gross),color=Color.White,fontSize=34.sp,fontWeight=FontWeight.Bold)
                HorizontalDivider(color=Color.White.copy(alpha=.15f))
                Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) { Text("Estimated margin",fontSize=11.sp,color=Color.White.copy(alpha=.8f));Text(if(gross>0)String.format(Locale.ENGLISH,"%.1f%%",profit.toDouble()/gross*100) else "—",color=Color(0xFFD6EF98),fontSize=22.sp,fontWeight=FontWeight.Bold) }
                    Column(Modifier.weight(1f)) { Text("Costs to date",fontSize=11.sp,color=Color.White.copy(alpha=.8f));Text(cash(costs),color=Color.White,fontSize=22.sp,fontWeight=FontWeight.Bold) }
                }
                Text(if(demo) "Simulated October · day $demoDay" else "Recorded activity · ${monthLabel(month)}",fontSize=10.sp,color=Color.White.copy(alpha=.7f))
            }
            Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                Metric("Net meal earnings",s.optLong("net_paise"),Modifier.weight(1f))
                Metric("Estimated profit / loss",profit,Modifier.weight(1f),profit<0)
            }
            Text("Net meal earnings ${cash(s.optLong("net_paise"))} · refunds / adjustments ${cash(s.optLong("adjustments_paise"))}",fontSize=11.sp,color=ChartMuted)
            ChartCard("Where your money goes") {
                val elapsed=s.optInt("elapsed_days",1)
                val days=s.optInt("days_in_month",31).coerceAtLeast(1)
                Text("Through day $elapsed of $days · daily costs + accrued overhead",fontSize=11.sp,color=ChartMuted)
                val cats=s.optJSONObject("categories") ?: JSONObject()
                val food=if(s.optBoolean("stock_valid")) s.optLong("food_used_paise") else s.optLong("food_purchases_paise")
                val total=costs.coerceAtLeast(0)
                fun accrued(category:String):Long {
                    if(demo)return cats.optLong(category)
                    val entries=objects(b.optJSONArray("entries")).filter { it.optString("category")==category && it.optString("kind")=="EXPENSE" && it.optString("cost_type")!="EXCLUDED" }
                    val fixed=entries.filter {it.optString("cost_type")=="FIXED"}.sumOf {it.optLong("amount_paise")}
                    val managed=entries.filter {it.optBoolean("staff_managed")}.sumOf {it.optLong("amount_paise")}
                    return cats.optLong(category)-fixed+Math.round((fixed-managed).toDouble()*elapsed/days)+(if(category=="Staff")s.optLong("salary_accrued_paise") else 0)
                }
                val parts=listOf("Food & ingredients" to food,"Staff salaries" to accrued("Staff"),"Rent" to accrued("Rent"),"Gas & fuel" to accrued("Gas & fuel"),"Electricity & water" to accrued("Utilities"))
                val slices=parts+listOf("Other operating costs" to (total-parts.sumOf{it.second}).coerceAtLeast(0))
                val sum=slices.sumOf{it.second.coerceAtLeast(0)}
                var animateIn by remember(demo,month,demoDay,total) { mutableStateOf(false) }
                LaunchedEffect(demo,month,demoDay,total) { animateIn=true }
                val coverage by animateFloatAsState(if(animateIn) elapsed.toFloat()/days else 0f,tween(900),label="Month progress")
                Box(Modifier.fillMaxWidth().height(190.dp),contentAlignment=Alignment.Center) {
                    Canvas(Modifier.size(180.dp).semantics { contentDescription="Operating cost breakdown. ${slices.joinToString { "${it.first}: ${cash(it.second)}" }}" }) {
                        val pad=14.dp.toPx(); val sz=Size(size.width-2*pad,size.height-2*pad)
                        drawArc(Color(0xFFEAF1EC),0f,360f,false,Offset(pad,pad),sz,style=Stroke(23.dp.toPx()))
                        var start=-90f
                        if(sum>0) slices.forEachIndexed { i,p -> val angle=(p.second.coerceAtLeast(0).toDouble()/sum*360*coverage).toFloat(); if(angle>0)drawArc(chartColors[i],start,angle,false,Offset(pad,pad),sz,style=Stroke(23.dp.toPx())); start+=angle }
                    }
                    Column(horizontalAlignment=Alignment.CenterHorizontally) { Text(cash(total),fontSize=22.sp,fontWeight=FontWeight.Bold,color=ChartInk);Text("Costs to date",fontSize=11.sp,color=ChartMuted);Text("${elapsed*100/days}% of month",fontSize=11.sp,color=ChartGreen) }
                }
                slices.forEachIndexed { i,p -> Column(verticalArrangement=Arrangement.spacedBy(6.dp)) { Row(verticalAlignment=Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).background(chartColors[i],RoundedCornerShape(4.dp)));Spacer(Modifier.width(8.dp))
                    Text(p.first,Modifier.weight(1f),fontSize=12.sp,color=ChartMuted);Text("${cash(p.second)} · ${if(sum>0) (p.second.toDouble()/sum*100).toInt() else 0}%",fontSize=12.sp,fontWeight=FontWeight.SemiBold)
                }
                    Box(Modifier.fillMaxWidth().height(5.dp).background(Color(0xFFF0F5F1),RoundedCornerShape(4.dp))) { Box(Modifier.fillMaxWidth(if(sum>0)(p.second.toFloat()/sum).coerceIn(0f,1f) else 0f).fillMaxHeight().background(chartColors[i],RoundedCornerShape(4.dp))) }
                } }
                HorizontalDivider(color=Color(0xFFEAF1EC))
                Text("Commission: ${cash(s.optLong("commission_paise"))} · already deducted from net earnings",fontSize=11.sp,color=ChartGreen)
                Text("The coloured arc is elapsed calendar time, not a spending target. Grey is the rest of the month. Food is recorded consumption/purchases, not a prorated monthly budget.",fontSize=11.sp,color=ChartMuted)
            }
            ChartCard("Revenue → deductions → result") {
                var reveal by remember(demo,month,demoDay,gross,costs) { mutableStateOf(false) }
                LaunchedEffect(demo,month,demoDay,gross,costs) { reveal=true }
                val otherIncome=profit+costs-s.optLong("net_paise")
                val bridge=listOf("Meal revenue" to gross,"Commission" to -s.optLong("commission_paise"),"Refunds / adjustments" to s.optLong("adjustments_paise"))+(if(otherIncome!=0L)listOf("Other recorded income" to otherIncome) else emptyList())+listOf("Operating costs to date" to -costs,"Estimated profit / loss" to profit)
                bridge.forEach { (label,amount) ->
                    val width by animateFloatAsState(if(reveal && gross>0)(kotlin.math.abs(amount.toDouble())/gross).toFloat().coerceIn(0f,1f) else 0f,tween(800),label=label)
                    Column(verticalArrangement=Arrangement.spacedBy(5.dp)) { Row {Text(label,Modifier.weight(1f),fontSize=12.sp);Text(cash(amount),fontSize=13.sp,fontWeight=FontWeight.Bold,color=if(amount<0)ChartRed else ChartGreen)}
                        Box(Modifier.fillMaxWidth().height(8.dp).background(Color(0xFFF0F5F1),RoundedCornerShape(5.dp))) {Box(Modifier.fillMaxWidth(width).fillMaxHeight().background(if(amount<0)Color(0xFFD18B70) else ChartGreen,RoundedCornerShape(5.dp)))}
                    }
                }
                if(demo)Text(if(commissionRate.isFinite())"Demo uses current agreed commission: $commissionRate%. Blank demo overhead counts as zero; fill it for a useful estimate." else "Commission unavailable: demo is BEFORE commission. Entered overhead only.",fontSize=11.sp,color=ChartMuted)
            }
            if((demo || month==current) && s.optInt("elapsed_days")>0 && gross>0) {
                val days=s.optInt("days_in_month",month.lengthOfMonth())
                val elapsed=s.optInt("elapsed_days").coerceIn(1,days)
                val factor=days.toDouble()/elapsed
                val projectedRevenue=(gross*factor).toLong()
                // Start from actual profit + costs so existing recorded external sales are not lost.
                val projectedIncome=((profit+costs)*factor).toLong()
                val variableCosts=costs-s.optLong("allocated_fixed_paise")
                val projectedCosts=(variableCosts*factor).toLong()+s.optLong("monthly_overhead_paise")
                val projectedProfit=projectedIncome-projectedCosts
                ChartCard("Month-end outlook") {
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Metric("Projected meal revenue",projectedRevenue,Modifier.weight(1f))
                        Metric("Projected profit / loss",projectedProfit,Modifier.weight(1f),projectedProfit<0)
                    }
                    Text("Estimate using $elapsed of $days calendar days, including today. Assumes the same daily activity and variable costs; monthly overhead is included once. Not a payout guarantee.",fontSize=11.sp,color=ChartMuted)
                }
            }
            if(!demo) ChartCard("Earnings vs expenses · 6 months") {
                Text("Net Zomeal earnings vs recorded purchase costs. Excludes stock adjustments and external sales.",fontSize=11.sp,color=ChartMuted)
                val trend=objects(b.optJSONArray("trend"))
                val max=trend.maxOfOrNull { maxOf(it.optLong("zomeal_paise"),it.optLong("expenses_paise")) }?.coerceAtLeast(1) ?: 1
                var selectedBar by remember(month) { mutableIntStateOf(5) }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(7.dp),verticalAlignment=Alignment.Bottom) {
                    trend.forEachIndexed { index,t -> Column(Modifier.weight(1f),horizontalAlignment=Alignment.CenterHorizontally) {
                        Row(Modifier.height(112.dp),horizontalArrangement=Arrangement.spacedBy(3.dp),verticalAlignment=Alignment.Bottom) {
                            listOf(t.optLong("zomeal_paise"),t.optLong("expenses_paise")).forEachIndexed { i,v -> Box(Modifier.weight(1f).height((v.toDouble()/max*112).coerceIn(1.0,112.0).dp).background(if(i==0)ChartGreen else Color(0xFFB8D965),RoundedCornerShape(topStart=5.dp,topEnd=5.dp))) }
                        }
                        TextButton({selectedBar=index},contentPadding=PaddingValues(0.dp)) {Text(t.optString("m").take(7).let { runCatching {YearMonth.parse(it).format(DateTimeFormatter.ofPattern("MMM"))}.getOrDefault(it)},fontSize=10.sp,color=if(index==selectedBar)ChartGreen else ChartMuted)}
                    } }
                }
                Text("Green: net earnings · Lime: costs · tap a month",fontSize=10.sp,color=ChartMuted)
                trend.getOrNull(selectedBar.coerceAtMost((trend.size-1).coerceAtLeast(0)))?.let { t ->
                    val label=t.optString("m").take(7)
                    Text(label,fontSize=11.sp,color=ChartMuted)
                    listOf("Net earnings" to t.optLong("zomeal_paise"),"Costs" to t.optLong("expenses_paise")).forEachIndexed { i,p ->
                        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.weight(1f).height(9.dp).background(Color(0xFFF0F5F1),RoundedCornerShape(5.dp))) {
                                Box(Modifier.fillMaxWidth((p.second.toDouble()/max).toFloat().coerceIn(0f,1f)).fillMaxHeight().background(if(i==0)ChartGreen else Color(0xFFE1B34C),RoundedCornerShape(5.dp)))
                            }
                            Text("${p.first} ${cash(p.second)}",fontSize=10.sp,color=ChartMuted)
                        }
                    }
                }
            }
            Text("Estimates include only recorded expenses and approved salaries. Missing costs can overstate profit. Payouts and advance recovery are cash movements, not new operating costs.",fontSize=11.sp,color=ChartMuted)
            OutlinedButton(onCosts,Modifier.fillMaxWidth()) { Text("Update costs, stock & salaries",color=ChartGreen) }
        }
    }
}

@Composable internal fun EarningsHistory(repository:SupabaseProviderRepository,onClose:()->Unit) {
    var month by remember { mutableStateOf<String?>(null) }
    var months by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var entries by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var more by remember { mutableStateOf(false) }
    var request by remember { mutableIntStateOf(0) }
    var cursor by remember { mutableStateOf<JSONObject?>(null) }
    DisposableEffect(month,request) {
        var active=true; loading=true;error=null
        val selected=month
        if(selected==null) repository.transactionMonths { r,e -> if(active){months=objects(r?.optJSONArray("months"));error=e;loading=false} }
        else repository.transactionPage(selected,cursor?.optString("created_at"),cursor?.optString("id")) { r,e -> if(active){
            if(e==null && r!=null){val page=objects(r.optJSONArray("entries")).take(50);entries=if(cursor==null)page else (entries+page).distinctBy{it.optString("id")};more=r.optBoolean("has_more")};error=e;loading=false
        } }
        onDispose {active=false}
    }
    Dialog(onClose,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Scaffold(modifier=Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),containerColor=Color(0xFFF7FAF7),topBar={Row(Modifier.fillMaxWidth().padding(10.dp),verticalAlignment=Alignment.CenterVertically){
            TextButton({if(month==null)onClose() else {month=null;entries=emptyList();cursor=null;more=false}}){Text("Back")}
            Text("Transaction history",Modifier.weight(1f),fontWeight=FontWeight.Bold);TextButton(onClose){Text("Close")}
        }}) { padding -> LazyColumn(Modifier.padding(padding),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            item { Text("Grouped by posting month (IST). Meal service dates may differ.",fontSize=12.sp,color=ChartMuted) }
            if(month==null) {
                items(months){m -> OutlinedButton({month=m.optString("month_start");cursor=null;entries=emptyList()},Modifier.fillMaxWidth()) {Text("${monthLabel(YearMonth.parse(m.optString("month_start").take(7)))} · ${m.optInt("entry_count")} entries")} }
                if(!loading && error==null && months.isEmpty()) item {Text("No transactions yet.")}
            } else {
                item {Text(monthLabel(YearMonth.parse(month!!.take(7))),fontWeight=FontWeight.Bold)}
                items(entries,key={it.optString("id")}){LedgerEntryCard(it)}
                if(more && error==null) item {OutlinedButton({cursor=entries.lastOrNull();request++},enabled=!loading,modifier=Modifier.fillMaxWidth()){Text("Load more transactions")}}
            }
            if(loading) item {LinearProgressIndicator(Modifier.fillMaxWidth())}
            error?.let {item {Text(it,color=ChartRed);TextButton({request++}){Text("Retry")}}}
        } }
    }
}
