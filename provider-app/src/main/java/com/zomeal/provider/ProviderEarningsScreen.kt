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
    onProfile: () -> Unit,
    onCosts: () -> Unit
) {
    var data by remember { mutableStateOf<JSONObject?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var showRequest by remember { mutableStateOf(false) }
    var showAdvance by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var refreshVersion by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    fun load() {
        refreshVersion++
        loading = true; error = null
        repository.loadEarningsSummary { result, problem -> data = result; error = problem; loading = false }
    }
    LaunchedEffect(Unit) { load() }

    val summary = data ?: JSONObject()
    if (showHistory) EarningsHistory(repository) { showHistory = false }
    val available = summary.optLong("available_paise")
    val balanceKnown = summary.has("available_paise") && !summary.isNull("available_paise")
    if (showRequest) {
        PayoutRequestDialog(
            availablePaise = available,
            loading = loading,
            onDismiss = { showRequest = false },
            onSubmit = { amount, method, note ->
                loading = true; message = null
                repository.requestPayout(amount, method, note) { result ->
                    loading = false; showRequest = false; message = result.message
                    if (result.success) load()
                }
            }
        )
    }
    if (showAdvance) AdvanceRequestDialog(loading, { showAdvance = false }) { amount, purpose ->
        loading = true; message = null
        repository.requestAdvance(amount, purpose) { result -> loading = false; showAdvance = false; message = result.message; if (result.success) load() }
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
                        Text("Delivered meals · balance and payout history", color = EMuted, fontSize = ProviderType.caption)
                    }
                    IconButton(onClick = { load() }) { Icon(Icons.Outlined.Refresh, "Refresh", tint = EBrand) }
                }
            }
        },
        bottomBar = { ProviderNavigation("Earnings", onDashboard, onOrders, {}, onProfile, onCosts) }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(15.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            if (loading && data == null) item { Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = EBrand) } }
            error?.let { item { EarningsNotice(it, true) } }
            message?.let { item { EarningsNotice(it, false) } }
            item { EarningsInfographics(repository, refreshVersion, onCosts, summary.optDouble("commission_rate_percent", Double.NaN)) }
            item { OutlinedButton(onClick = { showHistory = true }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.ReceiptLong, null); Spacer(Modifier.width(8.dp)); Text("Transaction history · view by month") } }
            if (!loading && data != null) {
                item {
                    Surface(color = EBrand, shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = .16f)), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.AccountBalanceWallet, null, tint = Color.White) }
                            Spacer(Modifier.width(10.dp)); Text("Available for a new payout request", color = Color.White.copy(alpha = .9f), fontSize = 12.sp)
                            }
                            Text(if (balanceKnown) money(available) else "Unavailable", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
                            Text("Net delivered-meal earnings, after commission and advance recovery. New earnings unlock after ${if (summary.has("payout_hold_hours") && !summary.isNull("payout_hold_hours")) summary.optInt("payout_hold_hours").toString() + " hours" else "your configured hold period"}.", color = Color.White.copy(alpha = .82f), fontSize = ProviderType.caption, lineHeight = 16.sp)
                            Button(
                                onClick = { showRequest = true }, enabled = balanceKnown && available > 0 && !loading,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(13.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = EBrand)
                            ) { Icon(Icons.Outlined.Payments, null); Spacer(Modifier.width(7.dp)); Text("Request payout", fontWeight = FontWeight.Bold) }
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        MiniMoneyCard("Net earnings waiting to unlock", summary.optLong("pending_48h_paise"), Icons.Outlined.Schedule, Modifier.weight(1f))
                        MiniMoneyCard("Held in open payout requests", summary.optLong("reserved_paise"), Icons.Outlined.HourglassTop, Modifier.weight(1f))
                    }
                }
                item {
                    EarningsSection("Provider advance", "This is money Zomeal paid you before it was earned. It has 0% commission and is recovered only from future net meal earnings.") {
                        MoneyRow("Advance still to recover", summary.optLong("advance_outstanding_paise"), true)
                        OutlinedButton(onClick = { showAdvance = true }, enabled = !loading, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Icon(Icons.Outlined.RequestQuote, null); Spacer(Modifier.width(7.dp)); Text("Request advance money") }
                        val advanceRequests = jsonList(summary.optJSONArray("advance_requests"))
                        if (advanceRequests.isEmpty()) Text("No advance requests yet.", color = EMuted, fontSize = ProviderType.caption)
                        advanceRequests.take(3).forEach { AdvanceRequestCard(it) }
                    }
                }
                item {
                    EarningsSection("Lifetime delivered-meal earnings", "These totals are not your withdrawable balance. Your negotiated commission is calculated once on each delivered meal.") {
                        MoneyRow("Gross value of delivered meals", summary.optLong("gross_paise"))
                        MoneyRow("Less: Zomeal commission", -summary.optLong("commission_paise"))
                        Text("Current agreed rate: ${summary.optDouble("commission_rate_percent",Double.NaN).let { if (it.isFinite()) formatRate(it) + "%" else "unavailable — contact support" }} · historical earnings retain their original agreed rates", color = EMuted, fontSize = ProviderType.caption)
                        HorizontalDivider(color = Color(0xFFE1EAE3))
                        MoneyRow("Lifetime net earnings", summary.optLong("provider_net_paise"), true)
                        val slots = summary.optJSONArray("by_slot") ?: JSONArray()
                        for (index in 0 until slots.length()) slots.optJSONObject(index)?.let { slot ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (slot.optString("slot") == "LUNCH") Icons.Outlined.WbSunny else Icons.Outlined.DarkMode, null, tint = EBrand, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(7.dp)); Text(slot.optString("slot").lowercase().replaceFirstChar(Char::uppercase), color = EMuted, fontSize = ProviderType.caption, modifier = Modifier.weight(1f))
                                Text(money(slot.optLong("net_paise")), color = EInk, fontSize = ProviderType.caption, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                val requests = jsonList(summary.optJSONArray("payout_requests"))
                item { Text("Payout requests", color = EInk, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                if (requests.isEmpty()) item { EmptyEarnings("No payout requests yet. Available earnings remain safe in your Zomeal balance.") }
                items(requests, key = { it.optString("id") }) { PayoutRequestCard(it) }

            }
            item { Spacer(Modifier.height(6.dp)) }
        }
    }
}

@Composable private fun AdvanceRequestDialog(loading: Boolean,onDismiss:()->Unit,onSubmit:(Long,String)->Unit) {
    var amount by remember { mutableStateOf("") }; var purpose by remember { mutableStateOf("") }
    val paise=((amount.toDoubleOrNull()?:0.0)*100).toLong()
    AlertDialog(onDismissRequest={if(!loading)onDismiss()},icon={Icon(Icons.Outlined.RequestQuote,null,tint=EBrand)},title={Text("Request advance funds")},text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)){OutlinedTextField(amount,{amount=it.filter{c->c.isDigit()||c=='.'}.take(10)},label={Text("Amount in ₹")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal));OutlinedTextField(purpose,{purpose=it.take(240)},label={Text("Purpose and repayment context")},minLines=3);Text("Zomeal will review this request. If disbursed, it will be shown in your ledger and recovered automatically from future delivered-meal earnings.",color=EMuted,fontSize = ProviderType.caption,lineHeight=14.sp)}},confirmButton={Button({onSubmit(paise,purpose.trim())},enabled=!loading&&paise>0&&purpose.isNotBlank()){Text("Submit for review")}},dismissButton={TextButton(onDismiss,enabled=!loading){Text("Cancel")}})
}

