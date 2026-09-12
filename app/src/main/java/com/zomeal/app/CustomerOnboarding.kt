package com.zomeal.app

import android.content.Context
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private val TourGreen=Color(0xFF078A45)
private val TourInk=Color(0xFF10231B)
private val TourMuted=Color(0xFF66716B)
private data class TourStep(val label:String,val title:String,val description:String,val note:String)
private val tourSteps=listOf(
    TourStep("YOUR AREA","Good food starts\nclose to home","Enter your delivery pincode to discover approved kitchens that serve your area.","Availability is checked for your pincode."),
    TourStep("YOUR KITCHEN","Find your kind\nof kitchen","Explore providers, food photos and menus. Choose the kitchen that fits your routine.","Choose from kitchens available in your area."),
    TourStep("YOUR PACKAGE","Lunch, dinner\nor a little of both?","Choose lunch, dinner or both, then pick an available weekly trial or monthly plan.","Each provider sets their own package prices."),
    TourStep("YOUR WEEK","A week of meals,\nchosen by you","Set your seven-day menu from your kitchen’s available choices. Review each day before continuing.","Available dishes depend on your selected kitchen."),
    TourStep("REVIEW & PAY","Check the details.\nMake it yours.","Review your meals, delivery address, start date and payment amount. Pay securely—or save your plan and pay later.","A saved plan is not an active subscription."),
    TourStep("YOU’RE IN CONTROL","Your routine changes.\nYour meals can too.","Manage your plan, edit eligible meals and pause before the cut-off. Track meal charges and recharge in your wallet.","Lunch cut-off: 8 AM · Dinner cut-off: 4 PM (IST).")
)

internal class CustomerOnboardingPreferences(context:Context) {
    private val preferences=context.getSharedPreferences("zomeal_customer_onboarding",Context.MODE_PRIVATE)
    val completed get()=preferences.getBoolean("completed_v1",false)
    fun complete(){preferences.edit().putBoolean("completed_v1",true).apply()}
}

/** Local, offline introduction only: never creates a plan or changes account data. */
@Composable
internal fun CustomerOnboardingScreen(onFinish:()->Unit) {
    val pager=rememberPagerState(pageCount={tourSteps.size})
    val scope=rememberCoroutineScope()
    val context=LocalContext.current
    val animate=remember{runCatching{Settings.Global.getFloat(context.contentResolver,Settings.Global.ANIMATOR_DURATION_SCALE,1f)>0f}.getOrDefault(false)}
    BackHandler{if(pager.currentPage==0)onFinish() else scope.launch{pager.animateScrollToPage(pager.currentPage-1)}}
    Scaffold(containerColor=Color(0xFFFAFCF8),modifier=Modifier.systemBarsPadding(),topBar={
        Row(Modifier.fillMaxWidth().padding(start=24.dp,end=12.dp,top=6.dp),verticalAlignment=Alignment.CenterVertically){
            Text("zomeal",color=TourGreen,fontSize=29.sp,fontWeight=FontWeight.Black,modifier=Modifier.weight(1f))
            TextButton(onClick=onFinish){Text("Skip intro",color=TourMuted)}
        }
    },bottomBar={
        Column(Modifier.fillMaxWidth().padding(horizontal=24.dp,vertical=16.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)){
            Row(horizontalArrangement=Arrangement.spacedBy(7.dp),modifier=Modifier.clearAndSetSemantics{contentDescription="Step ${pager.currentPage+1} of ${tourSteps.size}"}){
                repeat(tourSteps.size){index->Box(Modifier.size(if(index==pager.currentPage)24.dp else 7.dp,7.dp).clip(CircleShape).background(if(index==pager.currentPage)TourGreen else Color(0xFFD8E4DB)))}
            }
            Button(onClick={if(pager.currentPage==tourSteps.lastIndex)onFinish() else scope.launch{pager.animateScrollToPage(pager.currentPage+1)}},enabled=!pager.isScrollInProgress,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp),shape=RoundedCornerShape(16.dp),colors=ButtonDefaults.buttonColors(containerColor=TourGreen)){
                Text(if(pager.currentPage==tourSteps.lastIndex)"Let’s get started" else "Next",fontSize=16.sp,fontWeight=FontWeight.Bold)
            }
            Text("Swipe to explore · ${pager.currentPage+1}/${tourSteps.size}",fontSize=12.sp,color=TourMuted)
        }
    }){padding->
        HorizontalPager(state=pager,modifier=Modifier.fillMaxSize().padding(padding),key={it}){index->
            val step=tourSteps[index]
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=24.dp,vertical=14.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(15.dp)){
                TourIllustration(index,animate&&pager.currentPage==index)
                Text("${index+1}. ${step.label}",fontSize=12.sp,fontWeight=FontWeight.Bold,color=TourGreen,letterSpacing=1.6.sp)
                Text(step.title,fontSize=27.sp,lineHeight=33.sp,fontWeight=FontWeight.ExtraBold,color=TourInk,textAlign=TextAlign.Center,modifier=Modifier.semantics{heading()})
                Text(step.description,fontSize=15.sp,lineHeight=23.sp,color=TourMuted,textAlign=TextAlign.Center)
                Surface(color=Color(0xFFEBF3E7),shape=RoundedCornerShape(14.dp)){
                    Text(step.note,fontSize=12.sp,lineHeight=18.sp,color=TourInk,textAlign=TextAlign.Center,modifier=Modifier.padding(12.dp))
                }
            }
        }
    }
}

