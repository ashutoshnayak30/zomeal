package com.zomeal.provider

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Locale

private val EBrand = Color(0xFF087F43)
private val EInk = Color(0xFF14221B)
private val EMuted = Color(0xFF68736D)
private val EMist = Color(0xFFF0F7F2)

@Composable
fun ProviderEarningsScreen(
    repository: SupabaseProviderRepository,
    onDashboard: () -> Unit,
    onOrders: () -> Unit,
    onProfile: () -> Unit
) {
    var data by remember { mutableStateOf<JSONObject?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var showRequest by remember { mutableStateOf(false) }
    var showAdvance by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var sampleMode by remember { mutableStateOf(false) }
    fun load() {
        loading = true; error = null
        repository.loadEarningsSummary { result, problem -> data = result; error = problem; loading = false; sampleMode = false }
    }
    LaunchedEffect(Unit) { load() }

    val summary = data ?: JSONObject()
    val available = summary.optLong("available_paise")
    if (showRequest) {
        PayoutRequestDialog(
            availablePaise = available,
            loading = loading,
            onDismiss = { showRequest = false },
            onSubmit = { amount, method, note ->
                loading = true; message = null
                if (sampleMode) {
                    loading = false; showRequest = false; message = "Sample preview cannot create a real payout. Refresh and use real delivered earnings, or create an audited test cycle from Zomeal Admin."
                } else repository.requestPayout(amount, method, note) { result ->
                    loading = false; showRequest = false; message = result.message
                    if (result.success) load()
                }
            }
        )
    }
    if (showAdvance) AdvanceRequestDialog(loading, { showAdvance = false }) { amount, purpose ->
        loading = true; message = null
        if (sampleMode) { loading = false; showAdvance = false; message = "Sample advance request created on this device only." }
        else repository.requestAdvance(amount, purpose) { result -> loading = false; showAdvance = false; message = result.message; if (result.success) load() }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
        containerColor = Color(0xFFF7FAF7),
        topBar = {
            Surface(color = Color.White, shadowElevation = 2.dp) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDashboard) { Icon(Icons.Outlined.ArrowBack, "Dashboard", tint = EBrand) }
                    Column(Modifier.weight(1f)) {
                        Text("Earnings & payouts", color = EInk, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("Delivered meals · 48-hour settlement", color = EMuted, fontSize = 11.sp)
                    }
                    IconButton(onClick = { load() }) { Icon(Icons.Outlined.Refresh, "Refresh", tint = EBrand) }
                }
            }
        },
        bottomBar = { EarningsBottomBar(onDashboard, onOrders, onProfile) }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(15.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            if (loading && data == null) item { Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = EBrand) } }
            error?.let { item { EarningsNotice(it, true) } }
            message?.let { item { EarningsNotice(it, false) } }
            if (!loading && data != null) {
                if (sampleMode) item { EarningsNotice("SAMPLE PREVIEW — figures on this screen are not stored in Supabase and cannot create an admin payout request.", true) }
                item {
                    Surface(color = EBrand, shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = .16f)), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.AccountBalanceWallet, null, tint = Color.White) }
                                Spacer(Modifier.width(10.dp)); Text("Available to withdraw", color = Color.White.copy(alpha = .85f), fontSize = 12.sp)
                            }
                            Text(money(available), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
                            Text("Delivered earnings become available after ${summary.optInt("payout_hold_hours",48)} hours", color = Color.White.copy(alpha = .78f), fontSize = 10.sp)
                            Button(
                                onClick = { if(sampleMode) message="Sample preview cannot request a payout. Refresh to load real earnings." else showRequest = true }, enabled = available > 0 && !loading,
                                modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(13.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = EBrand)
                            ) { Icon(Icons.Outlined.Payments, null); Spacer(Modifier.width(7.dp)); Text("Request payout", fontWeight = FontWeight.Bold) }
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        MiniMoneyCard("Settlement pending", summary.optLong("pending_48h_paise"), Icons.Outlined.Schedule, Modifier.weight(1f))
                        MiniMoneyCard("Payout reserved", summary.optLong("reserved_paise"), Icons.Outlined.HourglassTop, Modifier.weight(1f))
                    }
                }
                item {
                    EarningsSection("Advance funds", "Advances have 0% commission. The full approved amount is paid and recovered from future net earnings") {
                        MoneyRow("Outstanding advance", -summary.optLong("advance_outstanding_paise"), true)
                        OutlinedButton(onClick = { showAdvance = true }, enabled = !loading, modifier = Modifier.fillMaxWidth().height(44.dp)) { Icon(Icons.Outlined.RequestQuote, null); Spacer(Modifier.width(7.dp)); Text("Request advance money") }
                        val advanceRequests = jsonList(summary.optJSONArray("advance_requests"))
                        if (advanceRequests.isEmpty()) Text("No advance requests yet.", color = EMuted, fontSize = 10.sp)
                        advanceRequests.take(3).forEach { AdvanceRequestCard(it) }
                    }
                }
                if (!sampleMode && summary.optLong("gross_paise") == 0L) item {
                    OutlinedButton(onClick = { data = sampleEarnings(); sampleMode = true; message = "Sample preview only—no money or payout request is real." }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        Icon(Icons.Outlined.Science, null); Spacer(Modifier.width(7.dp)); Text("Load sample earnings")
                    }
                }
                item {
                    EarningsSection("Earnings summary", "Your negotiated commission is calculated once on each delivered meal") {
                        MoneyRow("Gross delivered meal value", summary.optLong("gross_paise"))
                        MoneyRow("Zomeal commission total", -summary.optLong("commission_paise"))
                        Text("Current agreed rate: ${formatRate(summary.optDouble("commission_rate_percent",14.0))}% · historical earnings retain their original agreed rates", color = EMuted, fontSize = 9.sp)
                        HorizontalDivider(color = Color(0xFFE1EAE3))
                        MoneyRow("Provider net earnings", summary.optLong("provider_net_paise"), true)
                        val slots = summary.optJSONArray("by_slot") ?: JSONArray()
                        for (index in 0 until slots.length()) slots.optJSONObject(index)?.let { slot ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (slot.optString("slot") == "LUNCH") Icons.Outlined.WbSunny else Icons.Outlined.DarkMode, null, tint = EBrand, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(7.dp)); Text(slot.optString("slot").lowercase().replaceFirstChar(Char::uppercase), color = EMuted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                                Text(money(slot.optLong("net_paise")), color = EInk, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                val requests = jsonList(summary.optJSONArray("payout_requests"))
                item { Text("Payout requests", color = EInk, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                if (requests.isEmpty()) item { EmptyEarnings("No payout requests yet. Available earnings remain safe in your Zomeal balance.") }
                items(requests, key = { it.optString("id") }) { PayoutRequestCard(it) }

                val entries = jsonList(summary.optJSONArray("recent_entries"))
                item { Text("Recent transactions", color = EInk, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                if (entries.isEmpty()) item { EmptyEarnings("Delivered meal earnings will appear here automatically.") }
                items(entries, key = { it.optString("id") }) { LedgerEntryCard(it) }
            }
            item { Spacer(Modifier.height(6.dp)) }
        }
    }
}

@Composable private fun AdvanceRequestDialog(loading: Boolean,onDismiss:()->Unit,onSubmit:(Long,String)->Unit) {
    var amount by remember { mutableStateOf("") }; var purpose by remember { mutableStateOf("") }
    val paise=((amount.toDoubleOrNull()?:0.0)*100).toLong()
    AlertDialog(onDismissRequest={if(!loading)onDismiss()},icon={Icon(Icons.Outlined.RequestQuote,null,tint=EBrand)},title={Text("Request advance funds")},text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)){OutlinedTextField(amount,{amount=it.filter{c->c.isDigit()||c=='.'}.take(10)},label={Text("Amount in ₹")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal));OutlinedTextField(purpose,{purpose=it.take(240)},label={Text("Purpose and repayment context")},minLines=3);Text("Zomeal will review this request. If disbursed, it will be shown in your ledger and recovered automatically from future delivered-meal earnings.",color=EMuted,fontSize=10.sp,lineHeight=14.sp)}},confirmButton={Button({onSubmit(paise,purpose.trim())},enabled=!loading&&paise>0&&purpose.isNotBlank()){Text("Submit for review")}},dismissButton={TextButton(onDismiss,enabled=!loading){Text("Cancel")}})
}

@Composable private fun AdvanceRequestCard(item:JSONObject){val amount=item.optLong("approved_amount_paise",item.optLong("amount_paise"));val requested=item.optLong("requested_amount_paise",item.optLong("amount_paise"));val recovered=item.optLong("recovered_paise");Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(EMist).padding(11.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){Row{Text(money(amount),color=EInk,fontSize=12.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f));StatusPill(item.optString("status"))};Text("Full approved amount · 0% commission",color=EBrand,fontSize=9.sp,fontWeight=FontWeight.Bold);if(requested!=amount)Text("Originally requested ${money(requested)}",color=EMuted,fontSize=9.sp);Text(item.optString("purpose"),color=EMuted,fontSize=10.sp,maxLines=2);if(item.optString("status") in listOf("DISBURSED","RECOVERED"))Text("Recovered ${money(recovered)} · Remaining ${money((amount-recovered).coerceAtLeast(0))}",color=EBrand,fontSize=9.sp,fontWeight=FontWeight.SemiBold)}}

