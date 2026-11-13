package com.zomeal.provider

import java.math.BigDecimal

/** Observed net stock reduction, NOT an attribution to recipes, waste or profit. */
internal fun bikiStockReduction(opening:BigDecimal?,purchases:BigDecimal?,closing:BigDecimal,confirmed:Boolean):BigDecimal? {
    require(closing>=BigDecimal.ZERO)
    require(opening==null||opening>=BigDecimal.ZERO)
    require(purchases==null||purchases>=BigDecimal.ZERO)
    if(!confirmed||opening==null||purchases==null)return null
    return opening+purchases-closing // Negative remains a discrepancy, never silently clamped.
}