@Composable
private fun TourIllustration(step:Int,animated:Boolean) {
    val phase=if(animated){
        val transition=rememberInfiniteTransition(label="onboarding illustration")
        val value by transition.animateFloat(0f,1f,infiniteRepeatable(tween(2600,easing=FastOutSlowInEasing),RepeatMode.Reverse),label="gentle movement")
        value
    } else .5f
    BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(1.32f).clip(RoundedCornerShape(32.dp)).background(Brush.linearGradient(listOf(Color(0xFFE4F2DB),Color(0xFFF2F8ED),Color(0xFFE1F1E9)))).clearAndSetSemantics{},contentAlignment=Alignment.Center){
        Canvas(Modifier.fillMaxSize()){
            drawCircle(Color(0xFFB7DA45).copy(alpha=.22f),size.minDimension*.32f,center.copy(x=size.width*.85f,y=size.height*.2f))
            drawCircle(TourGreen.copy(alpha=.07f),size.minDimension*.4f,center.copy(x=size.width*.06f,y=size.height*.95f))
        }
        Column(Modifier.fillMaxWidth(.86f).offset(y=((phase-.5f)*7).dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)){
            when(step){
                0->{
                    Icon(Icons.Outlined.LocationOn,null,tint=TourGreen,modifier=Modifier.size(42.dp).offset(y=(-phase*7).dp))
                    TourCard{TourLine(Icons.Outlined.Search,"Your delivery area");Row(horizontalArrangement=Arrangement.spacedBy(4.dp)){repeat(6){Surface(color=Color(0xFFEDF5EB),shape=RoundedCornerShape(7.dp),modifier=Modifier.weight(1f)){Text("—",textAlign=TextAlign.Center,color=TourGreen,modifier=Modifier.padding(vertical=9.dp))}}}}
                    TourPill(Icons.Outlined.CheckCircle,"Discover nearby kitchens")
                }
                1->{
                    TourCard{Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){TourTiffin(phase,Modifier.size(67.dp));Column{Text("Your local kitchen",fontWeight=FontWeight.Bold,color=TourInk);Text("Photos · Menu · Packages",fontSize=11.sp,color=TourMuted)}}}
                    TourCard{TourLine(Icons.Outlined.Storefront,"Explore available providers")}
                    TourPill(Icons.Outlined.FavoriteBorder,"Find your food favourites")
                }
                2->{
                    Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){TourPill(Icons.Outlined.DateRange,"Weekly");TourPill(Icons.Outlined.CalendarMonth,"Monthly")}
                    TourCard{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly){TourMeal(Icons.Outlined.LightMode,"Lunch",false);TourMeal(Icons.Outlined.Restaurant,"Both",true);TourMeal(Icons.Outlined.DarkMode,"Dinner",false)}}
                }
                3->{
                    TourCard{
                        TourLine(Icons.Outlined.CalendarMonth,"Your weekly menu")
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){listOf("M","T","W","T","F","S","S").forEachIndexed{i,d->Surface(shape=CircleShape,color=if(i==(phase*6).toInt())TourGreen else Color(0xFFEAF3E6)){Text(d,color=if(i==(phase*6).toInt())Color.White else TourInk,fontSize=10.sp,modifier=Modifier.padding(7.dp))}}}
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly){TourTiffin(phase,Modifier.size(64.dp));TourTiffin(1-phase,Modifier.size(64.dp))}
                    }
                    TourPill(Icons.Outlined.CheckCircle,"Choose · Review · Save")
                }
                4->{
                    TourCard{TourLine(Icons.Outlined.FactCheck,"Review your plan");TourLine(Icons.Outlined.LocationOn,"Delivery address");TourLine(Icons.Outlined.Event,"Your start date")}
                    TourPill(Icons.Outlined.Lock,"Secure payment or save for later")
                }
                else->{
                    TourCard{TourLine(Icons.Outlined.AccountBalanceWallet,"Your meal wallet");Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly){TourMeal(Icons.Outlined.PauseCircle,"Pause",true);TourMeal(Icons.Outlined.Edit,"Edit meals",false);TourMeal(Icons.Outlined.ReceiptLong,"Activity",false)}}
                    TourPill(Icons.Outlined.Schedule,"Plan around your day")
                }
            }
        }
    }
}

