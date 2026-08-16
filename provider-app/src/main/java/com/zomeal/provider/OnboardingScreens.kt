package com.zomeal.provider

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.math.RoundingMode
import java.util.UUID

private val PBrand = Color(0xFF087F43)
private val PInk = Color(0xFF14221B)
private val PMuted = Color(0xFF68736D)
private val PMist = Color(0xFFF1F7F2)
private val days = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

/**
 * Android's system photo picker may revoke its content URI after the activity or
 * app process ends. Copy selections into app-private storage immediately so a
 * provider can safely resume a draft and upload the same photo days later.
 */
private fun persistProviderPhoto(context: Context, source: Uri): String? = runCatching {
    val resolver = context.contentResolver
    val mime = resolver.getType(source).orEmpty()
    val extension = when (mime) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        else -> "jpg"
    }
    val directory = File(context.filesDir, "provider-draft-media").apply { mkdirs() }
    val destination = File(directory, "${UUID.randomUUID()}.$extension")
    resolver.openInputStream(source)?.use { input ->
        destination.outputStream().use { output -> input.copyTo(output) }
    } ?: error("Selected photo cannot be read")
    Uri.fromFile(destination).toString()
}.getOrNull()

private fun isProviderPhotoReadable(context: Context, value: String?): Boolean {
    if (value.isNullOrBlank()) return false
    return runCatching { context.contentResolver.openInputStream(Uri.parse(value))?.use { it.read() } != null }.getOrDefault(false)
}

internal class DishDraft(name: String = "") {
    var name by mutableStateOf(name)
    var description by mutableStateOf("")
    var photo by mutableStateOf<String?>(null)
}

internal class DayDraft {
    val lunch = mutableStateListOf(DishDraft())
    val dinner = mutableStateListOf(DishDraft())
    var lunchFixed by mutableStateOf("Dal, rice/roti, salad")
    var dinnerFixed by mutableStateOf("Dal, roti, salad")
}

internal class PincodeDraft(value: String = "") {
    var value by mutableStateOf(value)
    var areaName by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)
    var verified by mutableStateOf(false)
}

internal object ProviderDraft {
    var ownerPhone by mutableStateOf("")
    var businessName by mutableStateOf("")
    var contactName by mutableStateOf("")
    var category by mutableStateOf("Both")
    var address by mutableStateOf("")
    var city by mutableStateOf("Bhubaneswar")
    var state by mutableStateOf("Odisha")
    var pincode by mutableStateOf("")
    val servicePincodes = mutableStateListOf(PincodeDraft())
    var radius by mutableStateOf("5")
    var lunchCapacity by mutableStateOf("50")
    var dinnerCapacity by mutableStateOf("50")
    var lunchEnabled by mutableStateOf(true)
    var dinnerEnabled by mutableStateOf(true)
    var bothEnabled by mutableStateOf(true)
    var lunchPrice by mutableStateOf("")
    var dinnerPrice by mutableStateOf("")
    var bothPrice by mutableStateOf("")
    var bothLunchDailyPrice by mutableStateOf("")
    val menus = days.map { DayDraft() }
    val savedMenuDays = mutableStateListOf<Boolean>().apply { repeat(7) { add(false) } }
    var deliveryName by mutableStateOf("")
    var deliveryPhone by mutableStateOf("")
    var profilePhoto by mutableStateOf<String?>(null)
    var kitchenPhoto by mutableStateOf<String?>(null)
    var mealPhoto by mutableStateOf<String?>(null)

    fun toJson(): JSONObject = JSONObject().apply {
        put("businessName", businessName); put("contactName", contactName); put("category", category); put("ownerPhone", ownerPhone)
        put("address", address); put("city", city); put("state", state); put("pincode", pincode)
        put("radius", radius); put("lunchCapacity", lunchCapacity); put("dinnerCapacity", dinnerCapacity)
        put("lunchEnabled", lunchEnabled); put("dinnerEnabled", dinnerEnabled); put("bothEnabled", bothEnabled)
        put("lunchPrice", lunchPrice); put("dinnerPrice", dinnerPrice); put("bothPrice", bothPrice); put("bothLunchDailyPrice", bothLunchDailyPrice)
        put("deliveryName", deliveryName); put("deliveryPhone", deliveryPhone)
        put("servicePincodes", JSONArray().apply { servicePincodes.forEach { p -> put(JSONObject().put("value", p.value).put("areaName", p.areaName).put("verified", p.verified)) } })
        put("menus", JSONArray().apply { menus.forEachIndexed { index, day ->
            put(JSONObject().put("day", days[index]).put("lunchFixed", day.lunchFixed).put("dinnerFixed", day.dinnerFixed)
                .put("lunch", dishesJson(day.lunch)).put("dinner", dishesJson(day.dinner)))
        } })
        put("savedMenuDays", JSONArray().apply { savedMenuDays.forEach(::put) })
    }

    private fun dishesJson(dishes: List<DishDraft>) = JSONArray().apply {
        dishes.forEach { put(JSONObject().put("name", it.name).put("description", it.description).put("photo", it.photo)) }
    }

