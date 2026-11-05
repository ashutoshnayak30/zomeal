package com.zomeal.provider

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

internal data class ShoppingNeed(val item:String,val unit:String,val quantity:BigDecimal)
internal fun shoppingKey(item:String)=item.trim().lowercase(Locale.ROOT).replace(Regex("\\s+")," ")
internal fun shoppingAggregate(rows:List<ShoppingNeed>):List<ShoppingNeed> {
    return rows.groupBy{shoppingKey(it.item) to recipeFamily(it.unit)}.map{(_,group)->
        require(group.none{recipeFamily(it.unit)=="unknown" || it.quantity<BigDecimal.ZERO})
        val unit=when(recipeFamily(group.first().unit)){"mass"->"g";"volume"->"ml";else->"piece"}
        ShoppingNeed(group.first().item,unit,group.fold(BigDecimal.ZERO){sum,r->sum+r.quantity*recipeBase(r.unit)})
    }.sortedBy{shoppingKey(it.item)}
}
internal fun shoppingShortfall(required:BigDecimal,available:BigDecimal?,unit:String):BigDecimal? {
    if(available==null)return null
    require(required>=BigDecimal.ZERO && available>=BigDecimal.ZERO)
    val gap=(required-available).max(BigDecimal.ZERO)
    return if(unit=="piece") gap.setScale(0,RoundingMode.CEILING) else gap
}
