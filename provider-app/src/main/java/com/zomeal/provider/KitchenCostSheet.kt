package com.zomeal.provider

import android.app.DatePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

private val SheetGreen = Color(0xFF087F43)
private val SheetTint = Color(0xFFEAF5EE)
private val SheetLine = Color(0xFFDCE8DF)
private val SheetRed = Color(0xFFAE3934)
private val SheetCategories = linkedMapOf(
    "Grains" to "Groceries / grains", "Vegetables" to "Vegetables",
    "Oil & spices" to "Oil & masalas", "Meat & eggs" to "Meat, eggs & fish",
    "Dairy" to "Milk & dairy", "Gas & fuel" to "Gas / fuel",
    "Packaging" to "Packaging", "Delivery" to "Delivery", "Cleaning" to "Cleaning",
    "Rent" to "Rent", "Utilities" to "Electricity / water", "Staff" to "Staff salaries",
    "Other" to "Other costs", "Equipment / owner / loan" to "Equipment / owner / loan"
)

private fun sheetMoney(paise: Long) = NumberFormat.getCurrencyInstance(Locale("en", "IN"))
    .format(BigDecimal(paise).movePointLeft(2))

private fun seedCostRows(): String = JSONArray().apply {
    listOf("Grains" to "Rice", "Grains" to "Atta", "Grains" to "Dal", "Vegetables" to "Vegetables", "Oil & spices" to "Oil", "Oil & spices" to "Masalas", "Meat & eggs" to "Meat / eggs / fish", "Gas & fuel" to "Gas / fuel", "Rent" to "Rent", "Utilities" to "Electricity / water", "Staff" to "Staff salaries").forEach { (category,label) ->
        put(newCostRow(category, category in listOf("Rent", "Utilities", "Staff")).put("item",label))
    }
}.toString()

private fun newCostRow(category: String, monthly: Boolean) = JSONObject()
    .put("id", UUID.randomUUID().toString()).put("category", category)
    .put("item", SheetCategories[category]).put("amount", "").put("quantity", "")
    .put("note", "").put("paid", !monthly).put("monthly", monthly)