    fun restore(json: JSONObject) {
        businessName = json.optString("businessName"); contactName = json.optString("contactName"); category = json.optString("category", "Both")
        if (ownerPhone.isBlank()) ownerPhone = json.optString("ownerPhone")
        address = json.optString("address"); city = json.optString("city", "Bhubaneswar"); state = json.optString("state", "Odisha"); pincode = json.optString("pincode")
        radius = json.optString("radius", "5"); lunchCapacity = json.optString("lunchCapacity", "50"); dinnerCapacity = json.optString("dinnerCapacity", "50")
        lunchEnabled = json.optBoolean("lunchEnabled", true); dinnerEnabled = json.optBoolean("dinnerEnabled", true); bothEnabled = json.optBoolean("bothEnabled", true)
        lunchPrice = json.optString("lunchPrice"); dinnerPrice = json.optString("dinnerPrice"); bothPrice = json.optString("bothPrice"); bothLunchDailyPrice = json.optString("bothLunchDailyPrice")
        deliveryName = json.optString("deliveryName"); deliveryPhone = json.optString("deliveryPhone")
        json.optJSONArray("servicePincodes")?.let { array ->
            servicePincodes.clear()
            for (i in 0 until array.length()) array.optJSONObject(i)?.let { item ->
                servicePincodes.add(PincodeDraft(item.optString("value")).also { p -> p.areaName = item.optString("areaName").ifBlank { null }; p.verified = item.optBoolean("verified") })
            }
            if (servicePincodes.isEmpty()) servicePincodes.add(PincodeDraft())
        }
        json.optJSONArray("menus")?.let { menuArray ->
            for (i in 0 until minOf(7, menuArray.length())) menuArray.optJSONObject(i)?.let { source ->
                val target = menus[i]
                target.lunchFixed = source.optString("lunchFixed", target.lunchFixed); target.dinnerFixed = source.optString("dinnerFixed", target.dinnerFixed)
                restoreDishes(source.optJSONArray("lunch"), target.lunch); restoreDishes(source.optJSONArray("dinner"), target.dinner)
            }
        }
        val savedArray = json.optJSONArray("savedMenuDays")
        if (savedArray != null) {
            for (i in 0 until 7) savedMenuDays[i] = savedArray.optBoolean(i, false)
        } else {
            // Drafts created before per-day submission existed are already stored remotely.
            // Preserve that work by treating each complete legacy day as submitted.
            val offersLunch = lunchEnabled || bothEnabled
            val offersDinner = dinnerEnabled || bothEnabled
            menus.forEachIndexed { index, day ->
                savedMenuDays[index] = (!offersLunch || day.lunch.any { it.name.isNotBlank() }) &&
                    (!offersDinner || day.dinner.any { it.name.isNotBlank() })
            }
        }
    }

    private fun restoreDishes(array: JSONArray?, target: SnapshotStateList<DishDraft>) {
        if (array == null) return
        target.clear()
        for (i in 0 until array.length()) array.optJSONObject(i)?.let { source ->
            target.add(DishDraft(source.optString("name")).also { it.description = source.optString("description"); it.photo = source.optString("photo").ifBlank { null } })
        }
        if (target.isEmpty()) target.add(DishDraft())
    }
}

@Composable
internal fun ProviderDraftAutoSave(repository: SupabaseProviderRepository, enabled: Boolean, onStatus: (String) -> Unit) {
    val snapshot = ProviderDraft.toJson().toString()
    LaunchedEffect(snapshot, enabled) {
        if (!enabled) return@LaunchedEffect
        onStatus("Saving…")
        delay(900)
        repository.saveDraft(ProviderDraft.toJson()) { result -> onStatus(if (result.success) "Draft saved" else result.message ?: "Save failed") }
    }
}

@Composable
private fun FlowScaffold(
    step: Int,
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    onNext: () -> Unit,
    nextLabel: String = "Save & continue",
    nextEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    Scaffold(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
        containerColor = Color(0xFFF7FAF7),
        topBar = {
            Column(Modifier.background(Color.White).padding(horizontal = 18.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "Back", tint = PBrand) }
                    Column(Modifier.weight(1f)) {
                        Text("Step $step of 6", color = PBrand, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text(title, color = PInk, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                    Text("zomeal", color = PBrand, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                }
                LinearProgressIndicator(
                    progress = { step / 6f },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(CircleShape),
                    color = PBrand,
                    trackColor = PMist
                )
            }
        },
        bottomBar = {
            Surface(shadowElevation = 8.dp) {
                Button(
                    onClick = onNext,
                    enabled = nextEnabled,
                    modifier = Modifier.fillMaxWidth().padding(16.dp).height(54.dp),
                    shape = RoundedCornerShape(15.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PBrand)
                ) { Text(nextLabel, fontWeight = FontWeight.Bold, fontSize = 15.sp); Spacer(Modifier.width(8.dp)); Icon(Icons.Outlined.ArrowForward, null) }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(18.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            item { Text(subtitle, color = PMuted, fontSize = 13.sp, lineHeight = 19.sp) }
            item { Column(verticalArrangement = Arrangement.spacedBy(12.dp), content = content) }
            item { Spacer(Modifier.height(4.dp)) }
        }
    }
}

@Composable
private fun Section(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color.White).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, color = PInk, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        subtitle?.let { Text(it, color = PMuted, fontSize = 12.sp, lineHeight = 17.sp) }
        content()
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, hint: String = "", number: Boolean = false, singleLine: Boolean = true, decimal: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, color = PInk, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(hint, fontSize = 13.sp) },
            singleLine = singleLine,
            keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else if (number) KeyboardType.Number else KeyboardType.Text),
            shape = RoundedCornerShape(13.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = PBrand, cursorColor = PBrand)
        )
    }
}

