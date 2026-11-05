package com.zomeal.provider

import org.json.JSONObject
import org.json.JSONArray

/** In-memory preview only. Never writes orders, ledger, costs or payouts. */
internal fun earningsDemoBook(day:Int,rate:Double,monthly:Map<String,Long>):JSONObject {
    val elapsed=day.coerceIn(1,31)
    val gross=KitchenDemoMath.revenue*elapsed
    val cooking=KitchenDemoMath.cooking*elapsed
    val commission=if(rate.isFinite()) (KitchenDemoMath.commission(rate.toString()) ?: 0L)*elapsed else 0L
    val overhead=monthly.values.sum()
    val allocated=KitchenDemoMath.allocation(overhead,elapsed)
    val gas=(1..elapsed).sumOf { KitchenDemoMath.costs((it-1)%6+1).last().paise }
    val categories=JSONObject().put("Staff",KitchenDemoMath.allocation(monthly["salary"] ?: 0,elapsed))
        .put("Rent",KitchenDemoMath.allocation(monthly["rent"] ?: 0,elapsed))
        .put("Utilities",KitchenDemoMath.allocation(monthly["electricity"] ?: 0,elapsed)).put("Gas & fuel",gas)
    val s=JSONObject().put("gross_paise",gross).put("net_paise",gross-commission).put("commission_paise",commission)
        .put("adjustments_paise",0).put("costs_to_date_paise",cooking+allocated).put("profit_to_date_paise",gross-commission-cooking-allocated)
        .put("food_used_paise",cooking-gas).put("stock_valid",true).put("categories",categories)
        .put("elapsed_days",elapsed).put("days_in_month",31).put("allocated_fixed_paise",allocated).put("monthly_overhead_paise",overhead)
    return JSONObject().put("statement",s).put("trend",JSONArray()).put("entries",JSONArray())
}
