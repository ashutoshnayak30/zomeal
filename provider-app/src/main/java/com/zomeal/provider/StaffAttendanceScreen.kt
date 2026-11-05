package com.zomeal.provider

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun staffWeekStart(day:LocalDate):LocalDate=day.minusDays((day.dayOfWeek.value-1).toLong())
private val AttendanceGreen=Color(0xFF087F43)
private val AttendanceTint=Color(0xFFEAF5EE)
private val attendanceOptions=linkedMapOf("PRESENT" to "Present","ABSENT" to "Absent","HALF_DAY" to "Half-day","LEAVE" to "Leave")
private fun attendanceArray(o:JSONObject,key:String)=o.optJSONArray(key)?.let{a->(0 until a.length()).map{a.getJSONObject(it)}}.orEmpty()

@Composable internal fun StaffAttendanceScreen(repository:SupabaseProviderRepository,onClose:()->Unit){
 val today=LocalDate.now(ZoneId.of("Asia/Kolkata"))
 var monday by remember{mutableStateOf(staffWeekStart(today))}
 var staff by remember{mutableStateOf<List<JSONObject>>(emptyList())}
 var busy by remember{mutableStateOf(false)};var error by remember{mutableStateOf<String?>(null)}
 var loaded by remember{mutableStateOf(false)};var serial by remember{mutableIntStateOf(0)}
 var sheet by remember{mutableStateOf(false)};var salaries by remember{mutableStateOf(false)}
 var selectedStaff by remember{mutableStateOf<JSONObject?>(null)};var selectedDay by remember{mutableStateOf(today)}
 var saveError by remember{mutableStateOf<String?>(null)}
 val days=(0..6).map{monday.plusDays(it.toLong())}
 fun load(){
  val request=++serial;busy=true;loaded=false;error=null
  val months=days.filter{it<=today}.map{YearMonth.from(it)}.distinct()
  val merged=linkedMapOf<String,JSONObject>()
  fun fetch(index:Int){
   if(index==months.size){staff=merged.values.toList();busy=false;loaded=true;return}
   repository.staffMonth(months[index].atDay(1).toString()){result,problem->
    if(request==serial){
     if(problem!=null||result==null){error=problem?:"Attendance could not be loaded.";busy=false}
     else{attendanceArray(result,"staff").forEach{s->val id=s.getString("id");val old=merged[id];val records=(old?.let{attendanceArray(it,"attendance")}.orEmpty()+attendanceArray(s,"attendance")).distinctBy{it.optString("day")};val summaries=old?.let{attendanceArray(it,"month_summaries")}.orEmpty()+listOfNotNull(s.optJSONObject("attendance_summary"));merged[id]=JSONObject(s.toString()).put("attendance",JSONArray(records)).put("month_summaries",JSONArray(summaries.distinctBy{it.optString("month")}))};fetch(index+1)}
    }
   }
  }
  fetch(0)
 }
 LaunchedEffect(monday){load()}
 StaffFrame("Weekly attendance",onClose){
  Text("Tap a day to mark attendance. Staff names stay on the left while you swipe through Monday–Sunday.",fontSize=12.sp)
  Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
   OutlinedButton(onClick={monday=monday.minusWeeks(1)},enabled=!busy&&monday.minusWeeks(1)>=LocalDate.of(2020,1,1)){Text("‹ Previous",fontSize=12.sp)}
   TextButton(onClick={monday=staffWeekStart(today)},enabled=!busy){Text("This week",fontSize=12.sp)}
   OutlinedButton(onClick={monday=monday.plusWeeks(1)},enabled=!busy&&monday.plusWeeks(1)<=today){Text("Next ›",fontSize=12.sp)}
  }
  val dateFormat=DateTimeFormatter.ofPattern("d MMM yyyy",Locale.ENGLISH)
  Text("${monday.format(dateFormat)} – ${days.last().format(dateFormat)}",fontWeight=FontWeight.Bold,fontSize=14.sp,color=AttendanceGreen)
  Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
   Button(onClick={sheet=true},enabled=loaded&&!busy,modifier=Modifier.weight(1f)){Text("+ Add staff",fontSize=12.sp)}
   OutlinedButton(onClick={salaries=true},enabled=!busy,modifier=Modifier.weight(1f)){Text("Salaries & team",fontSize=12.sp)}
  }
  if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
  error?.let{Text(it,color=MaterialTheme.colorScheme.error,fontSize=12.sp);OutlinedButton(onClick={load()}){Text("Retry")}}
  if(loaded){
   val visible=staff.filter{s->val joined=LocalDate.parse(s.getString("joined_on"));val left=runCatching{LocalDate.parse(s.optString("left_on"))}.getOrNull();joined<=days.last()&&(left==null||left>=monday)}
   Text("P = Present · A = Absent · ½ = Half-day · L = Leave\n— = Not marked (not absent) · × = Unavailable",fontSize=11.sp,color=Color.DarkGray)
   if(visible.isEmpty())Text("No staff employed in this week. Add staff above or choose another week.",fontSize=13.sp)
   else Row(Modifier.fillMaxWidth()){
    // Frozen staff-name column; one shared horizontal scroller for all seven days.
    Column(Modifier.width(112.dp)){
     Box(Modifier.fillMaxWidth().height(58.dp).background(AttendanceGreen).padding(8.dp),contentAlignment=Alignment.CenterStart){Text("Staff",color=Color.White,fontWeight=FontWeight.Bold,fontSize=12.sp)}
     visible.forEachIndexed{i,s->Column(Modifier.fillMaxWidth().height(68.dp).background(if(i%2==0)Color.White else AttendanceTint).padding(8.dp),verticalArrangement=Arrangement.Center){Text(s.optString("name"),fontSize=12.sp,fontWeight=FontWeight.SemiBold,maxLines=2);Text(s.optString("position"),fontSize=10.sp,maxLines=1)}}
    }
    Column(Modifier.weight(1f).horizontalScroll(rememberScrollState())){
     Row{days.forEach{day->Box(Modifier.width(70.dp).height(58.dp).background(AttendanceGreen).padding(5.dp),contentAlignment=Alignment.Center){Text(day.format(DateTimeFormatter.ofPattern("EEE\nd MMM",Locale.ENGLISH)),fontSize=11.sp,color=Color.White)}}}
     visible.forEachIndexed{i,s->Row{
      days.forEach{day->
       val status=attendanceArray(s,"attendance").find{it.optString("day")==day.toString()}?.optString("status")
       val left=runCatching{LocalDate.parse(s.optString("left_on"))}.getOrNull()
       val available=day<=today&&day>=LocalDate.parse(s.getString("joined_on"))&&(left==null||day<=left)
       val label=when(status){"PRESENT"->"P";"ABSENT"->"A";"HALF_DAY"->"½";"LEAVE"->"L";else->if(available)"—" else "×"}
       val tint=when(status){"PRESENT"->AttendanceTint;"ABSENT"->Color(0xFFFFE9E5);"HALF_DAY"->Color(0xFFFFF2D8);"LEAVE"->Color(0xFFE8EDF9);else->if(i%2==0)Color.White else AttendanceTint}
       Box(Modifier.width(70.dp).height(68.dp).background(tint).padding(3.dp)){
        OutlinedButton(onClick={selectedStaff=s;selectedDay=day;saveError=null},enabled=available&&!busy,modifier=Modifier.fillMaxSize().semantics{contentDescription="${s.optString("name")}, $day, ${attendanceOptions[status]?:if(available)"Not marked" else "Unavailable"}"},contentPadding=PaddingValues(0.dp)){Text(label,fontSize=14.sp,fontWeight=FontWeight.Bold)}
       }
      }
     }}
    }
   }
   Text("Each selection saves immediately and updates the monthly estimates below. Final salary still needs owner approval; approved salaries are not rewritten.",fontSize=11.sp,color=Color.DarkGray)
   Text("Monthly attendance & salary",fontSize=16.sp,fontWeight=FontWeight.Bold,color=AttendanceGreen)
   staff.forEach{s->attendanceArray(s,"month_summaries").filter{it.optInt("elapsed_employment_days")>0}.forEach{summary->StaffAttendanceSummaryCard(s,summary)}}
  }
 }
 if(sheet)StaffSheet(staff,onClose={sheet=false},onSave={rows,done->repository.saveStaffSheet(rows){_,e->done(e);if(e==null){sheet=false;load()}}})
 if(salaries)StaffScreen(repository,onClose={salaries=false;load()})
 selectedStaff?.let{s->AlertDialog(onDismissRequest={if(!busy)selectedStaff=null},title={Text(s.optString("name"),fontSize=16.sp)},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
  Text(selectedDay.format(DateTimeFormatter.ofPattern("EEEE, d MMM yyyy",Locale.ENGLISH)),fontSize=12.sp)
  attendanceOptions.forEach{(status,label)->OutlinedButton(enabled=!busy,modifier=Modifier.fillMaxWidth(),onClick={
   busy=true;saveError=null
   repository.staffAttendance(JSONObject().put("staff_id",s.getString("id")).put("day",selectedDay.toString()).put("status",status)){_,e->busy=false;if(e==null){selectedStaff=null;load()}else saveError=e}
  }){Text(label)}}
  saveError?.let{Text(it,color=MaterialTheme.colorScheme.error,fontSize=12.sp)}
 }},confirmButton={},dismissButton={TextButton(enabled=!busy,onClick={selectedStaff=null}){Text("Cancel")}})}
}