@Composable
fun BusinessScreen(onBack: () -> Unit, onNext: () -> Unit) {
    FlowScaffold(1, "Business details", "Tell us the basic information customers will see. Your draft stays available while you complete setup.", onBack, onNext,
        nextEnabled = ProviderDraft.businessName.isNotBlank() && ProviderDraft.contactName.isNotBlank() && ProviderDraft.address.isNotBlank()) {
        Section("Provider identity") {
            Field("Business / kitchen name *", ProviderDraft.businessName, { ProviderDraft.businessName = it }, "e.g. Swaad Ghar")
            Field("Contact person *", ProviderDraft.contactName, { ProviderDraft.contactName = it }, "Authorized representative")
            Text("Food category", color = PInk, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf("Veg", "Non-Veg", "Both").forEach { option ->
                    FilterChip(selected = ProviderDraft.category == option, onClick = { ProviderDraft.category = option }, label = { Text(option) })
                }
            }
        }
        Section("Business location", "This is the kitchen/business address, not your bank address.") {
            Field("Address *", ProviderDraft.address, { ProviderDraft.address = it }, "Building, street and landmark", singleLine = false)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) { Field("City", ProviderDraft.city, { ProviderDraft.city = it }) }
                Box(Modifier.weight(1f)) { Field("State", ProviderDraft.state, { ProviderDraft.state = it }) }
            }
            Field("Business pincode", ProviderDraft.pincode, { ProviderDraft.pincode = it.filter(Char::isDigit).take(6) }, "6-digit pincode", true)
        }
        InfoCard("Bank and UPI details are not required now. We will request payout details securely after Zomeal activates your provider account.")
    }
}

@Composable
fun ServiceAreaScreen(onBack: () -> Unit, onNext: () -> Unit) {
    FlowScaffold(2, "Service areas & capacity", "Add every pincode you can reliably serve and your realistic daily meal capacity.", onBack, onNext,
        nextEnabled = ProviderDraft.servicePincodes.any { it.verified }) {
        Section("Delivery coverage") {
            Text("Serviceable pincodes *", color = PInk, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            ProviderDraft.servicePincodes.forEachIndexed { index, pincode ->
                PincodeEditor(
                    pincode = pincode,
                    canRemove = ProviderDraft.servicePincodes.size > 1,
                    onRemove = { ProviderDraft.servicePincodes.removeAt(index) }
                )
            }
            OutlinedButton(
                onClick = { ProviderDraft.servicePincodes.add(PincodeDraft()) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(13.dp)
            ) { Icon(Icons.Outlined.AddLocationAlt, null); Spacer(Modifier.width(7.dp)); Text("Add another pincode") }
            Text("Each pincode is entered and verified separately. Zomeal admin approval is still required before the area becomes visible to customers.", color = PMuted, fontSize = 11.sp)
            Field("Preferred delivery radius (km)", ProviderDraft.radius, { ProviderDraft.radius = it.filter(Char::isDigit) }, "5", true)
        }
        Section("Daily capacity") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) { Field("Lunch meals", ProviderDraft.lunchCapacity, { ProviderDraft.lunchCapacity = it.filter(Char::isDigit) }, number = true) }
                Box(Modifier.weight(1f)) { Field("Dinner meals", ProviderDraft.dinnerCapacity, { ProviderDraft.dinnerCapacity = it.filter(Char::isDigit) }, number = true) }
            }
            InfoCard("You can update capacity later. The app should stop accepting new subscriptions when capacity is full.")
        }
    }
}

private fun verifyPincode(pincode: PincodeDraft) {
    val knownAreas = mapOf(
        "751030" to "Khandagiri, Bhubaneswar",
        "751019" to "Patrapada, Bhubaneswar",
        "751003" to "Baramunda, Bhubaneswar",
        "751012" to "Nayapalli, Bhubaneswar",
        "751024" to "Patia, Bhubaneswar"
    )
    if (pincode.value.length != 6) {
        pincode.verified = false
        pincode.areaName = null
        pincode.error = "Enter a valid 6-digit pincode"
        return
    }
    pincode.verified = true
    pincode.error = null
    pincode.areaName = knownAreas[pincode.value] ?: "Valid Indian pincode · area lookup pending"
}

@Composable
private fun PincodeEditor(pincode: PincodeDraft, canRemove: Boolean, onRemove: () -> Unit) {
    Surface(color = PMist, shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                OutlinedTextField(
                    value = pincode.value,
                    onValueChange = {
                        pincode.value = it.filter(Char::isDigit).take(6)
                        pincode.verified = false
                        pincode.areaName = null
                        pincode.error = null
                    },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("6-digit pincode") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    isError = pincode.error != null,
                    shape = RoundedCornerShape(12.dp)
                )
                Button(
                    onClick = { verifyPincode(pincode) },
                    enabled = pincode.value.length == 6,
                    contentPadding = PaddingValues(horizontal = 13.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PBrand)
                ) { Text(if (pincode.verified) "Verified" else "Verify", fontSize = 12.sp) }
                if (canRemove) IconButton(onClick = onRemove) { Icon(Icons.Outlined.Delete, "Remove", tint = Color(0xFFB23A32)) }
            }
            pincode.areaName?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.CheckCircle, null, tint = PBrand, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp)); Text(it, color = PBrand, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            pincode.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp) }
        }
    }
}

