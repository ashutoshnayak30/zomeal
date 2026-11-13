package com.zomeal.provider

import java.math.BigDecimal
import java.math.RoundingMode

internal data class OpeningStockPrice(val item:String,val quantity:BigDecimal,val unit:String,val total:BigDecimal)
/** Same item + compatible physical units only; never infer density or translated aliases. */
internal fun closingStockValue(item:String,quantity:BigDecimal,unit:String,opening:List<OpeningStockPrice>):BigDecimal? {
    if(quantity<BigDecimal.ZERO||recipeFamily(unit)=="unknown")return null
    if(quantity.signum()==0)return BigDecimal.ZERO.setScale(2)
    val matches=opening.filter{shoppingKey(it.item)==shoppingKey(item)}
    val source=matches.singleOrNull()?:return null
    if(source.quantity<=BigDecimal.ZERO||source.total<BigDecimal.ZERO||recipeFamily(source.unit)!=recipeFamily(unit))return null
    return source.total.multiply(quantity).multiply(recipeBase(unit))
        .divide(source.quantity.multiply(recipeBase(source.unit)),2,RoundingMode.HALF_UP)
}
