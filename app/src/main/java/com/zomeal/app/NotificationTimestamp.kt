package com.zomeal.app

import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun formatNotificationTimestamp(value: String): String = runCatching {
    OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.of("Asia/Kolkata"))
        .format(DateTimeFormatter.ofPattern("d MMM yyyy · h:mm a", Locale.ENGLISH)) + " IST"
}.getOrDefault("Time unavailable")