@Composable
fun PackageScreen(onBack: () -> Unit, onNext: () -> Unit) {
    val combinedTotal = ProviderDraft.bothPrice.toBigDecimalOrNull()
    val combinedLunchDaily = ProviderDraft.bothLunchDailyPrice.toBigDecimalOrNull()
    val validCombinedSplit = !ProviderDraft.bothEnabled || (combinedTotal != null && combinedLunchDaily != null &&
        combinedLunchDaily > java.math.BigDecimal.ZERO && combinedLunchDaily.multiply(java.math.BigDecimal(30)) < combinedTotal)
    val hasSelection = ProviderDraft.lunchEnabled || ProviderDraft.dinnerEnabled || ProviderDraft.bothEnabled
    val valid = hasSelection &&
        (!ProviderDraft.lunchEnabled || ProviderDraft.lunchPrice.isNotBlank()) &&
        (!ProviderDraft.dinnerEnabled || ProviderDraft.dinnerPrice.isNotBlank()) &&
        (!ProviderDraft.bothEnabled || ProviderDraft.bothPrice.isNotBlank()) && validCombinedSplit
    FlowScaffold(3, "Choose packages", "Lunch, dinner and combined packages are selected by default. Turn off anything you do not want to provide, then enter prices for the selected packages.", onBack, onNext, nextEnabled = valid) {
        InfoCard("You control which packages your kitchen offers. At least one selected package with a price is required to continue.")
        PackageEditor("Lunch only", "1 meal/day · 30 days", ProviderDraft.lunchEnabled, { ProviderDraft.lunchEnabled = it }, ProviderDraft.lunchPrice, { ProviderDraft.lunchPrice = it.filter(Char::isDigit) })
        PackageEditor("Dinner only", "1 meal/day · 30 days", ProviderDraft.dinnerEnabled, { ProviderDraft.dinnerEnabled = it }, ProviderDraft.dinnerPrice, { ProviderDraft.dinnerPrice = it.filter(Char::isDigit) })
        Section("Lunch + Dinner") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("2 meals/day · 30 days", color = PMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Switch(ProviderDraft.bothEnabled, { ProviderDraft.bothEnabled = it }, colors = SwitchDefaults.colors(checkedTrackColor = PBrand))
            }
            if (ProviderDraft.bothEnabled) {
                Field("Combined monthly price (₹) *", ProviderDraft.bothPrice, { ProviderDraft.bothPrice = it.filter(Char::isDigit) }, "3000", true)
                val dailyTotal = combinedTotal?.divide(java.math.BigDecimal(30), 2, RoundingMode.HALF_UP)
                dailyTotal?.let { Text("Combined daily meal value: ₹$it", color = PBrand, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                Field("Lunch value per day (₹) *", ProviderDraft.bothLunchDailyPrice, {
                    ProviderDraft.bothLunchDailyPrice = it.filterIndexed { index, char -> char.isDigit() || (char == '.' && index > 0 && !it.take(index).contains('.')) }.take(7)
                }, "e.g. 45", decimal = true)
                if (combinedTotal != null && combinedLunchDaily != null && combinedLunchDaily > java.math.BigDecimal.ZERO) {
                    val dinnerDaily = combinedTotal.subtract(combinedLunchDaily.multiply(java.math.BigDecimal(30))).divide(java.math.BigDecimal(30), 2, RoundingMode.HALF_UP)
                    Text("Dinner value per day (automatic): ₹$dinnerDaily", color = if (dinnerDaily > java.math.BigDecimal.ZERO) PBrand else MaterialTheme.colorScheme.error, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text("30 lunch values + 30 dinner values always equal the combined monthly price.", color = PMuted, fontSize = 10.sp)
                }
                if (!validCombinedSplit) Text("Lunch value must be greater than ₹0 and lower than the combined daily value.", color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
            }
        }
        InfoCard("Zomeal commission: 14% initially. Customer platform fee and ₹99 monthly delivery charge are handled separately.")
    }
}

@Composable
private fun PackageEditor(title: String, detail: String, enabled: Boolean, onEnabled: (Boolean) -> Unit, price: String, onPrice: (String) -> Unit) {
    Section(title) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(detail, color = PMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Switch(enabled, onEnabled, colors = SwitchDefaults.colors(checkedTrackColor = PBrand))
        }
        if (enabled) Field("Monthly price (₹) *", price, onPrice, "Enter package price", true)
    }
}

@Composable
fun WeeklyMenuScreen(
    onBack: () -> Unit,
    onNext: () -> Unit,
    onSaveDay: (Int, (AuthResult) -> Unit) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {
        ProviderDraft.menus.forEach { day ->
            (day.lunch + day.dinner).forEach { dish ->
                if (dish.photo != null && !isProviderPhotoReadable(context, dish.photo)) dish.photo = null
            }
        }
    }
    var selected by remember { mutableIntStateOf(ProviderDraft.savedMenuDays.indexOfFirst { !it }.let { if (it < 0) 0 else it }) }
    var activeDish by remember { mutableStateOf<DishDraft?>(null) }
    var saving by remember { mutableStateOf(false) }
    var dayMessage by remember { mutableStateOf<String?>(null) }
    val markCurrentDirty = { ProviderDraft.savedMenuDays[selected] = false; dayMessage = null }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { selectedUri ->
            persistProviderPhoto(context, selectedUri)?.let { storedUri ->
                activeDish?.photo = storedUri
                markCurrentDirty()
            }
        }
    }
    val offersLunch = ProviderDraft.lunchEnabled || ProviderDraft.bothEnabled
    val offersDinner = ProviderDraft.dinnerEnabled || ProviderDraft.bothEnabled
    val completedDays = ProviderDraft.savedMenuDays.count { it }
    val currentValid = (!offersLunch || ProviderDraft.menus[selected].lunch.any { it.name.isNotBlank() }) &&
        (!offersDinner || ProviderDraft.menus[selected].dinner.any { it.name.isNotBlank() })
    val saveCurrentDay = {
        if (!currentValid) {
            dayMessage = "Add at least one ${if (offersLunch && offersDinner) "lunch and one dinner" else if (offersLunch) "lunch" else "dinner"} main course before saving ${days[selected]}."
        } else {
            val dayBeingSaved = selected
            saving = true
            dayMessage = null
            ProviderDraft.savedMenuDays[dayBeingSaved] = true
            onSaveDay(dayBeingSaved) { result ->
                saving = false
                if (result.success) {
                    dayMessage = "${days[dayBeingSaved]} menu saved to Zomeal."
                    val nextUnfinished = ((dayBeingSaved + 1) until 7).firstOrNull { !ProviderDraft.savedMenuDays[it] }
                        ?: (0 until dayBeingSaved).firstOrNull { !ProviderDraft.savedMenuDays[it] }
                    if (nextUnfinished != null) selected = nextUnfinished else onNext()
                } else {
                    ProviderDraft.savedMenuDays[dayBeingSaved] = false
                    dayMessage = result.message ?: "Could not save ${days[dayBeingSaved]}. Please try again."
                }
            }
        }
    }
    FlowScaffold(4, "Monday–Sunday menus", "Complete and submit one day at a time. Every saved day is stored in Zomeal, so you can resume from the next unfinished day later.", onBack, saveCurrentDay,
        nextLabel = if (saving) "Saving ${days[selected]}…" else if (completedDays == 6 && !ProviderDraft.savedMenuDays[selected]) "Save ${days[selected]} & continue" else "Save ${days[selected]} & next",
        nextEnabled = !saving) {
        Section("Choose day") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                days.chunked(4).forEachIndexed { rowIndex, row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEachIndexed { index, day ->
                            val actual = rowIndex * 4 + index
                            FilterChip(
                                selected = selected == actual,
                                onClick = { if (!saving) { selected = actual; dayMessage = null } },
                                label = { Text((if (ProviderDraft.savedMenuDays[actual]) "✓ " else "") + day.take(3)) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        if (offersLunch) {
            MealMenuEditor("Lunch", ProviderDraft.menus[selected].lunch, ProviderDraft.menus[selected].lunchFixed, { ProviderDraft.menus[selected].lunchFixed = it; markCurrentDirty() }, markCurrentDirty) { dish -> activeDish = dish; picker.launch("image/*") }
        }
        if (offersDinner) {
            MealMenuEditor("Dinner", ProviderDraft.menus[selected].dinner, ProviderDraft.menus[selected].dinnerFixed, { ProviderDraft.menus[selected].dinnerFixed = it; markCurrentDirty() }, markCurrentDirty) { dish -> activeDish = dish; picker.launch("image/*") }
        }
        dayMessage?.let { InfoCard(it) }
        InfoCard("$completedDays of 7 days submitted. Green checkmarks show days safely stored in Zomeal. All seven days are required before account activation, and every photo stays hidden until approved.")
    }
}

@Composable
private fun MealMenuEditor(title: String, dishes: SnapshotStateList<DishDraft>, fixed: String, onFixed: (String) -> Unit, onDirty: () -> Unit, onPhoto: (DishDraft) -> Unit) {
    Section("$title menu") {
        dishes.forEachIndexed { index, dish ->
            Surface(color = PMist, shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Main course ${index + 1}", color = PBrand, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        if (dishes.size > 1) IconButton(onClick = { dishes.remove(dish); onDirty() }) { Icon(Icons.Outlined.Delete, "Remove", tint = Color(0xFFB23A32)) }
                    }
                    Field("Dish name *", dish.name, { dish.name = it; onDirty() }, "e.g. Paneer butter masala")
                    Field("Description (optional)", dish.description, { dish.description = it; onDirty() }, "Ingredients, nutrition or allergen note", singleLine = false)
                    OutlinedButton(onClick = { onPhoto(dish) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(if (dish.photo == null) Icons.Outlined.AddAPhoto else Icons.Outlined.CheckCircle, null)
                        Spacer(Modifier.width(7.dp)); Text(if (dish.photo == null) "Upload this dish photo" else "Photo selected · Change")
                    }
                }
            }
        }
        TextButton(onClick = { dishes.add(DishDraft()); onDirty() }) { Icon(Icons.Outlined.Add, null); Text("Add another main course") }
        Field("Fixed / included items", fixed, onFixed, "Dal, rice/roti, salad")
    }
}

@Composable
fun OperationsScreen(onBack: () -> Unit, onNext: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {
        if (ProviderDraft.profilePhoto != null && !isProviderPhotoReadable(context, ProviderDraft.profilePhoto)) ProviderDraft.profilePhoto = null
        if (ProviderDraft.kitchenPhoto != null && !isProviderPhotoReadable(context, ProviderDraft.kitchenPhoto)) ProviderDraft.kitchenPhoto = null
        if (ProviderDraft.mealPhoto != null && !isProviderPhotoReadable(context, ProviderDraft.mealPhoto)) ProviderDraft.mealPhoto = null
    }
    var target by remember { mutableStateOf(0) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { selectedUri -> persistProviderPhoto(context, selectedUri)?.let { storedUri ->
            when (target) { 0 -> ProviderDraft.profilePhoto = storedUri; 1 -> ProviderDraft.kitchenPhoto = storedUri; else -> ProviderDraft.mealPhoto = storedUri }
        } }
    }
    FlowScaffold(5, "Photos & delivery", "Add authentic business photos and at least one delivery contact. Photos remain pending until admin approval.", onBack, onNext,
        nextEnabled = ProviderDraft.deliveryPhone.length == 10) {
        Section("Provider photographs", "You may finish optional photos later, but clear photos improve customer trust.") {
            PhotoRow("Provider profile", ProviderDraft.profilePhoto != null) { target = 0; picker.launch("image/*") }
            PhotoRow("Kitchen / hygiene", ProviderDraft.kitchenPhoto != null) { target = 1; picker.launch("image/*") }
            PhotoRow("Complete meal / thali", ProviderDraft.mealPhoto != null) { target = 2; picker.launch("image/*") }
        }
        Section("Primary delivery contact", "At least one delivery phone number is required and can be changed later.") {
            Field("Delivery person name", ProviderDraft.deliveryName, { ProviderDraft.deliveryName = it }, "Full name")
            Field("Delivery phone number *", ProviderDraft.deliveryPhone, { ProviderDraft.deliveryPhone = it.filter(Char::isDigit).take(10) }, "10-digit mobile number", true)
            TextButton(onClick = { }) { Icon(Icons.Outlined.PersonAdd, null); Text("Add another delivery person later") }
        }
    }
}

@Composable
private fun PhotoRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(PMist).clickable(onClick = onClick).padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (selected) Icons.Outlined.CheckCircle else Icons.Outlined.AddAPhoto, null, tint = PBrand)
        Spacer(Modifier.width(10.dp)); Text(label, color = PInk, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text(if (selected) "Selected" else "Upload", color = PBrand, fontSize = 12.sp)
    }
}

@Composable
fun ReviewScreen(onBack: () -> Unit, submitting: Boolean, submissionError: String?, onNext: () -> Unit) {
    var confirmed by remember { mutableStateOf(false) }
    FlowScaffold(6, "Review application", "Check the activation essentials before submitting. You can return and edit any section.", onBack, onNext, if (submitting) "Submitting application…" else "Submit for review", confirmed && !submitting) {
        ReviewLine("Business", ProviderDraft.businessName.ifBlank { "Not completed" }, ProviderDraft.category)
        ReviewLine("Serviceability", "${ProviderDraft.servicePincodes.count { it.verified }} verified pincodes", "Lunch ${ProviderDraft.lunchCapacity} · Dinner ${ProviderDraft.dinnerCapacity}")
        ReviewLine("Packages", listOf(ProviderDraft.lunchEnabled, ProviderDraft.dinnerEnabled, ProviderDraft.bothEnabled).count { it }.toString() + " active", "Prices require Zomeal approval")
        ReviewLine("Weekly menu", "${ProviderDraft.menus.sumOf { it.lunch.size + it.dinner.size }} dish entries", "7 days · Lunch and dinner")
        ReviewLine("Delivery", ProviderDraft.deliveryName.ifBlank { "Primary delivery person" }, "+91 ${ProviderDraft.deliveryPhone}")
        Section("Before you submit") {
            Row(Modifier.clickable { confirmed = !confirmed }, verticalAlignment = Alignment.Top) {
                Checkbox(confirmed, { confirmed = it }, colors = CheckboxDefaults.colors(checkedColor = PBrand))
                Text("I confirm the menu, price, capacity and service-area information is accurate and I am authorized to submit it.", color = PMuted, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 11.dp))
            }
        }
        InfoCard("Submission does not activate the profile immediately. Zomeal will review mandatory data, service areas, prices and photographs first.")
        submissionError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, lineHeight = 17.sp) }
    }
}

