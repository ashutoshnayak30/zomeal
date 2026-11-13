package com.zomeal.provider

import java.math.BigDecimal

/** Only observed items have a known baseline. Caller explicitly selects later receipts. */
internal fun stockCarryForward(counts:List<ShoppingNeed>,receipts:List<ShoppingNeed>):List<ShoppingNeed> {
    val baseline=shoppingAggregate(counts)
    val additions=shoppingAggregate(receipts)
    return baseline.map { count ->
        val bought=additions.singleOrNull{shoppingKey(it.item)==shoppingKey(count.item)&&it.unit==count.unit}
        if(bought==null)count else count.copy(quantity=count.quantity+bought.quantity,
            estimatedCost=if(count.estimatedCost==null||bought.estimatedCost==null)null else count.estimatedCost+bought.estimatedCost)
    }
}