/** Draft rows are saved across rotation. Blank amount cells are not submitted. */
@Composable
internal fun KitchenCostSheet(
    initialDate: String,
    startMonthly: Boolean = false,
    repository: SupabaseProviderRepository? = null,
    startWithVoice: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (JSONArray, (String?) -> Unit) -> Unit
) {
    var draft by rememberSaveable { mutableStateOf(seedCostRows()) }
    var date by rememberSaveable { mutableStateOf(initialDate) }
    var monthly by rememberSaveable { mutableStateOf(startMonthly) }
    var quantityColumn by rememberSaveable { mutableStateOf(false) }
    var notesColumn by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // Freeze a failed request for retry: a network error can occur after commit.
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    var discard by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var columns by remember { mutableStateOf(false) }
    var voice by remember { mutableStateOf(startWithVoice) }
    val context = LocalContext.current
    val array = remember(draft) { JSONArray(draft) }
    val rows = (0 until array.length()).map { array.getJSONObject(it) }
    val filled = rows.filter { it.optString("amount").isNotBlank() }
    val invalid = filled.any { kitchenPaise(it.optString("amount")) == null || kitchenPaise(it.optString("amount")) == 0L }
    val total = filled.sumOf { kitchenPaise(it.optString("amount")) ?: 0 }
    val locked = busy || pending != null
    fun update(id: String, field: String, value: Any) {
        val next = JSONArray(draft)
        (0 until next.length()).map { next.getJSONObject(it) }.first { it.getString("id") == id }.put(field, value)
        draft = next.toString()
    }
    fun close() { if (!busy) { if (filled.isNotEmpty() || pending != null) discard = true else onDismiss() } }

    if (voice && repository != null) KitchenVoiceDialog(repository, "PURCHASE", date, onDismiss = { voice = false }, onApply = { incoming ->
        if (filled.size + incoming.length() > 50) { error = "Save at most 50 filled rows at a time."; voice = false }
        else {
            val next = JSONArray(draft)
            for (i in 0 until incoming.length()) {
                val r = incoming.getJSONObject(i)
                next.put(newCostRow(r.getString("category"), monthly)
                    .put("item", r.getString("item")).put("quantity", r.getString("quantity"))
                    .put("unit", r.getString("unit")).put("amount", r.getString("amount"))
                    .put("note", "Voice draft reviewed by provider"))
            }
            draft = next.toString(); quantityColumn = true; voice = false
        }
    })

    // Only one native dialog at a time: the sheet must not cover the recorder.
    if (!(voice && repository != null)) Dialog(onDismissRequest = ::close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Scaffold(
            // Match BIKI's OEM fallback: the save bar must never occupy the
            // phone's three-button navigation touch area, even with zero insets.
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.ime)).padding(bottom = 56.dp),
            contentWindowInsets = WindowInsets(0,0,0,0),
            containerColor = Color(0xFFF7FAF7),
            topBar = {
                Surface(color = Color.White) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = ::close, enabled = !busy) { Icon(Icons.Outlined.Close, "Close sheet") }
                        Column(Modifier.weight(1f)) {
                            Text("Add expenses", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Text(if(monthly) "1 · Monthly setup" else "2 · Daily spending", fontSize = 11.sp, color = SheetGreen)
                        }
                    }
                }
            },
            bottomBar = {
                Surface(color = Color.White, shadowElevation = 4.dp) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${filled.size} rows · total", fontSize = 13.sp)
                            Text(if (invalid) "Check amounts" else sheetMoney(total), color = SheetRed, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        }
                        Button(
                            enabled = !busy && filled.isNotEmpty() && !invalid,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                if (filled.any { it.optString("item").isBlank() }) { error = "Add an item name for every filled row."; return@Button }
                                if (filled.any { it.optString("quantity").isNotBlank() && it.optString("quantity").toBigDecimalOrNull()?.signum() != 1 }) { error = "Quantity must be a positive number, or left blank."; return@Button }
                                val payload = pending?.let(::JSONArray) ?: JSONArray().apply {
                                    filled.forEach { r -> put(JSONObject()
                                        .put("id", r.getString("id")).put("revision", 0).put("date", date)
                                        .put("kind", "EXPENSE").put("category", r.getString("category"))
                                        .put("item", r.getString("item").trim()).put("amount_paise", kitchenPaise(r.getString("amount")))
                                        .put("cost_type", if (r.getString("category") == "Equipment / owner / loan") "EXCLUDED" else if (r.optBoolean("monthly")) "FIXED" else "VARIABLE")
                                        .put("quantity", r.optString("quantity")).put("unit", r.optString("unit")).put("note", r.optString("note"))
                                        .put("paid", r.optBoolean("paid")).put("recurring", r.optBoolean("monthly")&&r.getString("category")!="Equipment / owner / loan")) }
                                }
                                pending = payload.toString(); busy = true; error = null
                                onSave(payload) { problem -> busy = false; error = problem?.let { "$it\nRetry saves the same rows without duplicates. To correct a rejected row, close and reopen the sheet." } }
                            }
                        ) { Text(if (busy) "Saving…" else if (pending != null) "Retry save" else "Save ${filled.size} rows") }
                    }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                error?.let { Text(it, color = SheetRed, fontSize = 12.sp) }
                if (repository?.voiceConfigured == true) OutlinedButton(onClick = { voice = true }, enabled = !locked, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Mic, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Speak expenses · Your language", fontSize = 12.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(monthly, { monthly = true }, label = { Text("Monthly costs", fontSize = 12.sp) })
                    FilterChip(!monthly, { monthly = false }, label = { Text("Daily costs", fontSize = 12.sp) })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(enabled = !locked, onClick = {
                        val c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"))
                        val parts = date.split("-").map { it.toInt() }
                        DatePickerDialog(context, { _, y, m, d -> date = String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d) }, parts[0], parts[1] - 1, parts[2]).apply {
                            datePicker.maxDate = c.timeInMillis
                            datePicker.minDate = Calendar.getInstance().apply { set(2020, Calendar.JANUARY, 1, 0, 0, 0) }.timeInMillis
                            show()
                        }
                    }) { Icon(Icons.Outlined.CalendarMonth, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(date, fontSize = 12.sp) }
                    Spacer(Modifier.weight(1f))
                    Box {
                        TextButton(onClick = { columns = true }, enabled = !locked) { Text("+ Columns", fontSize = 12.sp) }
                        DropdownMenu(columns, { columns = false }) {
                            DropdownMenuItem(text = { Text(if (quantityColumn) "Hide quantity" else "Add quantity") }, onClick = { quantityColumn = !quantityColumn; columns = false })
                            DropdownMenuItem(text = { Text(if (notesColumn) "Hide notes" else "Add notes") }, onClick = { notesColumn = !notesColumn; columns = false })
                        }
                    }
                }
                if(monthly) {
                    val monthlyTotal=filled.filter{it.optBoolean("monthly") && it.optString("category")!="Equipment / owner / loan"}.sumOf{kitchenPaise(it.optString("amount"))?:0}
                    val parts=date.split("-").map{it.toInt()}
                    val days=Calendar.getInstance().apply{set(parts[0],parts[1]-1,1)}.getActualMaximum(Calendar.DAY_OF_MONTH)
                    Surface(color=SheetTint,shape=RoundedCornerShape(12.dp)){Column(Modifier.fillMaxWidth().padding(12.dp)){
                        Text("${sheetMoney(monthlyTotal)} / $days days = ${sheetMoney(java.math.BigDecimal(monthlyTotal).divide(java.math.BigDecimal(days),0,java.math.RoundingMode.HALF_UP).toLong())} per day",fontSize=13.sp,fontWeight=FontWeight.Bold,color=SheetGreen)
                        Text("Repeats next month as unpaid. Edit actual bills or stop repeating in Monthly setup.",fontSize=11.sp)
                    }}
                } else Text("Enter today's purchases. Monthly overhead is added separately—do not enter rent again.",fontSize=11.sp,color=SheetGreen)
                Text("Swipe sideways for more columns. Leave unused amounts blank.", fontSize = 11.sp, color = Color.Gray)
                Surface(shape = RoundedCornerShape(12.dp), color = Color.White) {
                    Column(Modifier.horizontalScroll(rememberScrollState())) {
                        Row(Modifier.background(SheetGreen), verticalAlignment = Alignment.CenterVertically) {
                            SheetHeader("Item / goods", 160.dp); SheetHeader("Amount ₹", 112.dp); SheetHeader("Category", 150.dp)
                            if (quantityColumn) { SheetHeader("Quantity", 100.dp); SheetHeader("Unit", 90.dp) }
                            if (notesColumn) SheetHeader("Notes / unit", 180.dp)
                            SheetHeader("Paid", 60.dp); SheetHeader("", 48.dp)
                        }
                        rows.filter { it.optBoolean("monthly") == monthly }.forEachIndexed { i, r -> key(r.getString("id")) {
                            val id = r.getString("id")
                            Row(Modifier.background(if (i % 2 == 0) Color.White else Color(0xFFF2F8F4)), verticalAlignment = Alignment.CenterVertically) {
                                SheetInput(r.optString("item"), "Item", 160.dp, !locked) { if (it.length <= 100) update(id, "item", it) }
                                SheetInput(r.optString("amount"), "0.00", 112.dp, !locked, true) { update(id, "amount", it) }
                                var categoryOpen by remember { mutableStateOf(false) }
                                Box(Modifier.width(150.dp)) {
                                    TextButton(onClick = { categoryOpen = true }, enabled = !locked) { Text(SheetCategories[r.getString("category")] ?: r.getString("category"), fontSize = 12.sp, maxLines = 2) }
                                    DropdownMenu(categoryOpen, { categoryOpen = false }, modifier = Modifier.heightIn(max = 320.dp)) {
                                        SheetCategories.forEach { (value, label) -> DropdownMenuItem(text = { Text(label, fontSize = 12.sp) }, onClick = { update(id, "category", value); categoryOpen = false }) }
                                    }
                                }
                                if (quantityColumn) {
                                    SheetInput(r.optString("quantity"), "Optional", 100.dp, !locked, true) { update(id, "quantity", it) }
                                    SheetInput(r.optString("unit"), "kg, litre…", 90.dp, !locked) { if (it.length <= 20) update(id, "unit", it) }
                                }
                                if (notesColumn) SheetInput(r.optString("note"), "kg, litre, notes…", 180.dp, !locked) { if (it.length <= 500) update(id, "note", it) }
                                Box(Modifier.width(60.dp), contentAlignment = Alignment.Center) { Checkbox(r.optBoolean("paid"), { update(id, "paid", it) }, enabled = !locked) }
                                IconButton(enabled = !locked, onClick = { draft = JSONArray().apply { rows.filterNot { it.getString("id") == id }.forEach { put(it) } }.toString() }) { Icon(Icons.Outlined.Close, "Remove row", Modifier.size(18.dp), tint = SheetRed) }
                            }
                            HorizontalDivider(color = SheetLine)
                        } }
                    }
                }
                Box {
                    OutlinedButton(onClick = { adding = true }, enabled = !locked && rows.size < 50) { Icon(Icons.Outlined.Add, null, Modifier.size(16.dp)); Text("Add row", fontSize = 12.sp) }
                    DropdownMenu(adding, { adding = false }, modifier = Modifier.heightIn(max = 320.dp)) {
                        SheetCategories.forEach { (category, label) -> DropdownMenuItem(text = { Text(label, fontSize = 12.sp) }, onClick = { draft = JSONArray(draft).put(newCostRow(category, monthly)).toString(); adding = false }) }
                    }
                }
                Text("Save includes filled rows in both tabs. Monthly costs repeat automatically; no money is deducted. Swipe sideways to mark a bill paid.", fontSize = 11.sp, color = Color.Gray)
                Spacer(Modifier.height(16.dp))
            }
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("Close expense sheet?") }, text = { Text(if (pending != null) "A save was attempted. Refresh Entries before adding the same costs again; the server may already have saved them." else "Unsaved rows will be discarded.") }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close sheet") } }, dismissButton = { TextButton(onClick = { discard = false }) { Text("Keep editing") } })
}