@Composable
private fun ReviewLine(title: String, value: String, detail: String) {
    Section(title) {
        Text(value, color = PInk, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(detail, color = PMuted, fontSize = 12.sp)
    }
}

@Composable
fun SubmittedScreen(repository: SupabaseProviderRepository, onReview: () -> Unit, onEdit: () -> Unit, onDashboard: () -> Unit, onDone: () -> Unit) {
    var status by remember { mutableStateOf("PENDING_APPROVAL") }
    var requests by remember { mutableStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    var statusError by remember { mutableStateOf<String?>(null) }
    fun refresh() {
        refreshing = true
        repository.loadApplicationStatus { json ->
            status = json?.optString("status")?.ifBlank { "PENDING_APPROVAL" } ?: "PENDING_APPROVAL"
            requests = json?.optJSONArray("change_requests")?.length() ?: 0
            refreshing = false
        }
    }
    LaunchedEffect(Unit) { refresh() }
    Column(Modifier.fillMaxSize().background(Color(0xFFF7FAF7)).windowInsetsPadding(WindowInsets.safeDrawing).padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(.5f))
        Box(Modifier.size(92.dp).clip(CircleShape).background(PMist), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.TaskAlt, null, tint = PBrand, modifier = Modifier.size(53.dp)) }
        Spacer(Modifier.height(20.dp))
        Text(if (status == "ACTIVE") "Account activated!" else "Application submitted!", color = PInk, fontSize = 27.sp, fontWeight = FontWeight.Bold)
        Text("Welcome to the Zomeal partner family", color = PBrand, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(22.dp))
        Surface(color = if (status == "ACTIVE") Color(0xFFE6F5EA) else Color(0xFFFFF4D8), shape = RoundedCornerShape(50.dp)) {
            Text(status.replace('_', ' ').lowercase().replaceFirstChar(Char::uppercase), color = if (status == "ACTIVE") PBrand else Color(0xFF8A6500), fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp))
        }
        if (requests > 0) Text("$requests admin change request(s) require attention", color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(top = 9.dp))
        Spacer(Modifier.height(14.dp))
        Section("What happens next?") {
            StatusLine("1", "Zomeal reviews your business, menus and prices")
            StatusLine("2", "Photos and serviceable pincodes are approved")
            StatusLine("3", "After activation, add your bank account or payout UPI")
        }
        InfoCard("You may be contacted if information is missing. Your provider listing remains hidden until activation.")
        Spacer(Modifier.weight(1f))
        Button(onClick = { refresh() }, enabled = !refreshing, modifier = Modifier.fillMaxWidth().height(55.dp), shape = RoundedCornerShape(15.dp), colors = ButtonDefaults.buttonColors(containerColor = PBrand)) {
            if (refreshing) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp) else Text("Refresh application status", fontWeight = FontWeight.Bold)
        }
        OutlinedButton(onClick = onReview, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(50.dp)) {
            Icon(Icons.Outlined.Visibility, null); Spacer(Modifier.width(8.dp)); Text("Review submitted data")
        }
        if (status == "ACTIVE") {
            TextButton(onClick = onDashboard, modifier = Modifier.fillMaxWidth().height(44.dp)) {
                Icon(Icons.Outlined.Dashboard, null); Spacer(Modifier.width(7.dp)); Text("Open provider dashboard")
            }
        } else if (status == "DRAFT" || status == "PENDING_APPROVAL") {
            TextButton(onClick = {
                refreshing = true; statusError = null
                repository.resumeReturnedApplication { result ->
                    refreshing = false
                    if (result.success) onEdit() else statusError = result.message
                }
            }, modifier = Modifier.fillMaxWidth().height(44.dp)) {
                Icon(Icons.Outlined.Edit, null); Spacer(Modifier.width(7.dp)); Text(if (status == "DRAFT") "Edit requested changes" else "Edit & update application")
            }
            TextButton(onClick = onDashboard, modifier = Modifier.fillMaxWidth().height(42.dp)) {
                Icon(Icons.Outlined.DashboardCustomize, null); Spacer(Modifier.width(7.dp)); Text("Preview provider dashboard")
            }
        }
        statusError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp) }
        TextButton(onClick = onDone) { Text("Sign out", color = PMuted) }
    }
}

