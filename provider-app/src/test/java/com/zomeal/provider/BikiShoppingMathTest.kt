package com.zomeal.provider
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
class BikiShoppingMathTest {
 @Test fun combinesAcrossRecipesAndUnits(){val r=shoppingAggregate(listOf(ShoppingNeed(" Onion ","kg",BigDecimal("1")),ShoppingNeed("onion","g",BigDecimal("250"))));assertEquals(1,r.size);assertEquals(0,r[0].quantity.compareTo(BigDecimal("1250")))}
 @Test fun noDensityGuess(){assertEquals(2,shoppingAggregate(listOf(ShoppingNeed("Oil","g",BigDecimal.ONE),ShoppingNeed("Oil","ml",BigDecimal.ONE))).size)}
 @Test fun unknownNotZero(){assertNull(shoppingShortfall(BigDecimal.TEN,null,"g"))}
 @Test fun enoughStock(){assertEquals(0,shoppingShortfall(BigDecimal.TEN,BigDecimal("20"),"g")!!.signum())}
 @Test fun deficit(){assertEquals(0,shoppingShortfall(BigDecimal("1250"),BigDecimal("500"),"g")!!.compareTo(BigDecimal("750")))}
 @Test fun wholeEggs(){assertEquals(BigDecimal("3"),shoppingShortfall(BigDecimal("5.5"),BigDecimal("3"),"piece"))}
 @Test fun explicitZero(){assertEquals(BigDecimal.TEN,shoppingShortfall(BigDecimal.TEN,BigDecimal.ZERO,"g"))}
 @Test fun noTranslationGuess(){assertEquals(2,shoppingAggregate(listOf(ShoppingNeed("Rice","g",BigDecimal.ONE),ShoppingNeed("ଚାଉଳ","g",BigDecimal.ONE))).size)}
 @Test(expected=IllegalArgumentException::class) fun rejectNegative(){shoppingShortfall(BigDecimal.ONE,BigDecimal("-1"),"g")}
}
