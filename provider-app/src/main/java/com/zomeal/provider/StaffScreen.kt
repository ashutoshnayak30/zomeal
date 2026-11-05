package com.zomeal.provider

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.json.JSONObject
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

private fun staffMoney(p:Long)=NumberFormat.getCurrencyInstance(Locale("en","IN")).format(BigDecimal(p).movePointLeft(2))
private fun staffRows(o:JSONObject?,key:String)=o?.optJSONArray(key)?.let{a->(0 until a.length()).map{a.getJSONObject(it)}}.orEmpty()
private fun staffToday()=LocalDate.now(ZoneId.of("Asia/Kolkata"))

@Composable internal fun StaffScreen(repository:SupabaseProviderRepository,onClose:()->Unit){
 var month by remember{mutableStateOf(YearMonth.from(staffToday()))}
 var data by remember{mutableStateOf<JSONObject?>(null)}
 var busy by remember{mutableStateOf(false)}
 var error by remember{mutableStateOf<String?>(null)}
 var edit by remember{mutableStateOf<JSONObject?>(null)}
 var action by remember{mutableStateOf("")}
 var target by remember{mutableStateOf<JSONObject?>(null)}
 var expanded by remember{mutableStateOf("")}
 var sheet by remember{mutableStateOf(false)}
 var selectedStaff by remember{mutableStateOf("")}
 fun load(){busy=true;error=null;repository.staffMonth(month.atDay(1).toString()){d,e->data=d;error=e;busy=false}}
 LaunchedEffect(month){data=null;load()}
 StaffFrame("Staff & salaries",onClose){
  Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){TextButton(onClick={month=month.minusMonths(1)},enabled=!busy&&month>YearMonth.of(2020,1)){Text("‹ Previous",fontSize=12.sp)};Text(month.format(java.time.format.DateTimeFormatter.ofPattern("MMM yyyy",Locale.ENGLISH)),fontWeight=FontWeight.Bold,fontSize=14.sp);TextButton(onClick={month=month.plusMonths(1)},enabled=!busy&&month<YearMonth.from(staffToday())){Text("Next ›",fontSize=12.sp)}}
  Text("Present = full pay · Half-day = half pay · Absent = unpaid. Allowed leave is paid. Review final salary after month-end.",fontSize=12.sp)
  Button(onClick={sheet=true},enabled=!busy&&data!=null,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(12.dp)){Text("Open staff sheet · add / edit team")}
  if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
  error?.let{Text(it,color=MaterialTheme.colorScheme.error);TextButton(onClick={load()}){Text("Retry")}}
  val rows=staffRows(data,"staff")
  if(data!=null)Text("${rows.count{it.isNull("left_on")||it.optString("left_on")>=staffToday().toString()}} active staff · ${rows.size} total records",fontSize=12.sp)
  if(data!=null&&rows.isEmpty())Text("Add your cook, helpers, packers and delivery staff. Salary costs will appear in Kitchen Costs only after you approve a month.",fontSize=13.sp)
  val approved=rows.sumOf{it.optJSONObject("payroll")?.optLong("amount_paise")?:0}
  val paid=rows.sumOf{it.optJSONObject("payroll")?.optLong("paid_paise")?:0}
  StaffCard{
   Text("Approved ${staffMoney(approved)} · Recorded payments ${staffMoney(paid)}",fontSize=12.sp)
   Text("Remaining payable ${staffMoney(approved-paid)}",fontWeight=FontWeight.Bold)
   Text("This is bookkeeping, not a bank transfer. Payments and advances do not add expenses again.",fontSize=11.sp)
  }
  if(rows.isNotEmpty()){
   Text("Team register · swipe sideways for salary and actions",fontSize=12.sp)
   Column(Modifier.horizontalScroll(rememberScrollState())){
    Row(Modifier.background(Color(0xFF087F43))){listOf("Name" to 130,"Position" to 110,"Monthly salary" to 125,"Action" to 105).forEach{(label,width)->Text(label,Modifier.width(width.dp).padding(10.dp),fontSize=11.sp,color=Color.White,fontWeight=FontWeight.Bold)}}
    rows.forEachIndexed{i,s->Row(Modifier.background(if(i%2==0)Color.White else Color(0xFFEAF5EE))){Text(s.optString("name"),Modifier.width(130.dp).padding(10.dp),fontSize=12.sp);Text(s.optString("position"),Modifier.width(110.dp).padding(10.dp),fontSize=12.sp);Text(staffMoney(s.optLong("monthly_salary_paise")),Modifier.width(125.dp).padding(10.dp),fontSize=12.sp);TextButton(onClick={selectedStaff=s.getString("id")},modifier=Modifier.width(105.dp)){Text("Manage ↓",fontSize=12.sp)}}}
   }
  }
  listOfNotNull(rows.find{it.optString("id")==selectedStaff}?:rows.firstOrNull()).forEach{s->val payroll=s.optJSONObject("payroll");val id=s.getString("id")
   StaffCard{
    Text("${s.optString("name")} · ${s.optString("position")}",fontWeight=FontWeight.Bold,fontSize=15.sp)
    val left=s.optString("left_on").takeUnless{it=="null"||it.isBlank()}
    Text("${staffMoney(s.optLong("monthly_salary_paise"))} / month · Joined ${s.optString("joined_on")}${left?.let{" · Left $it"}.orEmpty()}",fontSize=12.sp)
    if(s.optString("phone").isNotBlank())Text(s.optString("phone"),fontSize=12.sp)
    TextButton(onClick={edit=s},enabled=!busy){Text("Edit staff details")}
    val attendance=staffRows(s,"attendance")
    Text("Attendance: ${attendance.count{it.optString("status")=="PRESENT"}} present · ${attendance.count{it.optString("status")=="ABSENT"}} absent · ${attendance.count{it.optString("status")=="HALF_DAY"}} half-day · ${attendance.count{it.optString("status")=="LEAVE"}} leave",fontSize=12.sp)
    Button(onClick={target=s;action="attendance"},enabled=!busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp),shape=RoundedCornerShape(12.dp)){Text("✓  Mark attendance",fontWeight=FontWeight.SemiBold)}
    if(payroll==null){
     Text("No salary approved for this month",fontSize=12.sp)
     val summary=s.optJSONObject("attendance_summary")
     Text("Attendance-based estimate: ${staffMoney(summary?.optLong("estimated_salary_paise")?:0)} · ${summary?.optInt("unmarked")?:0} unmarked days",fontSize=12.sp)
     Button(onClick={target=s;action="approve"},enabled=!busy&&summary?.optBoolean("month_ended")==true&&summary.optBoolean("policy_set")&&summary.optInt("unmarked")==0){Text("Review & approve salary")}
     Text("Approval requires month-end, a leave allowance and no unmarked employment days. Excess leave is reviewed by the owner.",fontSize=11.sp)
    }else{
     val remaining=payroll.optLong("amount_paise")-payroll.optLong("paid_paise")
     Text("Approved: ${staffMoney(payroll.optLong("amount_paise"))} · Owed: ${staffMoney(remaining)}",fontSize=13.sp,fontWeight=FontWeight.Bold)
     Text("Linked to Kitchen Costs once. Approval is locked; profile salary changes apply to unapproved months only.",fontSize=11.sp)
     Button(onClick={target=s;action="payment"},enabled=!busy&&remaining>0){Text("Record payment / advance")}
    }
    TextButton(onClick={expanded=if(expanded==id)"" else id}){Text(if(expanded==id)"Hide history" else "View attendance & payments")}
    if(expanded==id){
     attendance.forEach{Text("${it.optString("day")} · ${it.optString("status").replace('_',' ')}",fontSize=12.sp)}
     staffRows(s,"payments").forEach{Text("${it.optString("day")} · ${it.optString("kind")} · ${staffMoney(it.optLong("amount_paise"))}\n${it.optString("note")}",fontSize=12.sp)}
     if(attendance.isEmpty()&&staffRows(s,"payments").isEmpty())Text("No records for this month.",fontSize=12.sp)
    }
   }
  }
 }
 if(sheet)StaffSheet(staffRows(data,"staff"),onClose={sheet=false},onSave={rows,done->repository.saveStaffSheet(rows){_,e->done(e);if(e==null){sheet=false;load()}}})
 edit?.let{s->StaffEdit(s,onClose={edit=null},onSave={p,done->repository.saveStaff(p){_,e->done(e);if(e==null){edit=null;load()}}})}
 target?.let{s->StaffAction(s,month,action,onClose={target=null},onSave={p,done->val callback:(JSONObject?,String?)->Unit={_,e->done(e);if(e==null){target=null;load()}};when(action){"approve"->repository.approveStaffSalary(p,callback);"payment"->repository.payStaffSalary(p,callback);else->repository.staffAttendance(p,callback)}})}
}

