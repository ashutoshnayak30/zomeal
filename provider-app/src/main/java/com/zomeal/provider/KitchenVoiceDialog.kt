package com.zomeal.provider

import android.Manifest
import android.content.pm.PackageManager
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

private val VoiceCategories = listOf("Grains", "Vegetables", "Meat & eggs", "Oil & spices", "Dairy", "Gas & fuel", "Packaging", "Delivery", "Staff", "Rent", "Utilities", "Cleaning", "Other")
private val VoiceUnits = listOf("kg", "g", "litre", "ml", "piece", "packet", "bag", "cylinder")
private val VoiceLanguages = linkedMapOf("or-IN" to "ଓଡ଼ିଆ · Odia", "hi-IN" to "हिन्दी · Hindi", "en-IN" to "English (India)", "as-IN" to "Assamese", "bn-IN" to "Bengali", "gu-IN" to "Gujarati", "kn-IN" to "Kannada", "ml-IN" to "Malayalam", "mr-IN" to "Marathi", "ne-NP" to "Nepali", "pa-Guru-IN" to "Punjabi", "sd-IN" to "Sindhi", "ta-IN" to "Tamil", "te-IN" to "Telugu", "ur-PK" to "Urdu")

// A calling dialog's view lifecycle is not the host activity's visibility.
private fun Context.voiceActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> if (baseContext !== this) baseContext.voiceActivity() else null
    else -> null
}

