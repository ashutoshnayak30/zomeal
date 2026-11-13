package com.zomeal.provider
import org.junit.Test
import org.junit.Assert.*
import java.math.BigDecimal
class StockPriceMathTest {
 private val rice=OpeningStockPrice("Rice",BigDecimal("5"),"kg",BigDecimal("250"))
 @Test fun scalesOpeningRate(){assertEquals(BigDecimal("100.00"),closingStockValue("rice",BigDecimal("2"),"kg",listOf(rice)))}
 @Test fun convertsGrams(){assertEquals(BigDecimal("12.50"),closingStockValue(" Rice ",BigDecimal("250"),"g",listOf(rice)))}
 @Test fun onionExample(){assertEquals(BigDecimal("20.00"),closingStockValue("onion",BigDecimal.ONE,"kg",listOf(OpeningStockPrice("onion",BigDecimal("250"),"g",BigDecimal("5")))))}
 @Test fun volume(){assertEquals(BigDecimal("30.00"),closingStockValue("oil",BigDecimal("250"),"ml",listOf(OpeningStockPrice("oil",BigDecimal.ONE,"litre",BigDecimal("120")))))}
 @Test fun noDensityGuess(){assertNull(closingStockValue("Rice",BigDecimal.ONE,"litre",listOf(rice)))}
 @Test fun missingIsUnknown(){assertNull(closingStockValue("onion",BigDecimal.ONE,"kg",listOf(rice)))}
 @Test fun duplicateIsAmbiguous(){assertNull(closingStockValue("Rice",BigDecimal.ONE,"kg",listOf(rice,rice)))}
 @Test fun zeroRemainingNeedsNoPrice(){assertEquals(BigDecimal("0.00"),closingStockValue("onion",BigDecimal.ZERO,"kg",emptyList()))}
 @Test fun noZeroQuantityDivision(){assertNull(closingStockValue("Rice",BigDecimal.ONE,"kg",listOf(rice.copy(quantity=BigDecimal.ZERO))))}
 @Test fun pieces(){assertEquals(BigDecimal("10.00"),closingStockValue("egg",BigDecimal("2"),"piece",listOf(OpeningStockPrice("egg",BigDecimal("30"),"piece",BigDecimal("150")))))}
 @Test fun negativeRejected(){assertNull(closingStockValue("Rice",BigDecimal("-1"),"kg",listOf(rice)))}
}