@Composable private fun AdvanceRequestCard(item:JSONObject){val amount=item.optLong("approved_amount_paise",item.optLong("amount_paise"));val requested=item.optLong("requested_amount_paise",item.optLong("amount_paise"));val recovered=item.optLong("recovered_paise");Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(EMist).padding(11.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){Row{Text(money(amount),color=EInk,fontSize=12.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f));StatusPill(item.optString("status"))};Text(if(item.optString("status") in listOf("APPROVED","DISBURSED","RECOVERED")) "Approved advance · 0% commission" else "Requested advance · subject to approval",color=EBrand,fontSize = ProviderType.caption,fontWeight=FontWeight.Bold);if(requested!=amount)Text("Originally requested ${money(requested)}",color=EMuted,fontSize = ProviderType.caption);Text(item.optString("purpose"),color=EMuted,fontSize = ProviderType.caption,maxLines=2);if(item.optString("status") in listOf("DISBURSED","RECOVERED"))Text("Recovered ${money(recovered)} · Remaining ${money((amount-recovered).coerceAtLeast(0))}",color=EBrand,fontSize = ProviderType.caption,fontWeight=FontWeight.SemiBold)}}

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
                Text("Electronic payouts use the verified UPI or bank destination saved in your Profile. Cash and cheque requests are reviewed manually.", color = EMuted, fontSize = ProviderType.caption, lineHeight = 14.sp)
            }
        },
        confirmButton = { Button(onClick = { onSubmit(amountPaise, method, note) }, enabled = !loading && amountPaise in 1..availablePaise) { Text("Submit request") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !loading) { Text("Cancel") } }
    )
}

@Composable private fun MiniMoneyCard(label: String, value: Long, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(15.dp)).background(Color.White).padding(13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, null, tint = EBrand, modifier = Modifier.size(21.dp)); Text(money(value), color = EInk, fontSize = 16.sp, fontWeight = FontWeight.Bold); Text(label, color = EMuted, fontSize = ProviderType.caption)
    }
}

