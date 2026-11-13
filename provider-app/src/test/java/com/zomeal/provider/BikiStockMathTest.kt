package com.zomeal.provider
import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Test

class BikiStockMathTest {
 private fun n(v:String)=BigDecimal(v)
 @Test fun reductionIncludesPurchases(){assertEquals(n("7"),bikiStockReduction(n("10"),n("5"),n("8"),true))}
 @Test fun firstCountIsNotConsumption(){assertNull(bikiStockReduction(null,n("5"),n("8"),true))}
 @Test fun unconfirmedPurchasesAreUnknown(){assertNull(bikiStockReduction(n("10"),n("5"),n("8"),false))}
 @Test fun missingPurchasesAreNotZero(){assertNull(bikiStockReduction(n("10"),null,n("8"),true))}
 @Test fun confirmedZeroPurchases(){assertEquals(n("2"),bikiStockReduction(n("10"),BigDecimal.ZERO,n("8"),true))}
 @Test fun zeroClosingIsValid(){assertEquals(n("15"),bikiStockReduction(n("10"),n("5"),BigDecimal.ZERO,true))}
 @Test fun discrepancyIsNotClamped(){assertEquals(n("-3"),bikiStockReduction(n("10"),n("5"),n("18"),true))}
 @Test fun fractionalAmountsStayExact(){assertEquals(n("0.125"),bikiStockReduction(n("0.500"),n("0.250"),n("0.625"),true))}
 @Test(expected=IllegalArgumentException::class) fun negativePurchasesRejected(){bikiStockReduction(n("10"),n("-1"),n("8"),true)}
 @Test(expected=IllegalArgumentException::class) fun negativeCountRejected(){bikiStockReduction(n("10"),n("5"),n("-1"),true)}
}