@Composable private fun TourCard(content:@Composable ColumnScope.()->Unit){Surface(color=Color.White,shape=RoundedCornerShape(18.dp),shadowElevation=2.dp,modifier=Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(10.dp),content=content)}}
@Composable private fun TourLine(icon:ImageVector,label:String){Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(9.dp)){Icon(icon,null,tint=TourGreen,modifier=Modifier.size(21.dp));Text(label,fontSize=12.sp,color=TourInk,fontWeight=FontWeight.Medium)}}
@Composable private fun TourPill(icon:ImageVector,label:String){Surface(color=Color(0xFFDCECCD),shape=RoundedCornerShape(12.dp)){Row(Modifier.padding(horizontal=10.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(5.dp)){Icon(icon,null,tint=TourGreen,modifier=Modifier.size(16.dp));Text(label,fontSize=11.sp,color=TourInk)}}}
@Composable private fun TourMeal(icon:ImageVector,label:String,selected:Boolean){Column(Modifier.clip(RoundedCornerShape(12.dp)).background(if(selected)Color(0xFFE5F1DF) else Color.White).padding(8.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(6.dp)){Icon(icon,null,tint=TourGreen,modifier=Modifier.size(25.dp));Text(label,fontSize=11.sp,color=TourInk,fontWeight=FontWeight.Bold)}}

/** Tiffin, warm food and gently rising steam, drawn locally rather than downloaded. */
@Composable private fun TourTiffin(phase:Float,modifier:Modifier){Canvas(modifier){
    val w=size.width;val h=size.height
    drawRoundRect(TourGreen,topLeft=androidx.compose.ui.geometry.Offset(w*.12f,h*.49f),size=androidx.compose.ui.geometry.Size(w*.76f,h*.37f),cornerRadius=androidx.compose.ui.geometry.CornerRadius(w*.13f))
    drawOval(Color(0xFFD2E5B7),topLeft=androidx.compose.ui.geometry.Offset(w*.12f,h*.39f),size=androidx.compose.ui.geometry.Size(w*.76f,h*.27f))
    drawOval(Color(0xFFFFF4CC),topLeft=androidx.compose.ui.geometry.Offset(w*.22f,h*.43f),size=androidx.compose.ui.geometry.Size(w*.36f,h*.17f))
    drawCircle(Color(0xFFE9A044),w*.11f,androidx.compose.ui.geometry.Offset(w*.66f,h*.53f))
    repeat(3){i->val x=w*(.32f+i*.18f);val y=h*(.37f-phase*.14f);val steam=Path().apply{moveTo(x,y);cubicTo(x-w*.12f,y-h*.11f,x+w*.12f,y-h*.13f,x,y-h*.25f)};drawPath(steam,Color.White.copy(alpha=.8f),style=Stroke(width=w*.038f))}
}}
