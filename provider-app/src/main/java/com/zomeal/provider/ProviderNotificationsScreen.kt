package com.zomeal.provider

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val NBrand=Color(0xFF087F43);private val NInk=Color(0xFF14221B);private val NMuted=Color(0xFF68736D);private val NMist=Color(0xFFF0F7F2)

@Composable fun ProviderNotificationsScreen(repository:SupabaseProviderRepository,onBack:()->Unit,onOpen:(String)->Unit){
    var feed by remember{mutableStateOf<JSONObject?>(null)};var loading by remember{mutableStateOf(true)};var error by remember{mutableStateOf<String?>(null)}
    fun load(){loading=true;repository.loadNotifications{result,problem->feed=result;error=problem;loading=false}}
    LaunchedEffect(Unit){load()}
    val notifications=jsonObjects(feed?.optJSONArray("items"));val unread=feed?.optInt("unread_count")?:0
    Scaffold(containerColor=Color(0xFFF7FAF7),topBar={Surface(shadowElevation=2.dp){Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).height(64.dp).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically){IconButton(onBack){Icon(Icons.Outlined.ArrowBack,"Back",tint=NBrand)};Column(Modifier.weight(1f)){Text("Notifications",fontSize=20.sp,fontWeight=FontWeight.Bold,color=NInk);Text("$unread unread updates",fontSize=10.sp,color=NMuted)};if(unread>0)TextButton({repository.markNotificationsRead{result->if(result.success)load()}}){Text("Mark all read",color=NBrand,fontSize=11.sp,fontWeight=FontWeight.Bold)}}}}){padding->
        LazyColumn(Modifier.fillMaxSize().padding(padding),contentPadding=PaddingValues(15.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
            if(loading)item{Box(Modifier.fillMaxWidth().height(180.dp),contentAlignment=Alignment.Center){CircularProgressIndicator(color=NBrand)}}
            error?.let{item{NoticeCard(it,true)}}
            if(!loading&&notifications.isEmpty())item{Column(Modifier.fillMaxWidth().padding(top=80.dp),horizontalAlignment=Alignment.CenterHorizontally){Box(Modifier.size(76.dp).clip(CircleShape).background(NMist),contentAlignment=Alignment.Center){Icon(Icons.Outlined.NotificationsNone,null,tint=NBrand,modifier=Modifier.size(36.dp))};Spacer(Modifier.height(14.dp));Text("You're all caught up",fontSize=18.sp,fontWeight=FontWeight.Bold,color=NInk);Text("Provider, finance and operations updates will appear here.",fontSize=11.sp,color=NMuted)}}
            items(notifications,key={it.optString("id")}){item->NotificationCard(item){if(item.isNull("read_at")){repository.markNotificationsRead(item.optString("id")){}};onOpen(if(item.optString("entity_type")=="PROVIDER_CHANGE_REQUEST") "MANAGE_BUSINESS" else item.optString("destination"))}}
            item{Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))}
        }
    }
}

@Composable private fun NotificationCard(item:JSONObject,onClick:()->Unit){val unread=item.isNull("read_at");val icon=notificationIcon(item.optString("category"));Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if(unread)Color(0xFFEAF7EE) else Color.White).clickable(onClick=onClick).padding(14.dp),verticalAlignment=Alignment.Top){Box(Modifier.size(42.dp).clip(CircleShape).background(if(unread)NBrand else NMist),contentAlignment=Alignment.Center){Icon(icon,null,tint=if(unread)Color.White else NBrand,modifier=Modifier.size(21.dp))};Spacer(Modifier.width(11.dp));Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)){Row{Text(item.optString("title"),fontSize=13.sp,fontWeight=FontWeight.Bold,color=NInk,modifier=Modifier.weight(1f));if(unread)Box(Modifier.padding(top=4.dp).size(8.dp).clip(CircleShape).background(Color(0xFFE53935)))};Text(item.optString("message"),fontSize=11.sp,lineHeight=16.sp,color=NMuted);Text(formatNotificationTime(item.optString("created_at")),fontSize=9.sp,color=NBrand,fontWeight=FontWeight.SemiBold)};Icon(Icons.Outlined.ChevronRight,null,tint=NMuted,modifier=Modifier.padding(top=10.dp).size(18.dp))}}
private fun notificationIcon(category:String):ImageVector=when(category){"PAYOUT"->Icons.Outlined.Payments;"PAYOUT_DETAILS"->Icons.Outlined.AccountBalance;"ADVANCE"->Icons.Outlined.RequestQuote;"COMMISSION"->Icons.Outlined.Percent;"OPERATIONS"->Icons.Outlined.Restaurant;else->Icons.Outlined.Verified}
private fun jsonObjects(array:JSONArray?):List<JSONObject>{if(array==null)return emptyList();return(0 until array.length()).mapNotNull{array.optJSONObject(it)}}
private fun formatNotificationTime(value:String):String=try{DateTimeFormatter.ofPattern("dd MMM, h:mm a").withZone(ZoneId.systemDefault()).format(Instant.parse(value))}catch(_:Exception){value}
@Composable private fun NoticeCard(message:String,error:Boolean){Text(message,color=if(error)MaterialTheme.colorScheme.error else NBrand,fontSize=11.sp,modifier=Modifier.fillMaxWidth().background(if(error)Color(0xFFFFECEA) else NMist,RoundedCornerShape(13.dp)).padding(13.dp))}