@Composable private fun StaffAttendanceSummaryCard(staff:JSONObject,s:JSONObject){
 val total=s.optInt("days_in_month",31);val present=s.optInt("present")
 val money=java.text.NumberFormat.getCurrencyInstance(Locale("en","IN")).format(java.math.BigDecimal(s.optLong("estimated_salary_paise")).movePointLeft(2))
 Surface(color=Color.White,shape=androidx.compose.foundation.shape.RoundedCornerShape(16.dp)){
  Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){
   Text("${staff.optString("name")} · ${s.optString("month").take(7)}",fontWeight=FontWeight.Bold,fontSize=14.sp)
   Text("$present days present out of $total calendar days",fontSize=13.sp,color=AttendanceGreen,fontWeight=FontWeight.SemiBold)
   LinearProgressIndicator(progress={present.toFloat()/total.coerceAtLeast(1)},modifier=Modifier.fillMaxWidth().height(8.dp),color=AttendanceGreen,trackColor=AttendanceTint)
   listOf(listOf("Half-days" to s.optInt("half_day"),"Absent" to s.optInt("absent"),"Paid leave" to s.optInt("paid_leave")),listOf("Excess leave" to s.optInt("excess_leave"),"Unmarked" to s.optInt("unmarked"),"Days checked" to (s.optInt("elapsed_employment_days")-s.optInt("unmarked")))).forEach{group->
    Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){group.forEach{(label,n)->Surface(modifier=Modifier.weight(1f),color=AttendanceTint,shape=androidx.compose.foundation.shape.RoundedCornerShape(10.dp)){Column(Modifier.padding(8.dp)){Text(n.toString(),fontSize=18.sp,fontWeight=FontWeight.Bold,color=AttendanceGreen);Text(label,fontSize=10.sp)}}}}
   }
   Text(if(s.optBoolean("policy_set"))"Paid leave: ${staff.optInt("leave_days")} per ${if(staff.optString("leave_period")=="WEEKLY")"week (Mon–Sun)" else "month"}" else "Set a paid leave allowance in the staff sheet first.",fontSize=12.sp)
   Text("${if(s.optBoolean("month_ended"))"Calculated salary" else "Earned-so-far estimate"}: $money",fontSize=16.sp,fontWeight=FontWeight.Bold,color=AttendanceGreen)
   Text("${s.optString("payable_units")} paid-day equivalents × monthly salary ÷ $total. Future and unmarked days are not assumed present; excess leave is excluded pending review.",fontSize=11.sp,color=Color.DarkGray)
   Text(if(s.optBoolean("needs_review"))"Review needed: set policy / mark missing days / decide excess leave." else if(s.optBoolean("month_ended"))"Ready for owner review in Salaries & team." else "Final salary approval opens after month-end.",fontSize=11.sp,color=if(s.optBoolean("needs_review"))Color(0xFF9C5A22) else AttendanceGreen)
  }
 }
}