@Composable private fun StaffEdit(s:JSONObject,onClose:()->Unit,onSave:(JSONObject,(String?)->Unit)->Unit){
 val id=remember{s.optString("id").ifBlank{UUID.randomUUID().toString()}}
 var name by remember{mutableStateOf(s.optString("name"))};var role by remember{mutableStateOf(s.optString("position"))}
 var phone by remember{mutableStateOf(s.optString("phone"))}
 var amount by remember{mutableStateOf(s.optLong("monthly_salary_paise").takeIf{it>0}?.let{BigDecimal(it).movePointLeft(2).toPlainString()}.orEmpty())}
 var joined by remember{mutableStateOf(s.optString("joined_on").ifBlank{staffToday().toString()})}
 var left by remember{mutableStateOf(s.optString("left_on").takeUnless{it=="null"}.orEmpty())}
 var leavePeriod by remember{mutableStateOf(s.optString("leave_period").takeIf{it=="WEEKLY"}?:"MONTHLY")}
 var leaveDays by remember{mutableStateOf(if(s.isNull("leave_days"))"" else s.optInt("leave_days").toString())}
 var busy by remember{mutableStateOf(false)};var error by remember{mutableStateOf<String?>(null)}
 StaffFrame("Staff profile",{if(!busy)onClose()}){
  StaffInput("Name",name){name=it};StaffInput("Role · cook, helper, packer, delivery…",role){role=it};StaffInput("Phone (optional)",phone){phone=it}
  StaffInput("Monthly salary ₹",amount,true){amount=it};StaffInput("Joined · YYYY-MM-DD",joined){joined=it};StaffInput("Leaving date · optional YYYY-MM-DD",left){left=it}
  Text("Paid leave allowance",fontSize=13.sp,fontWeight=FontWeight.Bold)
  Row{FilterChip(leavePeriod=="MONTHLY",{leavePeriod="MONTHLY"},label={Text("Per month")});Spacer(Modifier.width(8.dp));FilterChip(leavePeriod=="WEEKLY",{leavePeriod="WEEKLY"},label={Text("Per week")})}
  StaffInput("Allowed leave days · 0 means no paid leave",leaveDays,true){leaveDays=it}
  Text("Weekly = Monday–Sunday, with no carry-over. Only days marked Leave use the allowance. Excess leave needs owner review. Changes recalculate unapproved previews, not approved salary records.",fontSize=11.sp)
  Text("Set a leaving date to stop future salary approvals. Existing salary and payment history stays unchanged. This version supports monthly salaries, not daily-wage payroll.",fontSize=12.sp)
  error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
  Button(enabled=!busy,onClick={
   val salary=kitchenPaise(amount);val j=runCatching{LocalDate.parse(joined)}.getOrNull();val l=if(left.isBlank())null else runCatching{LocalDate.parse(left)}.getOrNull()
   if(name.isBlank()||role.isBlank()||salary==null||salary<=0||j==null||(!left.isBlank()&&l==null)||j>staffToday()||(l!=null&&l<j)){error="Check name, role, salary and dates.";return@Button}
   val quota=leaveDays.toIntOrNull();if(quota==null||quota<0||quota>(if(leavePeriod=="WEEKLY")7 else 31)){error="Enter a valid paid leave allowance.";return@Button}
   busy=true;error=null
   onSave(JSONObject().put("id",id).put("revision",s.optInt("revision")).put("name",name).put("position",role).put("phone",phone).put("salary",salary).put("joined_on",joined).put("left_on",left).put("leave_period",leavePeriod).put("leave_days",quota)){busy=false;error=it}
  },modifier=Modifier.fillMaxWidth()){Text(if(busy)"Saving…" else "Save staff")}
 }
}

