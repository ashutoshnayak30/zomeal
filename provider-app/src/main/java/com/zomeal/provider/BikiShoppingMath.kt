package com.zomeal.provider

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

internal data class ShoppingNeed(val item:String,val unit:String,val quantity:BigDecimal,val estimatedCost:BigDecimal?=null)
internal fun shoppingKey(item:String)=item.trim().lowercase(Locale.ROOT).replace(Regex("\\s+")," ")
internal fun shoppingAggregate(rows:List<ShoppingNeed>):List<ShoppingNeed> {
    return rows.groupBy{shoppingKey(it.item) to recipeFamily(it.unit)}.map{(_,group)->
        require(group.none{recipeFamily(it.unit)=="unknown" || it.quantity<BigDecimal.ZERO})
        val unit=when(recipeFamily(group.first().unit)){"mass"->"g";"volume"->"ml";else->"piece"}
        require(group.none{it.estimatedCost!=null && it.estimatedCost<BigDecimal.ZERO})
        ShoppingNeed(group.first().item,unit,group.fold(BigDecimal.ZERO){sum,r->sum+r.quantity*recipeBase(r.unit)},
            if(group.any{it.estimatedCost==null}) null else group.fold(BigDecimal.ZERO){sum,r->sum+r.estimatedCost!!})
    }.sortedBy{shoppingKey(it.item)}
}
/** Recipe-weighted price estimate, not a supplier quote or a ledger posting. */
internal fun shoppingPurchaseEstimate(need:ShoppingNeed,shortfall:BigDecimal?):BigDecimal? {
    if(shortfall==null)return null
    require(shortfall>=BigDecimal.ZERO)
    if(shortfall.signum()==0)return BigDecimal.ZERO
    if(need.estimatedCost==null||need.quantity.signum()<=0)return null
    return need.estimatedCost.multiply(shortfall).divide(need.quantity,12,RoundingMode.HALF_UP)
}
internal fun shoppingShortfall(required:BigDecimal,available:BigDecimal?,unit:String):BigDecimal? {
    if(available==null)return null
    require(required>=BigDecimal.ZERO && available>=BigDecimal.ZERO)
    val gap=(required-available).max(BigDecimal.ZERO)
    return if(unit=="piece") gap.setScale(0,RoundingMode.CEILING) else gap
}
