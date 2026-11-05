package com.zomeal.provider

import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.TimeZone
import java.util.Locale

/** Shared compact type scale for operational screens, not a global font enlargement. */
internal object ProviderType {
    val caption = 12.sp
    val body = 13.sp
    val section = 16.sp
}

internal fun automaticMealSlot(hour: Int, minute: Int): String =
    if (hour * 60 + minute in 150 until 870) "LUNCH" else "DINNER"

internal fun currentAutomaticMealSlot(): String {
    val now = java.util.Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"))
    return automaticMealSlot(now.get(java.util.Calendar.HOUR_OF_DAY), now.get(java.util.Calendar.MINUTE))
}

internal fun isPreparationMeal(row: JSONObject): Boolean = row.optString("status") !in setOf("PAUSED", "CANCELLED")

/** A slower response must never replace the currently selected meal/date. */
internal class OperationsRequestGate {
    private var generation = 0
    fun next(): Int = ++generation
    fun accepts(request: Int): Boolean = request == generation
    fun invalidate() { generation++ }
}

internal fun operationsCutoff(data: JSONObject?): String = runCatching {
    val timestamp = data?.optString("cutoff_at").orEmpty().replace(Regex("\\.\\d+(?=[Z+-])"), "")
    val parsed = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply { isLenient = false }.parse(timestamp)
        ?: error("Missing cutoff")
    SimpleDateFormat("h:mm a 'IST'", Locale.ENGLISH).apply {
        timeZone = TimeZone.getTimeZone("Asia/Kolkata")
    }.format(parsed)
}.getOrDefault("the configured meal cutoff")