@Composable
private fun PayoutRequestDialog(availablePaise: Long, loading: Boolean, onDismiss: () -> Unit, onSubmit: (Long, String, String) -> Unit) {
    var amount by remember { mutableStateOf("") }
    var method by remember { mutableStateOf("UPI") }
    var note by remember { mutableStateOf("") }
    val amountPaise = ((amount.toDoubleOrNull() ?: 0.0) * 100).toLong()
    AlertDialog(
        onDismissRequest = { if (!loading) onDismiss() },
        icon = { Icon(Icons.Outlined.Payments, null, tint = EBrand) },
        title = { Text("Request payout") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Available: ${money(availablePaise)}", color = EBrand, fontWeight = FontWeight.Bold)
                OutlinedTextField(amount, { amount = it.filter { char -> char.isDigit() || char == '.' }.take(10) }, label = { Text("Amount in ₹") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                Text("Preferred method", color = EInk, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                listOf("UPI" to "UPI", "BANK_TRANSFER" to "Bank", "CHEQUE" to "Cheque", "CASH" to "Cash").chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) { row.forEach { option -> FilterChip(method == option.first, { method = option.first }, { Text(option.second) }, modifier = Modifier.weight(1f)) } }
                }
                OutlinedTextField(note, { note = it.take(180) }, label = { Text("Note or preferred contact time (optional)") }, minLines = 2)
                Text("Electronic payouts use the verified UPI or bank destination saved in your Profile. Cash and cheque requests are reviewed manually.", color = EMuted, fontSize = 10.sp, lineHeight = 14.sp)
            }
        },
        confirmButton = { Button(onClick = { onSubmit(amountPaise, method, note) }, enabled = !loading && amountPaise in 1..availablePaise) { Text("Submit request") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !loading) { Text("Cancel") } }
    )
}

