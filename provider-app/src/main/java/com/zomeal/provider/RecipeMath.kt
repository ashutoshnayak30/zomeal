package com.zomeal.provider

import java.math.BigDecimal
import java.math.RoundingMode

internal fun recipeScale(q: String, batch: Int, target: Int): BigDecimal {
    require(batch in 1..10000 && target in 1..10000)
    return q.toBigDecimal().multiply(target.toBigDecimal()).divide(batch.toBigDecimal(), 12, RoundingMode.HALF_UP)
}
internal fun recipeBase(unit: String): BigDecimal = when(unit) {
    "kg", "litre" -> BigDecimal("1000")
    "g", "ml", "piece" -> BigDecimal.ONE
    else -> error("Choose a supported unit")
}
internal fun recipeFamily(unit: String) = when(unit) {
    "kg", "g" -> "mass"
    "litre", "ml" -> "volume"
    "piece" -> "count"
    else -> "unknown"
}
/** Price for the batch, independent of the ingredient's measurement unit. */
internal fun recipeBatchCost(quantity: String, unit: String, price: String, basis: String, packQuantity: String = "", packUnit: String = ""): BigDecimal? {
    if(price.isBlank()) return null
    val q=quantity.toBigDecimal(); val p=price.toBigDecimal()
    require(q>BigDecimal.ZERO && p>=BigDecimal.ZERO)
    if(basis=="batch") return p
    val denominator = if(basis=="packet") {
        require(recipeFamily(unit)==recipeFamily(packUnit) && recipeFamily(unit)!="unknown")
        packQuantity.toBigDecimal().also { require(it>BigDecimal.ZERO) }.multiply(recipeBase(packUnit))
    } else {
        require(recipeFamily(unit)==recipeFamily(basis) && recipeFamily(unit)!="unknown")
        recipeBase(basis)
    }
    return q.multiply(recipeBase(unit)).multiply(p).divide(denominator,12,RoundingMode.HALF_UP)
}
internal fun recipeQuantity(q: BigDecimal, unit: String): String {
    val promote=(unit=="g" || unit=="ml") && q>=BigDecimal("1000")
    val amount=if(promote) q.divide(BigDecimal("1000")) else q
    val label=if(promote) (if(unit=="g") "kg" else "litre") else unit
    return "${amount.setScale(3,RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()} $label"
}