@Composable private fun EarningsSection(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(17.dp)).background(Color.White).padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, color = EInk, fontSize = 15.sp, fontWeight = FontWeight.Bold); Text(subtitle, color = EMuted, fontSize = ProviderType.caption); content()
    }
}

@Composable private fun MoneyRow(label: String, value: Long, strong: Boolean = false) { Row { Text(label, color = if (strong) EInk else EMuted, fontSize = if (strong) 13.sp else 11.sp, fontWeight = if (strong) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f)); Text((if (value < 0) "− " else "") + money(kotlin.math.abs(value)), color = if (strong) EBrand else EInk, fontSize = if (strong) 15.sp else 11.sp, fontWeight = FontWeight.Bold) } }

@Composable private fun PayoutRequestCard(item: JSONObject) {
    Surface(color = Color.White, shape = RoundedCornerShape(15.dp)) { Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(EMist), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.ReceiptLong, null, tint = EBrand) }
        Spacer(Modifier.width(9.dp)); Column(Modifier.weight(1f)) { Text(money(item.optLong("amount_paise")), color = EInk, fontSize = 14.sp, fontWeight = FontWeight.Bold); Text("${item.optString("preferred_method").replace('_', ' ')} · ${shortDate(item.optString("requested_at"))}", color = EMuted, fontSize = ProviderType.caption) }
        StatusPill(item.optString("status"))
    } }
}

@Composable internal fun LedgerEntryCard(item: JSONObject) {
    val type = item.optString("entry_type")
    val amount = item.optLong("provider_net_paise")
    val payout = amount < 0
    val label = when(type) { "PAYOUT" -> "Payout"; "REVERSAL" -> "Meal earning reversal"; "ADVANCE_RECOVERY" -> "Advance recovery"; "ADJUSTMENT" -> "Ledger adjustment"; "MEAL_EARNING" -> "${item.optString("meal_slot").lowercase().replaceFirstChar(Char::uppercase)} meal earning"; else -> type.replace('_', ' ').lowercase().replaceFirstChar(Char::uppercase).ifBlank { "Ledger entry" } }
    Surface(color = Color.White, shape = RoundedCornerShape(15.dp)) { Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (payout) Icons.Outlined.NorthEast else Icons.Outlined.SouthWest, null, tint = if (payout) Color(0xFFC6473D) else EBrand)
        Spacer(Modifier.width(9.dp)); Column(Modifier.weight(1f)) { Text(label, color = EInk, fontSize = 12.sp, fontWeight = FontWeight.Bold); Text(item.optString("service_date").ifBlank { shortDate(item.optString("created_at")) }, color = EMuted, fontSize = ProviderType.caption) }
        Text((if (payout) "− " else "+ ") + money(kotlin.math.abs(amount)), color = if (payout) Color(0xFFC6473D) else EBrand, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    } }
}

@Composable private fun StatusPill(status: String) { Surface(color = when(status) { "PAID" -> Color(0xFFE5F5E9); "REJECTED", "CANCELLED" -> Color(0xFFFFECEA); else -> Color(0xFFFFF3D6) }, shape = CircleShape) { Text(status.lowercase().replaceFirstChar(Char::uppercase), color = EInk, fontSize = ProviderType.caption, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) } }
@Composable private fun EarningsNotice(text: String, error: Boolean) { Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(if (error) Color(0xFFFFECEA) else Color(0xFFE8F5EB)).padding(12.dp)) { Icon(if (error) Icons.Outlined.ErrorOutline else Icons.Outlined.Info, null, tint = if (error) Color(0xFFB23A32) else EBrand); Spacer(Modifier.width(7.dp)); Text(text, color = if (error) Color(0xFF8D302A) else EInk, fontSize = ProviderType.caption, lineHeight = 14.sp) } }
@Composable private fun EmptyEarnings(text: String) { Text(text, color = EMuted, fontSize = ProviderType.caption, lineHeight = 16.sp, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White).padding(14.dp)) }


private fun money(paise: Long): String = NumberFormat.getCurrencyInstance(Locale("en", "IN")).format(paise / 100.0)
private fun shortDate(value: String): String = value.takeUnless { it == "null" }.orEmpty().take(10).ifBlank { "Date unavailable" }
private fun formatRate(value: Double): String = if (value % 1.0 == 0.0) value.toInt().toString() else String.format(Locale.US,"%.2f",value).trimEnd('0').trimEnd('.')
private fun jsonList(array: JSONArray?): List<JSONObject> = if (array == null) emptyList() else (0 until array.length()).mapNotNull(array::optJSONObject)
