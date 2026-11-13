package com.zomeal.provider
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.math.BigDecimal

@Composable internal fun BikiPackSize(item:String,quantity:String,enabled:Boolean,onApply:(BigDecimal,String)->Unit){
 var size by rememberSaveable(item,quantity){mutableStateOf("")}
 var unit by rememberSaveable(item,quantity){mutableStateOf("g")}
 var expanded by remember { mutableStateOf(false) }
 Text("$item · How much is in ONE packet / bag? Total price stays unchanged.",style=MaterialTheme.typography.bodySmall)
 Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
  OutlinedTextField(size,{size=it},enabled=enabled,label={Text("Pack size")},modifier=Modifier.weight(1f),singleLine=true)
  Box{OutlinedButton(onClick={expanded=true},enabled=enabled){Text(unit)};DropdownMenu(expanded,{expanded=false}){listOf("g","kg","ml","litre","piece").forEach{u->DropdownMenuItem(text={Text(u)},onClick={unit=u;expanded=false})}}}
  TextButton(enabled=enabled&&size.toBigDecimalOrNull()?.signum()==1,onClick={size.toBigDecimalOrNull()?.let{onApply(it,unit)}}){Text("Apply")}
 }
}