@Composable
fun SubmittedApplicationDetailsScreen(repository: SupabaseProviderRepository, onBack: () -> Unit, onEdit: () -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var editError by remember { mutableStateOf<String?>(null) }
    val offersLunch = ProviderDraft.lunchEnabled || ProviderDraft.bothEnabled
    val offersDinner = ProviderDraft.dinnerEnabled || ProviderDraft.bothEnabled
    Scaffold(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
        containerColor = Color(0xFFF7FAF7),
        topBar = {
            Surface(color = Color.White, shadowElevation = 2.dp) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "Back", tint = PBrand) }
                    Column(Modifier.weight(1f)) {
                        Text("Submitted application", color = PInk, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("Everything shared with Zomeal", color = PMuted, fontSize = 11.sp)
                    }
                    Text("zomeal", color = PBrand, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
        },
        bottomBar = {
            Surface(shadowElevation = 8.dp) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Button(
                        onClick = {
                            editing = true; editError = null
                            repository.resumeReturnedApplication { result ->
                                editing = false
                                if (result.success) onEdit() else editError = result.message
                            }
                        },
                        enabled = !editing,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(15.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PBrand)
                    ) {
                        Icon(Icons.Outlined.Edit, null); Spacer(Modifier.width(8.dp))
                        Text(if (editing) "Opening application…" else "Edit & update submitted data", fontWeight = FontWeight.Bold)
                    }
                    editError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp)) }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Section("Business information") {
                    SubmittedValue("Kitchen / provider", ProviderDraft.businessName)
                    SubmittedValue("Contact person", ProviderDraft.contactName)
                    SubmittedValue("Provider phone", "+91 ${ProviderDraft.ownerPhone}")
                    SubmittedValue("Food category", ProviderDraft.category)
                    SubmittedValue("Business address", listOf(ProviderDraft.address, ProviderDraft.city, ProviderDraft.state, ProviderDraft.pincode).filter { it.isNotBlank() }.joinToString(", "))
                }
            }
            item {
                Section("Serviceability & capacity") {
                    ProviderDraft.servicePincodes.filter { it.value.isNotBlank() }.forEach {
                        SubmittedValue("Pincode ${it.value}", it.areaName ?: if (it.verified) "Verified" else "Pending verification")
                    }
                    SubmittedValue("Delivery radius", "${ProviderDraft.radius} km")
                    SubmittedValue("Daily capacity", "Lunch ${ProviderDraft.lunchCapacity} · Dinner ${ProviderDraft.dinnerCapacity}")
                }
            }
            item {
                Section("Packages & submitted prices") {
                    if (ProviderDraft.lunchEnabled) SubmittedValue("Lunch only", "₹${ProviderDraft.lunchPrice} / 30 days")
                    if (ProviderDraft.dinnerEnabled) SubmittedValue("Dinner only", "₹${ProviderDraft.dinnerPrice} / 30 days")
                    if (ProviderDraft.bothEnabled) {
                        val total = ProviderDraft.bothPrice.toBigDecimalOrNull()
                        val lunch = ProviderDraft.bothLunchDailyPrice.toBigDecimalOrNull()
                        val dinner = if (total != null && lunch != null) total.subtract(lunch.multiply(java.math.BigDecimal(30))).divide(java.math.BigDecimal(30), 2, RoundingMode.HALF_UP) else null
                        SubmittedValue("Lunch + Dinner", "₹${ProviderDraft.bothPrice} / 30 days · Lunch ₹${ProviderDraft.bothLunchDailyPrice}/day · Dinner ₹${dinner ?: "—"}/day")
                    }
                    Text("Prices remain subject to Zomeal approval.", color = PMuted, fontSize = 11.sp)
                }
            }
            items(days.indices.toList()) { index ->
                val day = ProviderDraft.menus[index]
                Section(days[index]) {
                    if (offersLunch) SubmittedMealReview("Lunch", day.lunch, day.lunchFixed)
                    if (offersDinner) SubmittedMealReview("Dinner", day.dinner, day.dinnerFixed)
                }
            }
            item {
                Section("Provider photographs", "Photos remain private until Zomeal approves them.") {
                    SubmittedPhoto("Provider profile", ProviderDraft.profilePhoto)
                    SubmittedPhoto("Kitchen / hygiene", ProviderDraft.kitchenPhoto)
                    SubmittedPhoto("Complete meal / thali", ProviderDraft.mealPhoto)
                }
            }
            item {
                Section("Delivery contact") {
                    SubmittedValue("Delivery person", ProviderDraft.deliveryName.ifBlank { "Not provided" })
                    SubmittedValue("Phone number", "+91 ${ProviderDraft.deliveryPhone}")
                }
            }
            item { InfoCard("Use Edit & update to correct a pending application. Once the provider becomes active, future operational changes will use Zomeal's approval workflow.") }
        }
    }
}

