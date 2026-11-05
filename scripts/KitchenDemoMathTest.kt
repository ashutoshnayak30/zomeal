package com.zomeal.provider
fun main(){
 check(KitchenDemoMath.revenue*6==7200000L)
 check(KitchenDemoMath.cooking*6==4896000L)
 (1..6).forEach{check(KitchenDemoMath.costs(it).size==9);check(KitchenDemoMath.costs(it).sumOf{r->r.paise}==816000L)}
 check(KitchenDemoMath.costs(1)!=KitchenDemoMath.costs(2))
 check(KitchenDemoMath.profit(0,0,31)==11904000L)
 check(KitchenDemoMath.commission("9")==108000L)
 check(KitchenDemoMath.profit(3100000,108000,31)==5456000L)
 check(KitchenDemoMath.profit(3100000,108000,6)==1056000L)
 check(KitchenDemoMath.profit(20000000,108000,31)<0)
 check(KitchenDemoMath.commission("")==null)
 check(KitchenDemoMath.commission("-1")==null)
 check(KitchenDemoMath.commission("101")==null)
 println("PASS: six-day revenue, 68% costs, varied balanced rows, commission, overhead, monthly projection and loss")
}
