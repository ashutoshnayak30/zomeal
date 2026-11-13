package com.zomeal.provider
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
class StockCarryMathTest {
 private fun n(item:String,q:String,unit:String,cost:String?=null)=ShoppingNeed(item,unit,BigDecimal(q),cost?.let{BigDecimal(it)})
 @Test fun combinesCompatibleUnitsAndCosts(){val r=stockCarryForward(listOf(n("Rice","1","kg","40")),listOf(n(" rice ","500","g","25"))).single();assertEquals(0,r.quantity.compareTo(BigDecimal("1500")));assertEquals(0,r.estimatedCost!!.compareTo(BigDecimal("65")))}
 @Test fun unknownPriceRemainsUnknown(){assertNull(stockCarryForward(listOf(n("Rice","1","kg")),listOf(n("Rice","1","kg","40"))).single().estimatedCost)}
 @Test fun missingBaselineDoesNotBecomeZero(){assertTrue(stockCarryForward(emptyList(),listOf(n("Rice","1","kg"))).isEmpty())}
 @Test fun incompatibleUnitsNotAdded(){assertEquals(0,stockCarryForward(listOf(n("Oil","1","litre")),listOf(n("Oil","1","kg"))).single().quantity.compareTo(BigDecimal("1000")))}
 @Test fun noAliasGuess(){assertEquals(0,stockCarryForward(listOf(n("Rice","1","kg")),listOf(n("ଚାଉଳ","2","kg"))).single().quantity.compareTo(BigDecimal("1000")))}
 @Test fun zeroCountCanReceivePurchase(){assertEquals(0,stockCarryForward(listOf(n("Egg","0","piece","0")),listOf(n("Egg","30","piece","150"))).single().quantity.compareTo(BigDecimal("30")))}
 @Test fun unselectedPurchaseNotAdded(){assertEquals(0,stockCarryForward(listOf(n("Rice","1","kg")),emptyList()).single().quantity.compareTo(BigDecimal("1000")))}
}
