package com.zomeal.provider

import java.math.BigDecimal
import java.math.RoundingMode

internal fun kitchenPaise(value: String): Long? = runCatching {
    if (!Regex("[0-9]+(\\.[0-9]{1,2})?").matches(value.trim())) return null
    BigDecimal(value.trim()).movePointRight(2).longValueExact().takeIf { it in 0..100000000 }
}.getOrNull()

internal fun kitchenBreakEven(fixed: Long, netPerMeal: Long, variablePerMeal: Long): Long? {
    val contribution = netPerMeal - variablePerMeal
    if (fixed < 0 || variablePerMeal < 0 || contribution <= 0) return null
    return BigDecimal(fixed).divide(BigDecimal(contribution),0,RoundingMode.CEILING).longValueExact()
}

internal fun kitchenCsvCell(value: String): String {
    val safe = if (value.trimStart().firstOrNull() in listOf('=', '+', '-', '@') || value.startsWith('\t') || value.startsWith('\r')) "'$value" else value
    return "\"${safe.replace("\"", "\"\"")}\""
}

internal fun kitchenStockValue(quantity: String, unitPrice: String): Long? = runCatching {
    if (!Regex("[0-9]+(\\.[0-9]{1,3})?").matches(quantity.trim())) return null
    val q = BigDecimal(quantity.trim())
    val price = kitchenPaise(unitPrice) ?: return null
    if (q.signum() <= 0 || price <= 0) return null
    q.multiply(BigDecimal(price)).setScale(0, RoundingMode.HALF_UP).longValueExact().takeIf { it in 1..100000000 }
}.getOrNull()