@Composable private fun SheetHeader(label: String, width: Dp) { Text(label, Modifier.width(width).padding(10.dp), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
@Composable private fun SheetInput(value: String, hint: String, width: Dp, enabled: Boolean, numeric: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, enabled = enabled, singleLine = true,
        modifier = Modifier.width(width).padding(3.dp), textStyle = TextStyle(fontSize = 13.sp),
        placeholder = { Text(hint, fontSize = 11.sp) }, shape = RoundedCornerShape(6.dp),
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text),
        colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = SheetLine, focusedBorderColor = SheetGreen))
}

/** Spreadsheet-like ledger; it shares the parent list's vertical scroll. */
@Composable internal fun KitchenEntriesTable(entries: List<JSONObject>, onEdit: (JSONObject) -> Unit, onRemove: (JSONObject) -> Unit) {
    var filter by rememberSaveable { mutableStateOf("All") }
    val hasSales = entries.any { it.optString("kind") == "SALE" }
    LaunchedEffect(hasSales) { if (!hasSales && filter == "Sales") filter = "All" }
    val visible = entries.filter { e -> when (filter) {
        "Daily" -> e.optString("kind") == "EXPENSE" && e.optString("cost_type") == "VARIABLE"
        "Monthly" -> e.optString("kind") == "EXPENSE" && e.optString("cost_type") == "FIXED"
        "Sales" -> e.optString("kind") == "SALE"
        else -> true
    } }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (listOf("All", "Daily", "Monthly") + if(hasSales) listOf("Sales") else emptyList()).forEach { label -> FilterChip(filter == label, { filter = label }, label = { Text(label, fontSize = 12.sp) }) }
        }
        Text("${visible.size} entries · swipe sideways · tap a row to edit", fontSize = 11.sp, color = SheetGreen)
        Surface(shape = RoundedCornerShape(12.dp), color = Color.White) {
            Column(Modifier.horizontalScroll(rememberScrollState())) {
                Row(Modifier.background(SheetGreen)) { SheetHeader("Day", 55.dp); SheetHeader("Item", 155.dp); SheetHeader("Cost ₹", 100.dp); if(hasSales) SheetHeader("Sale ₹", 100.dp); SheetHeader("Category", 145.dp); SheetHeader("Payment", 90.dp); SheetHeader("", 48.dp) }
                visible.forEachIndexed { index, e ->
                    Row(Modifier.background(if (index % 2 == 0) Color.White else SheetTint).clickable { onEdit(e) }, verticalAlignment = Alignment.CenterVertically) {
                        LedgerCell(e.optString("entry_date").takeLast(2), 55.dp)
                        LedgerCell(e.optString("item"), 155.dp)
                        LedgerCell(if (e.optString("kind") == "EXPENSE") sheetMoney(e.optLong("amount_paise")) else "—", 100.dp, SheetRed)
                        if(hasSales) LedgerCell(if (e.optString("kind") == "SALE") sheetMoney(e.optLong("amount_paise")) else "—", 100.dp, SheetGreen)
                        LedgerCell(SheetCategories[e.optString("category")] ?: e.optString("category"), 145.dp)
                        val paidAmount=e.optLong("paid_amount_paise",if(e.optBoolean("paid"))e.optLong("amount_paise") else 0)
                        LedgerCell(if(paidAmount>=e.optLong("amount_paise"))"Paid" else if(paidAmount>0)"Part-paid" else "Unpaid",90.dp,if(paidAmount>=e.optLong("amount_paise"))SheetGreen else SheetRed)
                        IconButton(onClick = { onRemove(e) },enabled=!e.optBoolean("staff_managed")) { Icon(Icons.Outlined.DeleteOutline, "Remove ${e.optString("item")}", Modifier.size(18.dp), tint = SheetRed) }
                    }
                    HorizontalDivider(color = SheetLine)
                }
                Row(Modifier.background(SheetTint)) {
                    LedgerCell("Total", 210.dp)
                    LedgerCell(sheetMoney(visible.filter { it.optString("kind") == "EXPENSE" }.sumOf { it.optLong("amount_paise") }), 100.dp, SheetRed)
                    if(hasSales) LedgerCell(sheetMoney(visible.filter { it.optString("kind") == "SALE" }.sumOf { it.optLong("amount_paise") }), 100.dp, SheetGreen)
                    Spacer(Modifier.width(283.dp))
                }
            }
        }
        if (visible.isEmpty()) Text("No entries in this view. Add a cost sheet to get started.", fontSize = 12.sp)
        Text("Daily = variable costs · Monthly = fixed costs. All also includes equipment/owner/loan entries; these are excluded from operating profit.", fontSize = 11.sp, color = Color.Gray)
    }
}

@Composable private fun LedgerCell(text: String, width: Dp, color: Color = Color(0xFF273A30)) {
    Text(text, Modifier.width(width).padding(10.dp), fontSize = 12.sp, color = color)
}