@Composable private fun MiniMoneyCard(label: String, value: Long, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(15.dp)).background(Color.White).padding(13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, null, tint = EBrand, modifier = Modifier.size(21.dp)); Text(money(value), color = EInk, fontSize = 16.sp, fontWeight = FontWeight.Bold); Text(label, color = EMuted, fontSize = 10.sp)
    }
}

@Composable private fun EarningsSection(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(17.dp)).background(Color.White).padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, color = EInk, fontSize = 15.sp, fontWeight = FontWeight.Bold); Text(subtitle, color = EMuted, fontSize = 10.sp); content()
    }
}

@Composable private fun MoneyRow(label: String, value: Long, strong: Boolean = false) { Row { Text(label, color = if (strong) EInk else EMuted, fontSize = if (strong) 13.sp else 11.sp, fontWeight = if (strong) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f)); Text((if (value < 0) "− " else "") + money(kotlin.math.abs(value)), color = if (strong) EBrand else EInk, fontSize = if (strong) 15.sp else 11.sp, fontWeight = FontWeight.Bold) } }

@Composable private fun PayoutRequestCard(item: JSONObject) {
    Surface(color = Color.White, shape = RoundedCornerShape(15.dp)) { Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(EMist), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.ReceiptLong, null, tint = EBrand) }
        Spacer(Modifier.width(9.dp)); Column(Modifier.weight(1f)) { Text(money(item.optLong("amount_paise")), color = EInk, fontSize = 14.sp, fontWeight = FontWeight.Bold); Text("${item.optString("preferred_method").replace('_', ' ')} · ${shortDate(item.optString("requested_at"))}", color = EMuted, fontSize = 10.sp) }
        StatusPill(item.optString("status"))
    } }
}

@Composable private fun LedgerEntryCard(item: JSONObject) {
    val payout = item.optString("entry_type") == "PAYOUT"
    Surface(color = Color.White, shape = RoundedCornerShape(15.dp)) { Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (payout) Icons.Outlined.NorthEast else Icons.Outlined.SouthWest, null, tint = if (payout) Color(0xFFC6473D) else EBrand)
        Spacer(Modifier.width(9.dp)); Column(Modifier.weight(1f)) { Text(if (payout) "Payout" else "${item.optString("meal_slot").lowercase().replaceFirstChar(Char::uppercase)} meal earning", color = EInk, fontSize = 12.sp, fontWeight = FontWeight.Bold); Text(item.optString("service_date").ifBlank { shortDate(item.optString("created_at")) }, color = EMuted, fontSize = 10.sp) }
        Text((if (payout) "− " else "+ ") + money(kotlin.math.abs(item.optLong("provider_net_paise"))), color = if (payout) Color(0xFFC6473D) else EBrand, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    } }
}

