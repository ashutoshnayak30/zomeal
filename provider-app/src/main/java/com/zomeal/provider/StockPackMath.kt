package com.zomeal.provider
import java.math.BigDecimal

internal fun stockPackQuantity(packs:BigDecimal,size:BigDecimal):BigDecimal? {
 if(packs.signum()<0||size.signum()<=0)return null
 val result=packs.multiply(size).stripTrailingZeros()
 return result.takeIf{it<=BigDecimal(100000)&&it.scale()<=3}
}
