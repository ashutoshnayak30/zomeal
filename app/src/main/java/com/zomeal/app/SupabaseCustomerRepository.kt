package com.zomeal.app

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal data class MarketplaceProvider(
    val id:String,val name:String,val locality:String,val dietaryType:String,val description:String,
    val packages:List<MarketplacePackage>,val menu:JSONArray
)

internal data class MarketplacePackage(
    val id:String,val name:String,val kind:String,val pricePaise:Long
)

internal data class RazorpayCheckoutOrder(
    val paymentOrderId:String,val razorpayOrderId:String,val keyId:String,
    val amountPaise:Long,val currency:String,val receipt:String,val testMode:Boolean
)

internal class SupabaseCustomerRepository {
    private val main=Handler(Looper.getMainLooper())
    private val baseUrl=BuildConfig.SUPABASE_URL.trimEnd('/')
    private val anonKey=BuildConfig.SUPABASE_ANON_KEY
    val configured get()=baseUrl.startsWith("https://")&&anonKey.isNotBlank()

    fun marketplace(pincode:String,callback:(List<MarketplaceProvider>,String?)->Unit){
        if(!configured){callback(emptyList(),"Supabase is not configured");return}
        Thread{
            try{
                val connection=(URL("$baseUrl/rest/v1/rpc/customer_marketplace").openConnection() as HttpURLConnection).apply{
                    requestMethod="POST";connectTimeout=12000;readTimeout=18000;doOutput=true
                    setRequestProperty("apikey",anonKey);setRequestProperty("Authorization","Bearer $anonKey");setRequestProperty("Content-Type","application/json")
                }
                connection.outputStream.use{it.write(JSONObject().put("target_pincode",pincode).toString().toByteArray())}
                val stream=if(connection.responseCode in 200..299)connection.inputStream else connection.errorStream
                val text=stream?.bufferedReader()?.use{it.readText()}.orEmpty()
                if(connection.responseCode !in 200..299)throw IllegalStateException(runCatching{JSONObject(text).optString("message")}.getOrNull().orEmpty().ifBlank{"Marketplace request failed (${connection.responseCode})"})
                val array=JSONArray(text);val result=buildList{for(index in 0 until array.length()){val item=array.getJSONObject(index)
                    val packageJson=item.optJSONArray("packages")?:JSONArray()
                    val packages=buildList{for(packageIndex in 0 until packageJson.length()){
                        val value=packageJson.getJSONObject(packageIndex)
                        add(MarketplacePackage(value.getString("id"),value.optString("name"),value.optString("kind"),value.optLong("price_paise")))
                    }}
                    add(MarketplaceProvider(
                        item.getString("provider_id"),item.getString("display_name"),item.optString("locality",item.optString("city","Bhubaneswar")),
                        item.optString("dietary_type","BOTH"),item.optString("description"),packages,item.optJSONArray("weekly_menu")?:JSONArray()
                    ))}}
                main.post{callback(result,null)}
            }catch(error:Exception){main.post{callback(emptyList(),error.message?:"Could not load providers")}}
        }.start()
    }

    fun createRazorpayOrder(packageId:String,deliveryAddress:JSONObject,weeklyMenu:JSONObject,callback:(RazorpayCheckoutOrder?,String?)->Unit){
        functionRequest("create-razorpay-order",JSONObject().apply{
            put("package_id",packageId);put("delivery_address",deliveryAddress);put("weekly_menu",weeklyMenu)
        }){json,error->
            if(error!=null||json==null)callback(null,error?:"Could not create payment order")
            else callback(RazorpayCheckoutOrder(
                json.getString("payment_order_id"),json.getString("razorpay_order_id"),json.getString("key_id"),
                json.getLong("amount_paise"),json.optString("currency","INR"),json.getString("receipt"),json.optBoolean("test_mode",true)
            ),null)
        }
    }

    fun verifyRazorpayPayment(paymentOrderId:String,razorpayOrderId:String,paymentId:String,signature:String,callback:(JSONObject?,String?)->Unit){
        functionRequest("verify-razorpay-payment",JSONObject().apply{
            put("payment_order_id",paymentOrderId);put("razorpay_order_id",razorpayOrderId)
            put("razorpay_payment_id",paymentId);put("razorpay_signature",signature)
        },callback)
    }

    private fun functionRequest(name:String,body:JSONObject,callback:(JSONObject?,String?)->Unit){
        if(!configured){callback(null,"Supabase is not configured");return}
        Thread{
            try{
                val connection=(URL("$baseUrl/functions/v1/$name").openConnection() as HttpURLConnection).apply{
                    requestMethod="POST";connectTimeout=15000;readTimeout=30000;doOutput=true
                    setRequestProperty("apikey",anonKey);setRequestProperty("Authorization","Bearer $anonKey");setRequestProperty("Content-Type","application/json")
                }
                connection.outputStream.use{it.write(body.toString().toByteArray())}
                val stream=if(connection.responseCode in 200..299)connection.inputStream else connection.errorStream
                val text=stream?.bufferedReader()?.use{it.readText()}.orEmpty()
                val json=runCatching{JSONObject(text)}.getOrElse{JSONObject()}
                if(connection.responseCode !in 200..299)throw IllegalStateException(json.optString("error").ifBlank{"Payment request failed (${connection.responseCode})"})
                main.post{callback(json,null)}
            }catch(error:Exception){main.post{callback(null,error.message?:"Payment service is unavailable")}}
        }.start()
    }
}
