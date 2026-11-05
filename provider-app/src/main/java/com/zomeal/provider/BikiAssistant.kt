package com.zomeal.provider

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/** App-only draggable assistant. No overlay permission or background wake-word listener. */
@Composable internal fun BikiAssistant(repository: SupabaseProviderRepository, onSaved: () -> Unit) {
    var x by rememberSaveable { mutableFloatStateOf(1f) }
    var y by rememberSaveable { mutableFloatStateOf(1f) }
    var menu by remember { mutableStateOf(false) }
    var expense by remember { mutableStateOf(false) }
    var inventory by remember { mutableStateOf(false) }
    var practice by remember { mutableStateOf(false) }
    var recipe by remember { mutableStateOf(false) }
    var shopping by remember { mutableStateOf(false) }
    var stockStep by remember { mutableStateOf<Int?>(null) }
    var book by remember { mutableStateOf<JSONObject?>(null) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val alive = remember { java.util.concurrent.atomic.AtomicBoolean(true) }
    DisposableEffect(Unit) { alive.set(true); onDispose { alive.set(false) } }
    fun day(pattern: String) = SimpleDateFormat(pattern, Locale.ENGLISH).apply { timeZone = TimeZone.getTimeZone("Asia/Kolkata") }.format(Date())
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(start = 12.dp, end = 12.dp, top = 72.dp, bottom = 100.dp)) {
        val maxX = with(density) { (maxWidth - 68.dp).coerceAtLeast(0.dp).toPx() }
        val maxY = with(density) { (maxHeight - 68.dp).coerceAtLeast(0.dp).toPx() }
        Column(
            Modifier.offset { IntOffset((x * maxX).roundToInt(), (y * maxY).roundToInt()) }
                .size(68.dp).shadow(6.dp, CircleShape).clip(CircleShape)
                .background(Brush.verticalGradient(listOf(Color(0xFF0C9650), Color(0xFF076536))))
                .pointerInput(maxX, maxY) {
                    detectDragGestures(onDragEnd = { x = if (x < .5f) 0f else 1f }) { change, amount ->
                        change.consume()
                        if (maxX > 0) x = (x + amount.x / maxX).coerceIn(0f, 1f)
                        if (maxY > 0) y = (y + amount.y / maxY).coerceIn(0f, 1f)
                    }
                }
                .clickable { menu = true; message = null }
                .semantics { contentDescription = "Open BIKI kitchen voice assistant. Drag to move." },
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Outlined.SmartToy, null, tint = Color(0xFFE5F79B), modifier = Modifier.size(29.dp))
            Text("BIKI", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
        }
    }
    if (menu) AlertDialog(
        onDismissRequest = { if (!loading) menu = false },
        icon = { Icon(Icons.Outlined.SmartToy, null, tint = Color(0xFF087F43)) },
        title = { Text("Hi, I'm BIKI", fontSize = 20.sp) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Your kitchen voice assistant · Your language", fontSize = 12.sp)
            Text("Tap to record, speak, then stop and review. I don't listen in the background or learn automatically.", fontSize = 12.sp)
            if (!repository.voiceConfigured) Text("Preview build: microphone testing works, but AI submission is not connected yet.", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            message?.let { Text(it, fontSize = 12.sp) }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            Button(onClick = { menu = false; expense = true }, enabled = !loading, modifier = Modifier.fillMaxWidth()) { Text("Record expenses") }
            OutlinedButton(onClick = { menu=false;recipe=true }, modifier=Modifier.fillMaxWidth()) { Text("Teach BIKI · My recipes") }
            OutlinedButton(onClick = { menu=false;shopping=true }, modifier=Modifier.fillMaxWidth()) { Text("Ingredients & shopping planner") }
            OutlinedButton(onClick = { menu = false; inventory = true }, enabled = !loading, modifier = Modifier.fillMaxWidth()) { Text("Inventory · 7 / 15-day review") }
            OutlinedButton(onClick = { menu = false; practice = true }, enabled = !loading, modifier = Modifier.fillMaxWidth()) { Text("Practice kitchen · 100–500 customers") }
            listOf(0 to "Opening stock · start of month", 1 to "Remaining stock · this month").forEach { (step, label) ->
                OutlinedButton(onClick = {
                    loading = true; message = null
                    repository.kitchenBook(day("yyyy-MM-01")) { result, problem ->
                        if (alive.get()) {
                            loading = false
                            if (result?.has("statement") == true && problem == null) { book = result; stockStep = step; menu = false }
                            else message = problem ?: "Couldn't load stock totals. Try again."
                        }
                    }
                }, enabled = !loading, modifier = Modifier.fillMaxWidth()) { Text(label, fontSize = 12.sp) }
            }
            Text("Example: Hi BIKI, 2 kg tomato total ₹40, aur 5 kg chawal total ₹100. Always confirm which price belongs to which item.", fontSize = 11.sp)
        } },
        confirmButton = { TextButton(onClick = { menu = false }, enabled = !loading) { Text("Close") } }
    )
    if (expense) KitchenCostSheet(day("yyyy-MM-dd"), repository = repository, startWithVoice = true,
        onDismiss = { expense = false }, onSave = { rows, done ->
            repository.saveKitchenSheet(rows) { _, problem ->
                if (alive.get()) { done(problem); if (problem == null) { expense = false; onSaved() } }
            }
        })
    if (inventory) KitchenInventoryDialog(repository,onDismiss={inventory=false},onSaved=onSaved)
    if (practice) BikiPracticeDialog(onClose={practice=false})
    if (recipe) BikiRecipeSheet(repository,onClose={recipe=false})
    if (shopping) BikiShoppingSheet(repository,onClose={shopping=false})
    if (stockStep != null && book != null) KitchenReviewDialog(book!!, repository = repository, initialVoiceStep = stockStep,
        onDismiss = { stockStep = null; book = null }, onSave = { payload, done ->
            repository.saveKitchenReview(payload) { _, problem ->
                if (alive.get()) { done(problem); if (problem == null) { stockStep = null; book = null; onSaved() } }
            }
        })
}