@Composable private fun StaffAction(s:JSONObject,month:YearMonth,action:String,onClose:()->Unit,onSave:(JSONObject,(String?)->Unit)->Unit){
 val requestId=remember{UUID.randomUUID().toString()};val payroll=s.optJSONObject("payroll")
 val start=maxOf(month.atDay(1),LocalDate.parse(s.getString("joined_on")))
 val end=minOf(month.atEndOfMonth(),runCatching{LocalDate.parse(s.optString("left_on"))}.getOrNull()?:month.atEndOfMonth())
 val employed=(java.time.temporal.ChronoUnit.DAYS.between(start,end)+1).coerceAtLeast(0)
 val suggested=s.optJSONObject("attendance_summary")?.optLong("estimated_salary_paise")?:0L
 var amount by remember{mutableStateOf(if(action=="approve")BigDecimal(suggested).movePointLeft(2).toPlainString() else "")}
 var date by remember{mutableStateOf((if(action=="payment")staffToday() else minOf(month.atEndOfMonth(),staffToday())).toString())}
 var note by remember{mutableStateOf("")};var kind by remember{mutableStateOf(if(action=="attendance")"PRESENT" else "PAYMENT")}
 var busy by remember{mutableStateOf(false)};var error by remember{mutableStateOf<String?>(null)}
 StaffFrame(when(action){"approve"->"Approve ${month} salary";"payment"->"Record money already paid";else->"Record attendance"},{if(!busy)onClose()}){
  Text(s.optString("name"),fontWeight=FontWeight.Bold)
  if(action=="approve")Text("Attendance calculation: ${staffMoney(suggested)}. Monthly salary ÷ ${month.lengthOfMonth()} × paid-day equivalents. Excess leave: ${s.optJSONObject("attendance_summary")?.optInt("excess_leave")?:0} days (excluded pending your decision). Explain any excess-leave decision or change to the amount in the note. Approval is locked.",fontSize=12.sp)
  if(action!="approve")StaffInput("Date · YYYY-MM-DD",date){date=it}
  if(action!="attendance"){
   StaffInput(if(action=="approve")"Final salary for this month ₹" else "Amount paid ₹",amount,true){amount=it}
   if(action=="payment")Text("Remaining ${staffMoney((payroll?.optLong("amount_paise")?:0)-(payroll?.optLong("paid_paise")?:0))}. An advance here is money already paid toward this approved salary month—not an extra expense or a transfer.",fontSize=12.sp)
   StaffInput("Note / payment reference",note){note=it}
  }
  if(action!="approve"){
   (if(action=="attendance")listOf("PRESENT","ABSENT","HALF_DAY","LEAVE") else listOf("PAYMENT","ADVANCE")).forEach{k->FilterChip(selected=kind==k,onClick={kind=k},label={Text(k.replace('_',' '))})}
  }
  error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
  Button(enabled=!busy,onClick={
   val value=kitchenPaise(amount);val day=runCatching{LocalDate.parse(date)}.getOrNull()
   if(action!="attendance"&&(value==null||value<0||(action=="payment"&&value==0L))){error="Enter a valid amount.";return@Button}
   if(action!="approve"&&(day==null||day>staffToday()||(action=="attendance"&&YearMonth.from(day)!=month))){error="Check the date. Attendance must be within the selected month.";return@Button}
   val p=JSONObject().put("id",requestId).put("staff_id",s.getString("id")).put("month",month.atDay(1).toString()).put("amount",value).put("day",date).put("kind",kind).put("status",kind).put("note",note)
   if(action=="approve")p.put("attendance_signature",s.optJSONObject("attendance_summary")?.optString("signature"))
   if(action=="payment")p.put("payroll_id",payroll!!.getString("id"))
   busy=true;error=null;onSave(p){busy=false;error=it}
  },modifier=Modifier.fillMaxWidth()){Text(if(busy)"Saving…" else when(action){"approve"->"Confirm salary & add to Costs";"payment"->"Confirm payment record";else->"Save attendance"})}
 }
}

@Composable internal fun StaffFrame(title:String,onClose:()->Unit,body:@Composable ColumnScope.()->Unit){
 Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=true)){
  Surface(Modifier.fillMaxSize().padding(WindowInsets.safeDrawing.union(WindowInsets.ime).asPaddingValues()),color=MaterialTheme.colorScheme.background){
   Column{Row(Modifier.fillMaxWidth().padding(12.dp)){Text(title,Modifier.weight(1f),fontSize=18.sp,fontWeight=FontWeight.Bold);TextButton(onClick=onClose){Text("Close")}}
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp).padding(bottom=32.dp),verticalArrangement=Arrangement.spacedBy(12.dp),content=body)
   }
  }
 }
}
@Composable private fun StaffCard(body:@Composable ColumnScope.()->Unit){Surface(shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surface){Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp),content=body)}}
@Composable private fun StaffInput(label:String,value:String,number:Boolean=false,onChange:(String)->Unit){OutlinedTextField(value,onChange,label={Text(label,fontSize=12.sp)},modifier=Modifier.fillMaxWidth(),singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=if(number)KeyboardType.Decimal else KeyboardType.Text),shape=RoundedCornerShape(12.dp))}
