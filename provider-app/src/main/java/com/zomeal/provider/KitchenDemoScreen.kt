package com.zomeal.provider

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

private val DemoGreen=Color(0xFF087F43)
private val DemoRed=Color(0xFFB33C36)
private fun demoMoney(p:Long)=NumberFormat.getCurrencyInstance(Locale("en","IN")).format(BigDecimal(p).movePointLeft(2))

/** Local scenario only: this screen has no repository or database write path. */
@Composable internal fun KitchenDemoScreen(onClose:()->Unit){
 val prefs=LocalContext.current.getSharedPreferences("manju-cost-demo-october-2026",0)
 var rent by rememberSaveable{mutableStateOf(prefs.getString("rent","").orEmpty())}
 var electricity by rememberSaveable{mutableStateOf(prefs.getString("electricity","").orEmpty())}
 var salary by rememberSaveable{mutableStateOf(prefs.getString("salary","").orEmpty())}
 var other by rememberSaveable{mutableStateOf(prefs.getString("other","").orEmpty())}
 var rate by rememberSaveable{mutableStateOf(prefs.getString("commission","").orEmpty())}
 var saved by remember{mutableStateOf(false)}
 var expanded by rememberSaveable{mutableIntStateOf(1)}
 val values=listOf(rent,electricity,salary,other).map{if(it.isBlank())0L else kitchenPaise(it)}
 val valid=values.all{it!=null}
 val monthly=values.sumOf{it?:0L}
 val commission=KitchenDemoMath.commission(rate)
 val ready=valid&&(rate.isBlank()||commission!=null)
 val fees=commission?:0L
 val dailyProfit=KitchenDemoMath.profit(monthly,fees,1)
 val periodProfit=KitchenDemoMath.profit(monthly,fees,6)
 val projection=KitchenDemoMath.profit(monthly,fees,31)
 Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)){
  Surface(Modifier.fillMaxSize().padding(WindowInsets.safeDrawing.union(WindowInsets.ime).asPaddingValues()),color=Color(0xFFF7FAF7)){
   Scaffold(containerColor=Color(0xFFF7FAF7),contentWindowInsets=WindowInsets(0,0,0,0),topBar={
    Surface{Row(Modifier.fillMaxWidth().padding(12.dp)){
     Column(Modifier.weight(1f)){Text("Manju Kitchen · DEMO",fontWeight=FontWeight.Bold,fontSize=18.sp);Text("1–6 October 2026 · local preview",fontSize=12.sp,color=DemoGreen)}
     TextButton(onClick=onClose){Text("Close")}
    }}
   }){padding->Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
    DemoCard("Safe practice mode"){
     Text("100 lunches × ₹65 + 100 dinners × ₹55 every day. Cooking costs are 68% of gross sales, including gas. No real orders, earnings, expenses or payouts are changed.",fontSize=12.sp)
    }
    DemoCard("Your monthly assumptions"){
     Text("Enter full October amounts. Blank costs count as ₹0, so the projection is incomplete until you fill them. Saved only on this phone.",fontSize=12.sp)
     DemoInput("Rent ₹",rent){rent=it;saved=false}
     DemoInput("Electricity & water ₹",electricity){electricity=it;saved=false}
     DemoInput("Staff salaries ₹",salary){salary=it;saved=false}
     DemoInput("Other monthly costs ₹",other){other=it;saved=false}
     DemoInput("Demo commission % (optional)",rate){rate=it;saved=false}
     Text("Commission is a scenario assumption—not your configured Zomeal rate. Leave blank to see results before commission.",fontSize=11.sp,color=DemoGreen)
     if(!ready)Text("Use valid non-negative costs and a commission between 0 and 100%.",color=DemoRed,fontSize=12.sp)
     Button(enabled=ready,onClick={prefs.edit().putString("rent",rent).putString("electricity",electricity).putString("salary",salary).putString("other",other).putString("commission",rate).apply();saved=true},modifier=Modifier.fillMaxWidth()){
      Text(if(saved)"Demo assumptions saved" else "Save demo assumptions")
     }
     if(valid){DemoLine("Monthly overhead",monthly);DemoLine("Daily share · ÷ 31 days",KitchenDemoMath.allocation(monthly,1))}
    }
    if(ready){
     Surface(color=if(projection<0)Color(0xFFFFEFED) else Color(0xFFE5F2E9),shape=RoundedCornerShape(18.dp)){
      Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
       Text("Projected October ${if(projection<0)"loss" else "profit"}",fontSize=14.sp,fontWeight=FontWeight.Bold)
       Text(demoMoney(projection),fontSize=28.sp,fontWeight=FontWeight.Bold,color=if(projection<0)DemoRed else DemoGreen)
       Text(if(commission==null)"Before commission · after entered monthly costs" else "After assumed $rate% commission and entered monthly costs",fontSize=12.sp)
       Text("Projection, not earned money: assumes 200 meals every day for all 31 days, unchanged prices and 68% cooking costs. No skipped meals or refunds assumed.",fontSize=11.sp)
      }
     }
     DemoCard("October projection · 6,200 meals"){
      DemoLine("Gross revenue",KitchenDemoMath.revenue*31)
      DemoLine("Cooking costs · 68%",-KitchenDemoMath.cooking*31)
      if(commission!=null)DemoLine("Assumed commission",-fees*31)
      DemoLine("Monthly overhead",-monthly)
      HorizontalDivider();DemoLine("Projected result",projection)
     }
     DemoCard("1–6 October · 1,200 demo meals"){
      DemoLine("Gross revenue",KitchenDemoMath.revenue*6)
      DemoLine("Cooking costs",-KitchenDemoMath.cooking*6)
      if(commission!=null)DemoLine("Assumed commission",-fees*6)
      DemoLine("Net demo meal earnings",(KitchenDemoMath.revenue-fees)*6)
      DemoLine("Overhead for 6 / 31 days",-KitchenDemoMath.allocation(monthly,6))
      HorizontalDivider();DemoLine("Six-day result",periodProfit)
      Text("Daily overhead is rounded for display; period totals are calculated precisely, so a few paise can differ from adding displayed daily rows.",fontSize=11.sp)
     }
     Text("Daily earnings & cooking costs",fontSize=16.sp,fontWeight=FontWeight.Bold)
     (1..6).forEach{day->DemoCard("$day October 2026"){
      Text("Lunch: 100 × ₹65 = ₹6,500  ·  Dinner: 100 × ₹55 = ₹5,500",fontSize=12.sp)
      DemoLine("Gross earnings",KitchenDemoMath.revenue)
      if(commission!=null)DemoLine("Assumed commission",-fees)
      DemoLine("Cooking costs",-KitchenDemoMath.cooking)
      DemoLine("Monthly cost share",-KitchenDemoMath.allocation(monthly,1))
      DemoLine(if(commission==null)"Result before commission" else "Daily result",dailyProfit)
      LinearProgressIndicator(progress={0.68f},modifier=Modifier.fillMaxWidth(),color=Color(0xFFCA8C38),trackColor=Color(0xFFD7EBDD))
      Text("68% cooking · 32% left for commission, overhead and profit",fontSize=11.sp)
      TextButton(onClick={expanded=if(expanded==day)0 else day}){Text(if(expanded==day)"Hide expense rows" else "View 9 demo expense rows")}
      if(expanded==day){KitchenDemoMath.costs(day).forEach{DemoLine(it.name,it.paise)};HorizontalDivider();DemoLine("Total cooking costs",KitchenDemoMath.cooking);Text("Illustrative daily amounts, not actual bills or stock quantities.",fontSize=11.sp)}
     }}
    }
   }}
  }
 }
}
@Composable private fun DemoCard(title:String,body:@Composable ColumnScope.()->Unit){Surface(color=Color.White,shape=RoundedCornerShape(18.dp)){Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){Text(title,fontWeight=FontWeight.Bold,fontSize=14.sp);body()}}}
@Composable private fun DemoLine(label:String,value:Long){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){Text(label,Modifier.weight(1f),fontSize=12.sp);Text(demoMoney(value),fontSize=13.sp,fontWeight=FontWeight.SemiBold,color=if(value<0)DemoRed else DemoGreen)}}
@Composable private fun DemoInput(label:String,value:String,onChange:(String)->Unit){OutlinedTextField(value,onChange,label={Text(label,fontSize=12.sp)},singleLine=true,modifier=Modifier.fillMaxWidth(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),shape=RoundedCornerShape(12.dp))}
