package com.zomeal.provider

internal fun maskedCustomerPhone(value: String): String {
    if (Regex("^[0-9]{2}x{6}[0-9]{2}$").matches(value)) return value
    val digits = value.filter(Char::isDigit).takeLast(10)
    return if (digits.length == 10) digits.take(2) + "xxxxxx" + digits.takeLast(2) else "Phone hidden"
}

// Stored as part of the included-item text, so existing draft submission,
// approval and approved-menu restoration all preserve the serving instructions.
private const val PortionMarker = " · Portions: "
internal fun portionValue(fixed: String, item: String): String =
    fixed.substringAfter(PortionMarker, "").split("; ").firstOrNull { it.startsWith("$item: ") }
        ?.substringAfter(": ").orEmpty()

internal fun withPortion(fixed: String, item: String, value: String): String {
    val entries = sideDishNames(fixed).mapNotNull { label ->
        val amount = if (label == item) value.trim() else portionValue(fixed, label)
        amount.takeIf { it.isNotBlank() }?.let { "$label: $it" }
    }
    return fixed.substringBefore(PortionMarker) + if (entries.isEmpty()) "" else PortionMarker + entries.joinToString("; ")
}

internal fun sideDishNames(fixed: String): List<String> = includedItems(fixed).split(',')
    .map { it.trim() }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }

internal fun withIncludedItems(fixed: String, value: String): String {
    val entries = sideDishNames(value).mapNotNull { name ->
        val oldName = sideDishNames(fixed).firstOrNull { it.equals(name, ignoreCase = true) } ?: name
        portionValue(fixed, oldName).takeIf { it.isNotBlank() }?.let { "$name: $it" }
    }
    return value + if (entries.isEmpty()) "" else PortionMarker + entries.joinToString("; ")
}

internal fun includedItems(fixed: String): String = fixed.substringBefore(PortionMarker)
