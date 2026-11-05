package com.zomeal.provider

fun main() {
    check(sideDishNames("Rice, dal, Salad, rice, ") == listOf("Rice", "dal", "Salad"))
    var lunch = withPortion("Rice, dal, Salad", "Rice", "3 scoops")
    lunch = withPortion(lunch, "dal", "1 bowl")
    check(portionValue(lunch, "Rice") == "3 scoops")
    check(portionValue(lunch, "dal") == "1 bowl")
    lunch = withIncludedItems(lunch, "Pulao, dal")
    check(portionValue(lunch, "Rice").isEmpty())
    check(portionValue(lunch, "dal") == "1 bowl")
    lunch = withPortion(lunch, "Pulao", "4 scoops")
    check(portionValue(lunch, "Pulao") == "4 scoops")
    check(withPortion(lunch, "Pulao", "").contains("dal: 1 bowl"))
    val dinner = withPortion("Roti, Puri", "Roti", "4 pieces")
    check(portionValue(dinner, "Roti") == "4 pieces")
    check(portionValue(lunch, "Roti").isEmpty())
    check(withIncludedItems(lunch, "").isEmpty())
    check(withIncludedItems("Rice · Portions: Rice: 3 scoops", "rice").endsWith("rice: 3 scoops"))
    println("PASS: dynamic portions, removed sides, case changes, legacy values and separate meals")
}