@Composable
private fun SubmittedMealReview(slot: String, dishes: List<DishDraft>, fixed: String) {
    Text(slot, color = PBrand, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    dishes.filter { it.name.isNotBlank() }.forEach { dish ->
        SubmittedPhoto(dish.name, dish.photo, dish.description.ifBlank { null })
    }
    if (fixed.isNotBlank()) SubmittedValue("Included items", fixed)
    HorizontalDivider(color = Color(0xFFE5ECE7))
}

@Composable
private fun SubmittedValue(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = PMuted, fontSize = 11.sp)
        Text(value.ifBlank { "Not provided" }, color = PInk, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SubmittedPhoto(label: String, uriValue: String?, detail: String? = null) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val bitmap = remember(uriValue) {
        uriValue?.let { value -> runCatching {
            context.contentResolver.openInputStream(Uri.parse(value))?.use(BitmapFactory::decodeStream)?.asImageBitmap()
        }.getOrNull() }
    }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(PMist).padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
        if (bitmap != null) {
            Image(bitmap = bitmap, contentDescription = label, modifier = Modifier.size(72.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
        } else {
            Box(Modifier.size(72.dp).clip(RoundedCornerShape(10.dp)).background(Color.White), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.ImageNotSupported, null, tint = PMuted)
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = PInk, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            detail?.let { Text(it, color = PMuted, fontSize = 11.sp, lineHeight = 15.sp) }
            Text(if (bitmap != null) "Submitted photo" else "No accessible photo", color = if (bitmap != null) PBrand else PMuted, fontSize = 10.sp)
        }
    }
}

@Composable
private fun StatusLine(number: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(29.dp).clip(CircleShape).background(PMist), contentAlignment = Alignment.Center) { Text(number, color = PBrand, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.width(10.dp)); Text(text, color = PInk, fontSize = 13.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun InfoCard(text: String) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFFEAF5EC)).padding(13.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Outlined.Info, null, tint = PBrand, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(9.dp)); Text(text, color = Color(0xFF42604D), fontSize = 11.sp, lineHeight = 16.sp)
    }
}
