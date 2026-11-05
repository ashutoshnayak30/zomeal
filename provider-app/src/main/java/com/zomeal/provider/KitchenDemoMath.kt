package com.zomeal.provider

import java.math.BigDecimal
import java.math.RoundingMode

internal data class KitchenDemoCost(val name:String,val paise:Long)
internal object KitchenDemoMath {
 const val revenue=1200000L
 const val cooking=816000L
 const val days=6
 const val monthDays=31
 fun costs(day:Int):List<KitchenDemoCost>{
  require(day in 1..6)
  val names=listOf("Rice","Atta / roti","Dal","Vegetables","Meat, eggs & fish","Oil","Masalas","Milk & curd","Cooking gas")
  val weights=listOf(20,10,12,15,20,8,6,4,5).mapIndexed{i,v->v+(day*7+i*3)%5}
  val total=weights.sum()
  val amounts=weights.map{cooking*it/total}.toMutableList()
  amounts[amounts.lastIndex]+=cooking-amounts.sum()
  return names.mapIndexed{i,n->KitchenDemoCost(n,amounts[i])}
 }
 fun commission(rate:String):Long? = rate.toBigDecimalOrNull()?.takeIf{it>=BigDecimal.ZERO&&it<=BigDecimal(100)}
  ?.let{BigDecimal(revenue).multiply(it).divide(BigDecimal(100),0,RoundingMode.HALF_UP).toLong()}
 fun allocation(monthly:Long,days:Int):Long=BigDecimal(monthly).multiply(BigDecimal(days)).divide(BigDecimal(monthDays),0,RoundingMode.HALF_UP).toLong()
 fun profit(monthly:Long,commission:Long,days:Int):Long=(revenue-cooking-commission)*days-allocation(monthly,days)
}
