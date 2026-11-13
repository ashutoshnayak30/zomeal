package com.zomeal.provider

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

/** Review-only bridge, never a financial or physical-stock write. */
@Composable internal fun BikiStockReference(repository:SupabaseProviderRepository,closingOnly:Boolean=false,onUse:(JSONArray)->Unit){
 var data by remember{mutableStateOf<JSONObject?>(null)}
 var error by remember{mutableStateOf<String?>(null)}
 var loading by remember{mutableStateOf(false)}
 val selected=remember{mutableStateMapOf<String,Boolean>()}
 val alive=remember{java.util.concurrent.atomic.AtomicBoolean(true)}
 DisposableEffect(Unit){onDispose{alive.set(false)}}
 fun load(){loading=true;data=null;error=null;selected.clear();repository.stockReference{d,e->if(alive.get()){data=d;error=e;loading=false}}}
 fun rows(a:JSONArray)= (0 until a.length()).map{a.getJSONObject(it)}
 fun need(r:JSONObject):ShoppingNeed? {
  val q=r.optString("quantity").toBigDecimalOrNull()?:return null
  if(q.signum()<0||recipeFamily(r.optString("unit"))=="unknown")return null
  return ShoppingNeed(r.optString("item"),r.optString("unit"),q,r.optString("amount").toBigDecimalOrNull())
 }
 OutlinedButton(onClick={load()},enabled=!loading,modifier=Modifier.fillMaxWidth()){Text(if(loading)"Loading stock reference…" else "Use saved stock + later purchases")}
 error?.let{Text("Stock reference unavailable. Refresh after the backend update, or enter a physical count manually.",color=MaterialTheme.colorScheme.error)}
 data?.let { d ->
  val counts=rows(d.optJSONArray("rows")?:JSONArray()).mapNotNull{need(it)}
  if(counts.isEmpty()||(closingOnly&&d.optString("kind")!="CLOSE"))Text("No confirmed closing stock to carry forward. Enter a physical opening count.")
  else {
   Text("${if(d.optString("kind")=="CLOSE")"Closing" else "Opening"} count: ${d.optString("counted_at")}")
   Text("Select only purchases received AFTER this count and not already included. Dates alone cannot tell BIKI the purchase time.",style=MaterialTheme.typography.bodySmall)
   val purchases=rows(d.optJSONArray("purchases")?:JSONArray())
   purchases.forEach { r ->
    val n=need(r)
    val matches=n!=null&&counts.any{shoppingKey(it.item)==shoppingKey(n.item)&&recipeFamily(it.unit)==recipeFamily(n.unit)}
    Row { Checkbox(selected[r.optString("id")]==true,{selected[r.optString("id")]=it},enabled=matches)
     Text("${r.optString("entry_date")} · ${r.optString("item")} · ${r.optString("quantity")} ${r.optString("unit")}"+if(matches)"" else " · Count manually (unknown baseline or unit)",Modifier.weight(1f)) }
   }
   val result=stockCarryForward(counts,purchases.filter{selected[it.optString("id")]==true}.mapNotNull{need(it)})
   result.forEach{Text("${it.item}: ${recipeQuantity(it.quantity,it.unit)}")}
   Text("Estimate before consumption, waste or transfers. Omitted items stay unknown. Check quantities physically; prices are carried estimates, not new expenses.",style=MaterialTheme.typography.bodySmall)
   Button(onClick={onUse(JSONArray().apply{result.forEach { n ->
    val divisor=if(n.unit=="g"||n.unit=="ml")java.math.BigDecimal(1000) else java.math.BigDecimal.ONE
    put(JSONObject().put("item",n.item).put("unit",when(n.unit){"g"->"kg";"ml"->"litre";else->n.unit})
     .put("quantity",n.quantity.divide(divisor).stripTrailingZeros().toPlainString())
     .put("amount",n.estimatedCost?.setScale(2,java.math.RoundingMode.HALF_UP)?.toPlainString()?:""))
   }})},modifier=Modifier.fillMaxWidth()){Text("Copy estimate to editable draft")}
  }
 }
}
