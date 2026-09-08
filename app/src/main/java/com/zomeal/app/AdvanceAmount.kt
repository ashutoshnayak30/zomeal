package com.zomeal.app

internal fun advanceAmountPaise(text: String): Long? {
    if (!Regex("[0-9]{1,5}(\\.[0-9]{1,2})?").matches(text)) return null
    return runCatching { text.toBigDecimal().movePointRight(2).longValueExact() }.getOrNull()
}

internal fun paiseText(amount: Long): String = java.math.BigDecimal.valueOf(amount, 2).toPlainString()

// Temporary controlled-testing minimum. Restore to 50000L (₹500) before release.
private const val INITIAL_PLAN_TEST_MINIMUM_PAISE = 500L

internal fun validAdvance(amount: Long?, planTotal: Long): Boolean =
    amount != null && amount in INITIAL_PLAN_TEST_MINIMUM_PAISE..minOf(1000000L, planTotal)