@Composable internal fun KitchenVoiceDialog(
    repository: SupabaseProviderRepository, mode: String, dateLabel: String,
    onDismiss: () -> Unit, onApply: (JSONArray) -> Unit, quantityOnly: Boolean = false,
    openingStockRows: String = "[]", directDraft: Boolean = false
) {
    val stock = mode != "PURCHASE"
    val reuseOpeningPrices=mode=="CLOSING_STOCK"&&!quantityOnly
    val openingPrices=remember(openingStockRows){
        val saved=runCatching{JSONArray(openingStockRows)}.getOrDefault(JSONArray())
        (0 until saved.length()).mapNotNull { i->runCatching{
            val r=saved.getJSONObject(i)
            OpeningStockPrice(r.getString("item"),r.getString("quantity").toBigDecimal(),r.getString("unit"),r.getString("amount").toBigDecimal())
        }.getOrNull() }
    }
    fun applyOpeningPrice(row:JSONObject) {
        if(!reuseOpeningPrices)return
        val value=row.optString("quantity").toBigDecimalOrNull()?.let{closingStockValue(row.optString("item"),it,row.optString("unit"),openingPrices)}
        if(value!=null){row.put("amount",value.toPlainString());row.put("opening_price_applied",true)}
        else if(row.optBoolean("opening_price_applied")){row.put("amount","");row.put("opening_price_applied",false)}
    }
    val context = LocalContext.current
    val activity = remember(context) { context.voiceActivity() }
    val privacyPrefs = remember(context) { context.getSharedPreferences("biki_privacy", Context.MODE_PRIVATE) }
    val account = context.getSharedPreferences("zomeal_provider_session", Context.MODE_PRIVATE).getString("user_id", "").orEmpty()
    val languageKey = "voice_language_$account"
    var language by remember(languageKey) { mutableStateOf(privacyPrefs.getString(languageKey, "").orEmpty().takeIf { it in VoiceLanguages }.orEmpty()) }
    var languageOpen by remember { mutableStateOf(false) }
    fun chooseLanguage(value: String) {
        language = value
        privacyPrefs.edit().putString(languageKey, value).apply()
    }
    val consentKey = "singapore_audio_india_text_v1_$account"
    var consent by remember(consentKey) { mutableStateOf(account.isNotBlank() && privacyPrefs.getBoolean(consentKey, false)) }
    var privacyOpen by remember { mutableStateOf(false) }
    var showTranscript by remember { mutableStateOf(false) }
    fun acceptConsent(value: Boolean) {
        consent = value
        if (account.isNotBlank() && repository.voiceConfigured) privacyPrefs.edit().putBoolean(consentKey, value).apply()
    }
    var recording by remember { mutableStateOf(false) }
    var seconds by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var audio by remember { mutableStateOf<ByteArray?>(null) }
    var recorder by remember { mutableStateOf<KitchenVoiceRecorder?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var transcript by rememberSaveable { mutableStateOf("") }
    var draft by rememberSaveable { mutableStateOf("[]") }
    var checked by rememberSaveable { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    var generation by remember { mutableIntStateOf(0) }
    val live = remember { java.util.concurrent.atomic.AtomicBoolean(true) }
    val rows = remember(draft) { JSONArray(draft) }
    val categories = if (stock) VoiceCategories.take(5) else VoiceCategories
    val valid = rows.length() in 1..30 && (0 until rows.length()).all { i ->
        val r = rows.getJSONObject(i); val q = r.optString("quantity").toBigDecimalOrNull(); val amount = kitchenPaise(r.optString("amount"))
        r.optString("item").trim().length in 1..100 && r.optString("category") in categories &&
            r.optString("unit") in VoiceUnits && q != null && q >= BigDecimal.ZERO && q <= BigDecimal(100000) && q.scale() <= 3 &&
            (quantityOnly || (amount != null && amount in 0..100000000 && (stock || (amount > 0 && q > BigDecimal.ZERO))))
    }
    val total = (0 until rows.length()).sumOf { kitchenPaise(rows.getJSONObject(it).optString("amount")) ?: 0L }
    fun update(index: Int, field: String, value: String) {
        val next = JSONArray(draft); val changed=next.getJSONObject(index);changed.put(field, value)
        if(field in listOf("item","quantity","unit"))applyOpeningPrice(changed)
        draft = next.toString(); checked = false
    }
    fun close() { if (recording || busy || audio != null || rows.length() > 0) discard = true else onDismiss() }
    fun start() {
        if (language !in VoiceLanguages) { languageOpen = true; return }
        if (activity?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) != true) {
            error = "Return to the app, then tap Start recording."; return
        }
        val capture = KitchenVoiceRecorder(); recorder = capture; recording = true; seconds = 0; error = null; audio = null
        capture.start({ seconds = it }) { bytes, problem ->
            if (live.get()) { recording = false; audio = bytes; error = problem }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (live.get()) { if (granted) start() else error = "Allow microphone access to record. Manual entry is still available." }
    }
    DisposableEffect(activity) {
        live.set(true)
        val owner = activity
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && (recording || busy || audio != null)) {
                recorder?.cancel(); recording = false; audio = null; generation++; busy = false
                error = "Recording/request stopped when the app left the foreground. Nothing was saved."
            }
        }
        owner?.lifecycle?.addObserver(observer)
        onDispose { live.set(false); recorder?.cancel(); audio = null; owner?.lifecycle?.removeObserver(observer) }
    }
    Dialog(onDismissRequest = ::close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // Apply to BIKI's own window, not the parent dialog. Restore on exit.
        val dialogView = LocalView.current
        DisposableEffect(dialogView, recording, busy) {
            val previous = dialogView.keepScreenOn
            if (recording || busy) dialogView.keepScreenOn = true
            onDispose { dialogView.keepScreenOn = previous }
        }
        Scaffold(
            // Some OEM dialog windows report zero navigation insets. Keep the
            // entire scaffold above a reserved touch-safe strip as well.
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.ime)).padding(bottom = 56.dp),
            containerColor = Color(0xFFF5F9F6),
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = { Surface(color = Color.White, contentColor = Color(0xFF087D45)) { Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("BIKI", fontSize = 26.sp, fontWeight = FontWeight.Bold); Text("${if (!stock) "Purchases" else if(mode=="RECIPE")"Recipe ingredients" else if (mode == "OPENING_STOCK") "Opening stock" else "Closing stock"} · $dateLabel", fontSize = 12.sp) }
                TextButton(onClick = ::close) { Text("Close") }
            } } },
            bottomBar = { if(rows.length()>0)Surface(shadowElevation = 4.dp, color = Color.White) { Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if(mode=="RECIPE") "${rows.length()} recipe ingredients" else if(quantityOnly) "${rows.length()} stock quantities" else if(reuseOpeningPrices&&!valid)"${rows.length()} items · incomplete subtotal" else "${rows.length()} items · review total", fontSize = 12.sp)
                    if(!quantityOnly) Text("₹${BigDecimal(total).movePointLeft(2)}", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF087D45))
                }
                Button(onClick = { onApply(JSONArray(draft)) }, enabled = valid && checked && total <= 100000000 && !busy && !recording, modifier = Modifier.fillMaxWidth()) {
                    Text(if(quantityOnly) "Use reviewed quantities" else if (stock) "Use this stock total" else "Add to expense sheet")
                }
                Text("Draft only. Save from the next screen to update your accounts.", fontSize = 11.sp)
            } } }
        ) { padding -> Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!repository.voiceConfigured) Text("BIKI recording preview: AI is not connected. You can test the microphone, but cannot submit this recording. Nothing will be uploaded or saved.", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            Text(if(mode=="RECIPE") "Say ingredients and raw quantities for one batch." else if(quantityOnly||reuseOpeningPrices) "Say each item and how much is left. No prices needed for matching opening items." else if(stock) "Say each item, quantity and its total purchase cost." else "Say what you bought, how much, and the total cost. Say ‘per kg’ for a unit price.",fontSize=15.sp,color=Color(0xFF63746A))
            if(reuseOpeningPrices&&openingPrices.isEmpty())Text("No item-level opening prices available. Older reports saved only a total. Record opening items once and save the stock check to remember their prices.",fontSize=12.sp,color=Color(0xFF865C00))
            Surface(shape = RoundedCornerShape(22.dp), color = Color(0xFFE4F3E9)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (recording) "Listening to you…" else if (busy) "Preparing your sheet…" else if (rows.length() > 0) "Check your items" else "Ready when you are", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF075B35))
                    Text(if (language.isBlank()) "Choose your voice language once before recording." else "Voice language: ${VoiceLanguages[language]}", fontSize = 12.sp)
                    Text(if (recording) "${seconds}s / 60s · Stop to prepare your sheet" else "Record → automatic processing → review sheet", fontSize = 12.sp, color = Color(0xFF087D45))
                    if (recording) LinearProgressIndicator(progress = { seconds / 60f }, modifier = Modifier.fillMaxWidth(), color = Color(0xFF087D45))
                }
            }
            OutlinedButton(onClick = { languageOpen = true }, enabled = !recording && !busy && audio == null) { Text(if (language.isBlank()) "Choose voice language" else "Voice settings · Change language", fontSize = 12.sp) }
            if (!consent) Surface(shape = RoundedCornerShape(16.dp), color = Color.White) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(consent, { acceptConsent(it) }, enabled = !recording && !busy)
                    Text(if(repository.voiceConfigured) "Allow Google to process audio in Singapore and transcripts in India. Remembered for this account on this phone. Do not include passwords or customer details." else "Allow a microphone test. Audio stays in memory on this phone.", fontSize = 12.sp)
                }
            } else TextButton(onClick = { privacyOpen = true }, enabled = !recording && !busy) { Text("Privacy & voice settings", fontSize = 12.sp) }
            error?.let { Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.errorContainer) { Text(it, modifier = Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 12.sp) } }
            if (rows.length() == 0) {
                Column(Modifier.fillMaxWidth().padding(vertical=22.dp),horizontalAlignment=Alignment.CenterHorizontally){
                 FilledIconButton(onClick={
                    if(recording)recorder?.stop() else if(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)start() else permission.launch(Manifest.permission.RECORD_AUDIO)
                 },enabled=consent&&!busy,modifier=Modifier.size(120.dp),shape=CircleShape,colors=IconButtonDefaults.filledIconButtonColors(containerColor=Color(0xFF087D45),contentColor=Color.White)){
                    Icon(if(recording)Icons.Outlined.Stop else Icons.Outlined.Mic,if(recording)"Stop and prepare sheet" else "Start recording",Modifier.size(44.dp))
                 }
                 Spacer(Modifier.height(18.dp));Text("%02d:%02d".format(seconds/60,seconds%60),fontSize=32.sp,color=Color(0xFF183D2C))
                 Text(if(busy)"Processing · nothing saved yet" else if(recording)"Tap stop when finished" else "Tap the microphone to begin",fontSize=13.sp)
                }
                Button(onClick = {
                    if (recording) recorder?.stop()
                    else if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) start()
                    else permission.launch(Manifest.permission.RECORD_AUDIO)
                }, enabled = consent && !busy, modifier = Modifier.fillMaxWidth()) { Text(if (recording) "Stop & prepare sheet" else if (audio != null) "Record again" else "Start recording") }
                TextButton(onClick={if(directDraft)onDismiss() else {draft=JSONArray().put(JSONObject().put("item","").put("category",categories.first()).put("quantity","").put("unit","kg").put("amount","")).toString()}},enabled=!busy&&!recording,modifier=Modifier.fillMaxWidth()){Text("Type instead")}
                if (audio != null && !recording && !repository.voiceConfigured) Text("Microphone test captured ${audio!!.size / 32000.0} seconds. AI submission needs the deployed backend.",fontSize=12.sp)
                // Run once for each completed recording, never on a recomposition
                // or for a microphone-only preview. Saving still requires review.
                LaunchedEffect(audio) {
                    val bytes = audio ?: return@LaunchedEffect
                    if (recording || busy || !consent || !repository.voiceConfigured ||
                        activity?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) != true) return@LaunchedEffect
                    val serial = ++generation
                    busy = true; error = null
                    repository.voiceDraft(bytes, language, mode) { result, problem ->
                        if (live.get() && serial == generation) {
                            busy = false; audio = null; error = problem
                            if (result != null) {
                                if(language=="or-IN"&&result.optString("transcript").any{it in '\u0980'..'\u09ff'}){
                                    error="Odia was selected, but recognition returned Bengali script. Nothing was saved. Record a shorter sentence or use Type instead."
                                    return@voiceDraft
                                }
                                transcript = result.optString("transcript")
                                val incoming = result.getJSONArray("items"); val next = JSONArray()
                                for (i in 0 until incoming.length()) {
                                    val r = incoming.getJSONObject(i)
                                    next.put(JSONObject().put("item", r.optString("item")).put("category", r.optString("category"))
                                        .put("quantity", if (r.isNull("quantity")) "" else r.optString("quantity")).put("unit", r.optString("unit"))
                                        .put("amount", if (r.isNull("amount_paise")) "" else BigDecimal(r.getLong("amount_paise")).movePointLeft(2).toPlainString())
                                        .put("issues", r.optJSONArray("issues") ?: JSONArray()))
                                }
                                for(i in 0 until next.length())applyOpeningPrice(next.getJSONObject(i))
                                draft = next.toString(); checked = false
                                if (next.length() == 0) error = "No kitchen items found. Please record again."
                                else if(directDraft)onApply(next)
                            }
                        }
                    }
                }
            }
            if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Understanding your recording… Nothing is saved yet.", fontSize = 12.sp) }
            if (transcript.isNotBlank()) { TextButton(onClick={showTranscript=!showTranscript}){Text("What BIKI heard ${if(showTranscript)"↑" else "↓"}")};if(showTranscript)Text(transcript,fontSize=13.sp) }
            if (rows.length() > 0) Text(if(quantityOnly) "Review quantities · prices are not required for this inventory observation." else if(reuseOpeningPrices)"Opening-stock rates are reused automatically, including kg/g and litre/ml conversion. Match a different spoken spelling to a saved item below; only genuinely new items need a full remaining-stock cost." else "Review sheet · swipe sideways for all columns. Enter full item costs, not unit prices.", fontSize = 12.sp)
            Column(Modifier.horizontalScroll(rememberScrollState())) {
            if (rows.length() > 0) Row(Modifier.background(Color(0xFF087D45)).padding(8.dp)) {
                (listOf("Item" to 140, "Quantity" to 80, "Unit" to 100)+(if(quantityOnly)emptyList() else listOf("Total ₹" to 150))+listOf("Category" to 180,"Action" to 110)).forEach { (label, width) -> Text(label, Modifier.width(width.dp), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            }
            for (i in 0 until rows.length()) {
                val r = rows.getJSONObject(i)
                Surface(color = if (i % 2 == 0) Color.White else Color(0xFFEAF5EE)) { Column(Modifier.width(876.dp).padding(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(140.dp).padding(end=6.dp)) { VoiceField("Item ${i+1}", r.optString("item"), false) { update(i, "item", it) } }
                        Box(Modifier.width(80.dp).padding(end=6.dp)) { VoiceField("Qty", r.optString("quantity"), true) { update(i, "quantity", it) } }
                        Box(Modifier.width(100.dp)) { VoiceChoice("", r.optString("unit"), VoiceUnits) { update(i, "unit", it) } }
                        if(!quantityOnly)Box(Modifier.width(150.dp).padding(end=6.dp)) {
                            if(r.optBoolean("opening_price_applied"))Text("₹${r.optString("amount")}\nOpening-stock rate",fontSize=12.sp,color=Color(0xFF087D45))
                            else VoiceField(if(reuseOpeningPrices)"Missing price · total ₹" else "Total ₹", r.optString("amount"), true) { update(i, "amount", it) }
                        }
                        Box(Modifier.width(180.dp)) { VoiceChoice("Category", r.optString("category"), categories) { update(i, "category", it) } }
                        TextButton(onClick = { val next = JSONArray(draft); next.remove(i); draft = next.toString(); checked = false }, modifier=Modifier.width(110.dp)) { Text("Remove") }
                    }
                    val issues = r.optJSONArray("issues")
                    if(reuseOpeningPrices&&!r.optBoolean("opening_price_applied")&&openingPrices.isNotEmpty()) {
                        VoiceChoice("Match opening item", "", openingPrices.map{it.item}.distinct()) { update(i,"item",it) }
                    }
                    val visibleIssues = if(issues==null) emptyList() else (0 until issues.length()).map{issues.optString(it)}.filterNot{(quantityOnly||r.optBoolean("opening_price_applied")) && it=="purchase-cost valuation"}
                    if (visibleIssues.isNotEmpty()) Text("Please check: " + visibleIssues.joinToString(), fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                } }
            }
            }
            if (rows.length() > 0) {
                Row { Checkbox(checked, { checked = it }); Text(if(mode=="RECIPE") "I checked these raw ingredient quantities for my recipe batch." else if(quantityOnly) "I checked the remaining quantities. This is a stock observation, not an expense." else if (stock) "I checked all items and purchase-cost values. This replaces the selected stock total; it does not add a purchase." else "I checked every item, quantity and full cost, including any flagged differences.", fontSize = 12.sp) }
                TextButton(onClick = { draft = "[]"; transcript = ""; checked = false }) { Text("Discard draft & record again") }
            }
        } }
    }
    if (privacyOpen) AlertDialog(onDismissRequest = { privacyOpen = false }, title = { Text("Voice privacy") }, text = { Text("Google processes audio in Singapore and transcripts in India. Your choice is remembered on this phone for this account. Nothing is saved to your accounts until you review and save the expense sheet.") }, confirmButton = { TextButton(onClick = { privacyOpen = false }) { Text("Done") } }, dismissButton = { TextButton(onClick = { acceptConsent(false); privacyOpen = false; audio = null }) { Text("Withdraw consent") } })
    if (languageOpen) AlertDialog(onDismissRequest = { languageOpen = false }, title = { Text("Voice language") }, text = {
        Column(Modifier.heightIn(max=360.dp).verticalScroll(rememberScrollState())) {
            Text("Saved for this account on this phone. Change anytime in BIKI voice settings.", fontSize=12.sp)
            VoiceLanguages.forEach { (code, label) -> TextButton(onClick = { chooseLanguage(code); languageOpen=false }, modifier=Modifier.fillMaxWidth()) { Text((if(language==code) "✓ " else "") + label) } }
            Text("Not available on the current speech service:", fontSize=12.sp)
            listOf("Bodo", "Dogri", "Kashmiri", "Konkani", "Maithili", "Manipuri (Meitei)", "Sanskrit", "Santali").forEach { Text("$it · unavailable", fontSize=12.sp, modifier=Modifier.padding(8.dp)) }
        }
    }, confirmButton = { TextButton(onClick = { languageOpen=false }) { Text("Close") } })
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("Discard voice draft?") }, text = { Text("No expenses have been saved. An upload already sent may still finish and count toward your daily limit.") }, confirmButton = { TextButton(onClick = { recorder?.cancel(); generation++; onDismiss() }) { Text("Discard") } }, dismissButton = { TextButton(onClick = { discard = false }) { Text("Keep reviewing") } })
}

@Composable private fun VoiceField(label: String, value: String, number: Boolean, change: (String) -> Unit) {
    OutlinedTextField(value, change, label = { Text(label, fontSize = 11.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Decimal else KeyboardType.Text))
}
@Composable private fun VoiceChoice(label: String, value: String, choices: List<String>, change: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box { OutlinedButton(onClick = { expanded = true }) { Text("$label: ${value.ifBlank { "Select" }}", fontSize = 12.sp) }
        DropdownMenu(expanded, { expanded = false }) { choices.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { change(option); expanded = false }) } }
    }
}
