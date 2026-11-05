package com.zomeal.provider

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class RecipeMathTest {
    private fun equal(expected:String, actual:BigDecimal?)=assertEquals(0,BigDecimal(expected).compareTo(actual!!))
    @Test fun onionKgPrice(){equal("5",recipeBatchCost("250","g","20","kg"))}
    @Test fun batchTotal(){equal("5",recipeBatchCost("250","g","5","batch"))}
    @Test fun hundredPeople(){
        equal("11.111111111111",recipeScale("1",9,100))
        val cost=recipeBatchCost("250","g","20","kg")!!.multiply(BigDecimal(100)).divide(BigDecimal(9),2,java.math.RoundingMode.HALF_UP)
        equal("55.56",cost)
    }
    @Test fun oilMlPrice(){equal("30",recipeBatchCost("250","ml","120","litre"))}
    @Test fun eggs(){equal("90",recipeBatchCost("18","piece","5","piece"))}
    @Test fun packet(){equal("5",recipeBatchCost("50","g","10","packet","100","g"))}
    @Test fun unknownPrice(){assertNull(recipeBatchCost("250","g","","kg"))}
    @Test(expected=IllegalArgumentException::class) fun noDensityGuess(){recipeBatchCost("250","g","120","litre")}
    @Test(expected=IllegalArgumentException::class) fun zeroPacket(){recipeBatchCost("250","g","20","packet","0","g")}
    @Test fun readableUnits(){assertEquals("2.778 kg",recipeQuantity(BigDecimal("2777.777778"),"g"))}
}
