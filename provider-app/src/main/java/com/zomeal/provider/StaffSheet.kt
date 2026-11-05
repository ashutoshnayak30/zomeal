package com.zomeal.provider

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

private fun staffSheetRow(s:JSONObject)=JSONObject().put("id",s.optString("id").ifBlank{UUID.randomUUID().toString()})
 .put("revision",s.optInt("revision")).put("name",s.optString("name")).put("position",s.optString("position"))
 .put("phone",s.optString("phone")).put("amount",if(s.has("monthly_salary_paise"))BigDecimal(s.optLong("monthly_salary_paise")).movePointLeft(2).toPlainString() else "")
 .put("joined_on",s.optString("joined_on").ifBlank{LocalDate.now(ZoneId.of("Asia/Kolkata")).toString()})
 .put("left_on",s.optString("left_on").takeUnless{it=="null"}.orEmpty()).put("leave_period",s.optString("leave_period").takeIf{it=="WEEKLY"}?:"MONTHLY").put("leave_days",if(s.isNull("leave_days"))"" else s.optInt("leave_days").toString())

@Composable internal fun StaffSheet(staff:List<JSONObject>,onClose:()->Unit,onSave:(JSONArray,(String?)->Unit)->Unit){
 val original=remember{staff.map(::staffSheetRow)}
 var draft by rememberSaveable{mutableStateOf(JSONArray(original.ifEmpty{listOf(staffSheetRow(JSONObject()))}).toString())}
 var optional by rememberSaveable{mutableStateOf(false)}
 var busy by remember{mutableStateOf(false)};var locked by remember{mutableStateOf(false)}
 var error by remember{mutableStateOf<String?>(null)};var discard by remember{mutableStateOf(false)}
 val rows=JSONArray(draft).let{a->(0 until a.length()).map{a.getJSONObject(it)}}
 val changed=rows.filter{r->original.none{it.toString()==r.toString()}&&listOf("name","position","amount","phone").any{r.optString(it).isNotBlank()}}
 fun close(){if(!busy){if(changed.isNotEmpty())discard=true else onClose()}}
 fun update(id:String,key:String,value:String){draft=JSONArray(rows.map{JSONObject(it.toString()).apply{if(optString("id")==id)put(key,value)}}).toString()}
 StaffFrame("Staff entry sheet",::close){
  Text("One person per row. Fill name, position and monthly salary, then save together.",fontSize=12.sp)
  Text("Set paid leave for each person: e.g. 1 per week or 4 per month. Weekly means Monday–Sunday; unused leave does not carry forward. Enter 0 for no paid leave.",fontSize=11.sp)
  Text("Swipe sideways for more columns. Joining date defaults to today—check it for existing staff. Editing profiles never changes approved salaries.",fontSize=11.sp,color=Color.DarkGray)
  OutlinedButton(onClick={optional=!optional}){Text(if(optional)"Hide optional columns" else "+ Phone & employment dates",fontSize=12.sp)}
  Column(Modifier.horizontalScroll(rememberScrollState())){
   Row(Modifier.background(Color(0xFF087F43))){listOf("Name" to 135,"Position" to 115,"Salary ₹ / month" to 125).plus(if(optional)listOf("Phone · optional" to 145,"Joined YYYY-MM-DD" to 165,"Left · optional" to 165) else emptyList()).plus(listOf("Paid leave days" to 115,"Allowance period" to 145)).forEach{(label,width)->Text(label,Modifier.width(width.dp).padding(10.dp),fontSize=11.sp,fontWeight=FontWeight.Bold,color=Color.White)}}
   rows.forEachIndexed{index,r->key(r.getString("id")){
    Row(Modifier.background(if(index%2==0)Color.White else Color(0xFFEAF5EE)).padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically){
     listOf(Triple("name","Name",135),Triple("position","Cook / helper…",115),Triple("amount","0.00",125)).plus(if(optional)listOf(Triple("phone","Optional",145),Triple("joined_on","YYYY-MM-DD",165),Triple("left_on","Optional",165)) else emptyList()).forEach{(field,hint,width)->
      OutlinedTextField(r.optString(field),{update(r.getString("id"),field,it)},enabled=!locked,modifier=Modifier.width(width.dp).padding(horizontal=3.dp),singleLine=true,textStyle=TextStyle(fontSize=12.sp),placeholder={Text(hint,fontSize=11.sp)},shape=RoundedCornerShape(8.dp),keyboardOptions=KeyboardOptions(keyboardType=if(field=="amount")KeyboardType.Decimal else if(field=="phone")KeyboardType.Phone else KeyboardType.Text))
     }
     OutlinedTextField(r.optString("leave_days"),{update(r.getString("id"),"leave_days",it)},enabled=!locked,modifier=Modifier.width(115.dp).padding(3.dp),singleLine=true,textStyle=TextStyle(fontSize=12.sp),placeholder={Text("e.g. 4",fontSize=11.sp)},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
     OutlinedButton(onClick={update(r.getString("id"),"leave_period",if(r.optString("leave_period")=="MONTHLY")"WEEKLY" else "MONTHLY")},enabled=!locked,modifier=Modifier.width(145.dp)){Text(if(r.optString("leave_period")=="WEEKLY")"Per week ⇄" else "Per month ⇄",fontSize=12.sp)}
    }
   }}
  }
  OutlinedButton(onClick={draft=JSONArray(rows+staffSheetRow(JSONObject())).toString()},enabled=!locked&&rows.size<50){Text("+ Add row")}
  Text("${changed.size} changed rows · total monthly salaries ₹${BigDecimal(rows.sumOf{kitchenPaise(it.optString("amount"))?:0}).movePointLeft(2).toPlainString()}",fontWeight=FontWeight.SemiBold,fontSize=12.sp)
  Text("Saving this sheet updates staff profiles only. Approve salary months separately to include them in Costs.",fontSize=11.sp,color=Color.DarkGray)
  error?.let{Text(it,color=MaterialTheme.colorScheme.error,fontSize=12.sp)}
  Button(modifier=Modifier.fillMaxWidth().heightIn(min=48.dp),enabled=!busy&&changed.isNotEmpty(),onClick={
   val payload=JSONArray();var issue:String?=null
   changed.forEach{r->val amount=kitchenPaise(r.optString("amount"));val joined=runCatching{LocalDate.parse(r.optString("joined_on"))}.getOrNull();val left=runCatching{LocalDate.parse(r.optString("left_on"))}.getOrNull()
    if(r.optString("name").isBlank()||r.optString("name").length>80||r.optString("position").isBlank()||r.optString("position").length>60||r.optString("phone").length>20||amount==null||amount<=0||joined==null||joined>LocalDate.now(ZoneId.of("Asia/Kolkata"))||(r.optString("left_on").isNotBlank()&&(left==null||left<joined))){issue="Check ${r.optString("name").ifBlank{"the unnamed row"}}: name, position, positive salary and valid dates are required."}
    val quota=r.optString("leave_days").toIntOrNull();if(quota==null||quota<0||quota>(if(r.optString("leave_period")=="WEEKLY")7 else 31))issue="Enter a valid leave allowance for ${r.optString("name")}."
    payload.put(JSONObject(r.toString()).removeAmount().put("salary",amount).put("leave_days",quota))
   }
   if(issue!=null){error=issue;optional=true}else{busy=true;locked=true;error=null;onSave(payload){e->busy=false;error=e?.plus(" Retry keeps the same rows to avoid duplicates. Close and reopen the sheet to edit after refreshing.")}}
  },shape=RoundedCornerShape(12.dp)){Text(if(busy)"Saving staff…" else if(locked)"Retry save" else "Save staff sheet")}
 }
 if(discard)AlertDialog(onDismissRequest={discard=false},title={Text("Leave staff sheet?")},text={Text("Unsaved changes will be lost. If a save failed, refresh before editing again.")},confirmButton={TextButton(onClick=onClose){Text("Leave")}},dismissButton={TextButton(onClick={discard=false}){Text("Keep editing")}})
}
private fun JSONObject.removeAmount():JSONObject{remove("amount");return this}
