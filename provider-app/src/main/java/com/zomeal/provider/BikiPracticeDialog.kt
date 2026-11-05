package com.zomeal.provider

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.util.Calendar
import java.util.TimeZone
import java.text.SimpleDateFormat

/** Entirely in-memory practice: no repository, voice requests or ledger writes. */
@Composable internal fun BikiPracticeDialog(onClose: () -> Unit) {
    var customers by remember { mutableStateOf(100) }
    var report by remember { mutableStateOf(false) }
    var actual by remember { mutableStateOf("") }
    var commission by remember { mutableStateOf("") }
    var overhead by remember { mutableStateOf("") }
    val today = remember { Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata")) }
    val monthDays = today.getActualMaximum(Calendar.DAY_OF_MONTH)
    fun dateAgo(days: Int): String = SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ENGLISH).apply {
        timeZone = TimeZone.getTimeZone("Asia/Kolkata")
    }.format((today.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, -days) }.time)
    val meals = customers * 85 / 100
    val revenue = meals * 120.0
    val opening = customers * 2.0
    val dailyRice = meals * 2 * .08
    val purchases = dailyRice * 12
    val expected = opening + purchases - dailyRice * 14
    val confirmed = actual.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 && it <= 100000 }
    val rate = commission.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..100.0 }
    val monthly = overhead.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 && it <= 10000000 }
    val green = Color(0xFF087F43)
    fun money(v: Double) = "₹%,.2f".format(java.util.Locale.ENGLISH, v)
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.ime)), color = Color(0xFFF5FAF6)) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).padding(bottom = 56.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("BIKI · Practice kitchen", fontSize = 22.sp, color = green)
                Text("15-day simulation · Nothing here changes your accounts. Resets when closed.", fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(100,200,500).forEach { n -> FilterChip(customers == n, { customers=n; report=false; actual="" }, label={Text("$n")}) }
                }
                Text("Each lunch / dinner: $meals delivered · ${customers/10} paused · ${customers/20} cancelled. Lunch ₹65; dinner ₹55.", fontSize=13.sp)
                Text("Delivery history", color=green)
                Column(Modifier.horizontalScroll(rememberScrollState())) {
                    Row(Modifier.background(green).padding(8.dp)) { listOf("Date","Lunch","Dinner","Stock check").forEach { Text(it,Modifier.width(110.dp),color=Color.White) } }
                    (0..14).forEach { day ->
                        Row(Modifier.background(if(day%2==0) Color.White else Color(0xFFE8F4EC)).padding(8.dp)) {
                            listOf(dateAgo(14-day),"$meals","$meals",if(day==0 || (day==14 && report)) "Recorded" else "Missing").forEach { Text(it,Modifier.width(110.dp),fontSize=12.sp) }
                        }
                    }
                }
                Text("Rice · reconcile missed stock checks", color=green)
                Text("First check: ${"%.2f".format(opening)} kg after dinner, 14 days ago. Purchases since then: ${"%.2f".format(purchases)} kg. Assumed use: 80 g per delivered meal.",fontSize=13.sp)
                Text("Estimated remaining: ${"%.2f".format(expected)} kg · ${if(report) 0 else 14} days without a check",color=green)
                Text("This is an estimate, not measured consumption. Waste, staff meals and missing purchases can explain a difference. Gas is recorded as a cost, not a daily stock count.",fontSize=12.sp)
                OutlinedTextField(actual,{actual=it;report=false},label={Text("Count remaining rice now · kg")},singleLine=true,modifier=Modifier.fillMaxWidth())
                Button({report=true},enabled=confirmed!=null,modifier=Modifier.fillMaxWidth()) { Text("Confirm practice stock check") }
                if(report && confirmed!=null) Text("Confirmed: $confirmed kg. Difference from estimate: ${"%+.2f".format(confirmed-expected)} kg. Review purchases and waste before adjusting anything.",color=green)
                Text("Practice finances · assumptions",color=green)
                Text("Daily sales ${money(revenue)}; cooking costs ${money(revenue*.68)} (68%, includes gas). Enter your commission and monthly overhead to simulate profit. These are not actual earnings.",fontSize=13.sp)
                OutlinedTextField(commission,{commission=it},label={Text("Commission % · 0–100")},singleLine=true,modifier=Modifier.fillMaxWidth())
                OutlinedTextField(overhead,{overhead=it},label={Text("Monthly rent + salaries + other overhead ₹")},singleLine=true,modifier=Modifier.fillMaxWidth())
                if(rate!=null && monthly!=null) {
                    val daily = revenue*(.32-rate/100)-monthly/monthDays
                    Text("15-day simulated sales: ${money(revenue*15)}\n15-day profit / loss: ${money(daily*15)}\nFull-month projected sales: ${money(revenue*monthDays)}\nFull-month projected profit / loss: ${money(daily*monthDays)}",color=green)
                    Text("Projection assumes the same deliveries and costs every day. The rolling 15-day sample is not month-to-date accounting.",fontSize=12.sp)
                }
                OutlinedButton(onClose,modifier=Modifier.fillMaxWidth()) { Text("Close practice · return to BIKI") }
            }
        }
    }
}
