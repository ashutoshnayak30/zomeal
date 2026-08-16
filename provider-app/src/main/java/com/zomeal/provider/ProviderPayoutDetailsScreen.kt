package com.zomeal.provider

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

private val PayGreen=Color(0xFF087F45); private val PayInk=Color(0xFF112119); private val PayMuted=Color(0xFF65736B); private val PayMist=Color(0xFFF0F7F2)

@Composable fun ProviderPayoutDetailsScreen(repository:SupabaseProviderRepository,onBack:()->Unit){
    var destination by remember{mutableStateOf<JSONObject?>(null)};var loading by remember{mutableStateOf(true)};var error by remember{mutableStateOf<String?>(null)}
    var method by remember{mutableStateOf("UPI")};var holder by remember{mutableStateOf("")};var upi by remember{mutableStateOf("")};var account by remember{mutableStateOf("")};var ifsc by remember{mutableStateOf("")};var bank by remember{mutableStateOf("")};var note by remember{mutableStateOf("")}
    fun reload(){loading=true;repository.loadPayoutDestination{json,message->destination=json;error=message;loading=false}}
    LaunchedEffect(Unit){reload()}
    Scaffold(containerColor=Color(0xFFF7FAF7),topBar={Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).height(62.dp).background(Color.White).padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically){IconButton(onBack){Icon(Icons.Outlined.ArrowBack,null,tint=PayGreen)};Column{Text("Payout details",fontSize=20.sp,fontWeight=FontWeight.Bold,color=PayInk);Text("Secure settlement destination",fontSize=10.sp,color=PayMuted)}}}){padding->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
            if(loading){LinearProgressIndicator(Modifier.fillMaxWidth(),color=PayGreen);return@Column}
            destination?.takeIf{it.optString("status")!="NOT_ADDED"}?.let{d->
                val status=d.optString("status");Card(colors=CardDefaults.cardColors(containerColor=if(status=="VERIFIED")Color(0xFFE8F7EC) else Color(0xFFFFF6DE)),shape=RoundedCornerShape(18.dp)){Column(Modifier.padding(17.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){Row(verticalAlignment=Alignment.CenterVertically){Icon(if(status=="VERIFIED")Icons.Outlined.Verified else Icons.Outlined.Schedule,null,tint=PayGreen);Spacer(Modifier.width(8.dp));Text(status.replace('_',' '),fontWeight=FontWeight.Bold,color=PayInk)};Text(d.optString("masked_destination"),fontSize=16.sp,fontWeight=FontWeight.Bold,color=PayInk);Text("${d.optString("method").replace('_',' ')} · ${d.optString("account_holder_name")}",fontSize=11.sp,color=PayMuted);if(d.optString("admin_note").isNotBlank())Text("Zomeal note: ${d.optString("admin_note")}",fontSize=11.sp,color=Color(0xFFB13D32))}}
            }
            Card(colors=CardDefaults.cardColors(containerColor=Color.White),shape=RoundedCornerShape(18.dp)){Column(Modifier.padding(17.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                Text(if(destination?.optString("status")=="VERIFIED")"Change payout destination" else "Add payout destination",fontSize=17.sp,fontWeight=FontWeight.Bold,color=PayInk)
                Text("Changes are hidden from payouts until Zomeal verifies them. Your full details are never shown in ordinary provider lists or payout queues.",fontSize=11.sp,lineHeight=16.sp,color=PayMuted)
                Row(horizontalArrangement=Arrangement.spacedBy(9.dp)){listOf("UPI" to "UPI ID","BANK_TRANSFER" to "Bank account").forEach{(value,label)->FilterChip(method==value,{method=value},{Text(label)},leadingIcon={Icon(if(value=="UPI")Icons.Outlined.PhoneAndroid else Icons.Outlined.AccountBalance,null,Modifier.size(17.dp))})}}
                PayField(holder,{holder=it},"Account holder name")
                if(method=="UPI")PayField(upi,{upi=it.lowercase().replace(" ","")},"UPI ID (example: name@bank)") else {PayField(account,{account=it.filter(Char::isDigit).take(20)},"Bank account number",KeyboardType.Number);PayField(ifsc,{ifsc=it.uppercase().replace(" ","").take(11)},"IFSC code");PayField(bank,{bank=it},"Bank name (optional)")}
                PayField(note,{note=it.take(200)},"Note for Zomeal (optional)")
                Button({loading=true;error=null;repository.savePayoutDestination(method,holder,upi,account,ifsc,bank,note){result->loading=false;if(result.success)reload() else error=result.message}},enabled=!loading&&holder.isNotBlank()&&((method=="UPI"&&upi.contains('@'))||(method=="BANK_TRANSFER"&&account.length>=6&&ifsc.length==11)),modifier=Modifier.fillMaxWidth().height(50.dp),colors=ButtonDefaults.buttonColors(containerColor=PayGreen),shape=RoundedCornerShape(14.dp)){Icon(Icons.Outlined.Lock,null);Spacer(Modifier.width(7.dp));Text("Submit securely for verification",fontWeight=FontWeight.Bold)}
            }}
            error?.let{Text(it,color=MaterialTheme.colorScheme.error,fontSize=11.sp)}
            Row(Modifier.fillMaxWidth().background(PayMist,RoundedCornerShape(14.dp)).padding(14.dp),verticalAlignment=Alignment.Top){Icon(Icons.Outlined.Security,null,tint=PayGreen);Spacer(Modifier.width(9.dp));Text("Payout requests use only a verified destination. If you update it later, payouts pause until the new details are approved.",fontSize=11.sp,lineHeight=16.sp,color=PayMuted)}
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

@Composable private fun PayField(value:String,onChange:(String)->Unit,label:String,type:KeyboardType=KeyboardType.Text){OutlinedTextField(value,onChange,label={Text(label)},singleLine=true,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(13.dp),keyboardOptions=KeyboardOptions(keyboardType=type),colors=OutlinedTextFieldDefaults.colors(focusedBorderColor=PayGreen))}
