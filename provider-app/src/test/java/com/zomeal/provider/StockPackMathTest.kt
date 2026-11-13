package com.zomeal.provider
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class StockPackMathTest {
 @Test fun convertsOnlyOwnerConfirmedContents(){assertEquals(0,stockPackQuantity(BigDecimal("3"),BigDecimal("500"))!!.compareTo(BigDecimal("1500")))}
 @Test fun supportsFractionalRemainingPack(){assertEquals(0,stockPackQuantity(BigDecimal("0.5"),BigDecimal("500"))!!.compareTo(BigDecimal("250")))}
 @Test fun rejectsInvalidOrOverPreciseCounts(){for((q,s) in listOf("-1" to "500","1" to "0","100000" to "1000","0.001" to "0.001"))assertNull(stockPackQuantity(BigDecimal(q),BigDecimal(s)))}
 @Test fun zeroRemainingIsExplicitlyZero(){assertEquals(0,stockPackQuantity(BigDecimal.ZERO,BigDecimal("500"))!!.signum())}
}
