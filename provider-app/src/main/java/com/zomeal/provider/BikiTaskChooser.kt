package com.zomeal.provider

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable internal fun BikiTaskChooser(onClose:()->Unit,onTask:(String)->Unit,tools:@Composable ()->Unit){
 var expanded by remember { mutableStateOf(false) }
 Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)){
  Surface(color=Color(0xFFF5F9F6),modifier=Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.ime))){
   Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).padding(bottom=56.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
    Row(verticalAlignment=Alignment.CenterVertically){Icon(Icons.Outlined.SmartToy,null,tint=Color(0xFF087F43));Text(" BIKI",Modifier.weight(1f),fontSize=26.sp,fontWeight=FontWeight.Bold,color=Color(0xFF087F43));TextButton(onClick=onClose){Text("Close")}}
    Text("Let's make your sheet",fontSize=28.sp,lineHeight=34.sp,fontWeight=FontWeight.Bold,color=Color(0xFF183D2C))
    Text("1 Choose a task  →  2 Speak  →  3 Review",fontSize=13.sp,fontWeight=FontWeight.SemiBold,color=Color(0xFF087F43))
    Text("Tap one option below to get started.",color=Color(0xFF63746A))
    BikiTaskTile("Opening stock","Items you have before cooking","Record opening stock",Icons.Outlined.Inventory2){onTask("OPEN")}
    BikiTaskTile("Closing stock","Items left after cooking · no prices needed","Record remaining stock",Icons.Outlined.FactCheck){onTask("CLOSE")}
    BikiTaskTile("Purchases","Items you bought and their total cost","Record purchases",Icons.Outlined.ShoppingBasket){onTask("PURCHASE")}
    BikiTaskTile("Recipe ingredients","Teach BIKI quantities for a menu dish","Choose a dish",Icons.Outlined.Restaurant){onTask("RECIPE")}
    Surface(color=Color(0xFFE6F3EB),shape=RoundedCornerShape(16.dp)){Text("Your saved voice language appears before recording. Change it there anytime.",Modifier.fillMaxWidth().padding(16.dp),fontSize=13.sp,color=Color(0xFF345B43))}
    TextButton(onClick={expanded=!expanded},modifier=Modifier.fillMaxWidth()){Text(if(expanded)"Hide kitchen tools & history ↑" else "Kitchen tools & history ↓")}
    if(expanded)tools()
    Text("No background listening. Nothing is saved until you review and confirm.",fontSize=12.sp,color=Color(0xFF63746A))
   }
  }
 }
}
@Composable private fun BikiTaskTile(title:String,subtitle:String,action:String,icon:ImageVector,onClick:()->Unit){
 Card(onClick=onClick,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(20.dp),border=BorderStroke(1.dp,Color(0xFFCDE5D6)),colors=CardDefaults.cardColors(containerColor=Color.White)){
  Row(Modifier.fillMaxWidth().padding(16.dp),horizontalArrangement=Arrangement.spacedBy(14.dp),verticalAlignment=Alignment.CenterVertically){
   Surface(color=Color(0xFFE6F3EB),shape=RoundedCornerShape(14.dp)){
    Icon(icon,null,Modifier.padding(12.dp).size(24.dp),tint=Color(0xFF087F43))
   }
   Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)){
    Text(title,fontWeight=FontWeight.Bold,fontSize=17.sp,color=Color(0xFF183D2C))
    Text(subtitle,fontSize=12.sp,color=Color(0xFF63746A))
    Text(action,fontSize=13.sp,fontWeight=FontWeight.SemiBold,color=Color(0xFF087F43))
   }
   Icon(Icons.Outlined.ChevronRight,null,tint=Color(0xFF087F43))
  }
 }
}
