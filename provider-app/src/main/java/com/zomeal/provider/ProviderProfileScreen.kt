package com.zomeal.provider

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.util.Locale

private val PBrand=Color(0xFF087F43);private val PInk=Color(0xFF14221B);private val PMuted=Color(0xFF68736D);private val PMist=Color(0xFFF0F7F2)

@Composable fun ProviderProfileScreen(repository:SupabaseProviderRepository,onBack:()->Unit,onEarnings:()->Unit,onPayoutDetails:()->Unit,onSignOut:()->Unit){
 var data by remember{mutableStateOf<JSONObject?>(null)};var error by remember{mutableStateOf<String?>(null)}
 LaunchedEffect(Unit){repository.loadCommissionSummary{r,e->data=r;error=e}}
 Scaffold(modifier=Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),containerColor=Color(0xFFF7FAF7),topBar={Surface(shadowElevation=2.dp){Row(Modifier.fillMaxWidth().padding(8.dp),verticalAlignment=Alignment.CenterVertically){IconButton(onBack){Icon(Icons.Outlined.ArrowBack,"Back",tint=PBrand)};Text("Provider profile",fontSize=20.sp,fontWeight=FontWeight.Bold,color=PInk)}}}){padding->
  Column(Modifier.fillMaxSize().padding(padding).padding(16.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
   error?.let{Text(it,color=Color(0xFFB23A32))}
   if(data==null)CircularProgressIndicator(color=PBrand) else {val c=data!!;Text(c.optString("provider_name"),fontSize=23.sp,fontWeight=FontWeight.Bold,color=PInk)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color.White).padding(18.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){Row(verticalAlignment=Alignment.CenterVertically){Icon(Icons.Outlined.Percent,null,tint=PBrand);Spacer(Modifier.width(8.dp));Text("Commission agreement",fontSize=16.sp,fontWeight=FontWeight.Bold,color=PInk)};Text("${rate(c.optDouble("rate_percent",14.0))}%",fontSize=32.sp,fontWeight=FontWeight.ExtraBold,color=PBrand);Text(if(c.optBoolean("agreed_by_provider"))"Negotiated and marked agreed" else "Default Zomeal rate · negotiation available",fontSize=11.sp,color=PMuted);Text(c.optString("negotiation_note"),fontSize=11.sp,color=PMuted);Text("Commission is deducted once from gross delivered-meal value. Advances carry 0% commission and are recovered from your net earnings.",fontSize=11.sp,lineHeight=16.sp,color=PMuted)}
    Button(onEarnings,Modifier.fillMaxWidth().height(48.dp),colors=ButtonDefaults.buttonColors(containerColor=PBrand)){Icon(Icons.Outlined.AccountBalanceWallet,null);Spacer(Modifier.width(7.dp));Text("View earnings and advances")};OutlinedButton(onPayoutDetails,Modifier.fillMaxWidth().height(48.dp)){Icon(Icons.Outlined.AccountBalance,null);Spacer(Modifier.width(7.dp));Text("Payout details & verification")};OutlinedButton(onSignOut,Modifier.fillMaxWidth().height(48.dp)){Icon(Icons.Outlined.Logout,null);Spacer(Modifier.width(7.dp));Text("Sign out")}
   }
  }
 }
}
private fun rate(v:Double)=if(v%1.0==0.0)v.toInt().toString() else String.format(Locale.US,"%.2f",v).trimEnd('0').trimEnd('.')