@Composable private fun StatusPill(status: String) { Surface(color = when(status) { "PAID" -> Color(0xFFE5F5E9); "REJECTED", "CANCELLED" -> Color(0xFFFFECEA); else -> Color(0xFFFFF3D6) }, shape = CircleShape) { Text(status.lowercase().replaceFirstChar(Char::uppercase), color = EInk, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) } }
@Composable private fun EarningsNotice(text: String, error: Boolean) { Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(if (error) Color(0xFFFFECEA) else Color(0xFFE8F5EB)).padding(12.dp)) { Icon(if (error) Icons.Outlined.ErrorOutline else Icons.Outlined.Info, null, tint = if (error) Color(0xFFB23A32) else EBrand); Spacer(Modifier.width(7.dp)); Text(text, color = if (error) Color(0xFF8D302A) else EInk, fontSize = 10.sp, lineHeight = 14.sp) } }
@Composable private fun EmptyEarnings(text: String) { Text(text, color = EMuted, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White).padding(14.dp)) }

@Composable private fun EarningsBottomBar(onDashboard: () -> Unit, onOrders: () -> Unit, onProfile: () -> Unit) { NavigationBar(containerColor = Color.White, modifier = Modifier.height(64.dp)) {
    NavigationBarItem(false, onDashboard, { Icon(Icons.Outlined.Dashboard, null) }, label = { Text("Dashboard", fontSize = 9.sp) })
    NavigationBarItem(false, onOrders, { Icon(Icons.Outlined.ReceiptLong, null) }, label = { Text("Orders", fontSize = 9.sp) })
    NavigationBarItem(true, {}, { Icon(Icons.Outlined.AccountBalanceWallet, null) }, label = { Text("Earnings", fontSize = 9.sp) }, colors = NavigationBarItemDefaults.colors(selectedIconColor = EBrand, selectedTextColor = EBrand, indicatorColor = EMist))
    NavigationBarItem(false, onProfile, { Icon(Icons.Outlined.AccountCircle, null) }, label = { Text("Profile", fontSize = 9.sp) })
} }

private fun money(paise: Long): String = NumberFormat.getCurrencyInstance(Locale("en", "IN")).format(paise / 100.0)
private fun shortDate(value: String): String = value.take(10).ifBlank { "Today" }
private fun formatRate(value: Double): String = if (value % 1.0 == 0.0) value.toInt().toString() else String.format(Locale.US,"%.2f",value).trimEnd('0').trimEnd('.')
private fun jsonList(array: JSONArray?): List<JSONObject> = if (array == null) emptyList() else (0 until array.length()).mapNotNull(array::optJSONObject)
private fun sampleEarnings(): JSONObject = JSONObject()
    .put("gross_paise", 1_410_000L).put("commission_paise", 197_400L).put("provider_net_paise", 1_212_600L)
    .put("available_paise", 745_000L).put("pending_48h_paise", 367_600L).put("reserved_paise", 100_000L).put("payout_hold_hours",48).put("advance_outstanding_paise",150_000L)
    .put("advance_requests",JSONArray().put(JSONObject().put("id","sample-advance").put("amount_paise",200_000L).put("recovered_paise",50_000L).put("purpose","Purchase additional steel tiffin boxes").put("status","DISBURSED")))
    .put("by_slot", JSONArray().put(JSONObject().put("slot", "LUNCH").put("net_paise", 636_400L)).put(JSONObject().put("slot", "DINNER").put("net_paise", 576_200L)))
    .put("payout_requests", JSONArray().put(JSONObject().put("id", "sample-payout-1").put("amount_paise", 100_000L).put("preferred_method", "UPI").put("status", "PROCESSING").put("requested_at", "2026-08-15T10:30:00Z")))
    .put("recent_entries", JSONArray()
        .put(JSONObject().put("id", "sample-entry-1").put("entry_type", "MEAL_EARNING").put("meal_slot", "LUNCH").put("service_date", "2026-08-15").put("provider_net_paise", 5_160L))
        .put(JSONObject().put("id", "sample-entry-2").put("entry_type", "MEAL_EARNING").put("meal_slot", "DINNER").put("service_date", "2026-08-15").put("provider_net_paise", 4_300L)))
