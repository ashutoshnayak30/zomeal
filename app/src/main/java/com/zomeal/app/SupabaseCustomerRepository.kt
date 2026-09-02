package com.zomeal.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

internal data class MarketplaceProvider(
    val id:String,val name:String,val locality:String,val dietaryType:String,val description:String,
    val packages:List<MarketplacePackage>,val menu:JSONArray,val primaryPhotoPath:String,
    val kitchenPhotoPath:String,val mealPhotoPath:String
)

internal data class PendingCheckout(val providerId:String,val packageId:String,val payload:JSONObject,val updatedAt:String)

internal data class MarketplacePackage(
    val id:String,val name:String,val kind:String,val pricePaise:Long
)

internal data class RazorpayCheckoutOrder(
    val paymentOrderId:String,val razorpayOrderId:String,val keyId:String,
    val amountPaise:Long,val currency:String,val receipt:String,val testMode:Boolean
)

internal data class PersistedDailyMeal(
    val id:String,val serviceDate:String,val mealSlot:String,val status:String,
    val itemId:String,val itemName:String,val dietaryType:String,val description:String
)

internal data class PersistedSubscription(
    val id:String,val status:String,val providerId:String,val providerName:String,
    val packageId:String,val packageName:String,val packageKind:String,
    val startDate:String,val endDate:String,val paymentReference:String,
    val address:JSONObject?,val weeklyMenu:JSONArray,val dailyMeals:List<PersistedDailyMeal>,
    val payment:JSONObject?
)

internal data class CustomerAuthResult(val success:Boolean,val message:String?=null)

internal class SupabaseCustomerRepository(context:Context) {
    companion object { @Volatile private var customerAccessToken:String="" }
    private val prefs=context.applicationContext.getSharedPreferences("zomeal_customer_session",Context.MODE_PRIVATE)
    private val main=Handler(Looper.getMainLooper())
    private val baseUrl=BuildConfig.SUPABASE_URL.trimEnd('/')
    private val anonKey=BuildConfig.SUPABASE_ANON_KEY
    val configured get()=baseUrl.startsWith("https://")&&anonKey.isNotBlank()
    val isAuthenticated:Boolean get()=!prefs.getBoolean("signed_out",false)&&!prefs.getString("access_token",null).isNullOrBlank()
    val savedPincode:String get()=prefs.getString("pincode","751030").orEmpty().ifBlank{"751030"}
    /** Only customer JWTs are stored here. Service-role credentials must never enter the app. */
    private fun saveSession(json:JSONObject){
        customerAccessToken=json.optString("access_token").trim()
        prefs.edit().putString("access_token",customerAccessToken)
            .putString("refresh_token",json.optString("refresh_token"))
            .putString("user_id",json.optJSONObject("user")?.optString("id"))
            .putBoolean("signed_out",false).apply()
    }
    fun savePincode(pincode:String){if(pincode.length==6)prefs.edit().putString("pincode",pincode).apply()}
    fun signOut(){customerAccessToken="";prefs.edit().clear().putBoolean("signed_out",true).apply()}
    fun restoreSession(callback:(Boolean,String?)->Unit){
        val access=prefs.getString("access_token",null).orEmpty()
        val refresh=prefs.getString("refresh_token",null).orEmpty()
        if(prefs.getBoolean("signed_out",false)||access.isBlank()){callback(false,null);return}
        customerAccessToken=access
        if(refresh.isBlank()){callback(true,null);return}
        authRequest("/auth/v1/token?grant_type=refresh_token",JSONObject().put("refresh_token",refresh)){json,error->
            if(json!=null&&json.optString("access_token").isNotBlank()){saveSession(json);callback(true,null)}
            else{customerAccessToken="";prefs.edit().clear().apply();callback(false,error?:"Your session expired. Please log in again.")}
        }
    }
    fun completeAuthentication(phone:String,token:String,callback:(CustomerAuthResult)->Unit){
        if(BuildConfig.DEVELOPMENT_AUTH){
            if(token!="123456"){callback(CustomerAuthResult(false,"For testing, enter OTP 123456"));return}
            val existingRefresh=prefs.getString("refresh_token",null).orEmpty()
            if(existingRefresh.isNotBlank()){
                prefs.edit().putBoolean("signed_out",false).apply()
                restoreSession{ok,error->callback(CustomerAuthResult(ok,error))};return
            }
            authRequest("/auth/v1/signup",JSONObject().put("data",JSONObject()
                .put("zomeal_test_phone","+91$phone").put("account_type","CUSTOMER_TEST"))){json,error->
                if(json!=null&&json.optString("access_token").isNotBlank()){
                    saveSession(json);prefs.edit().putBoolean("development_session",true).apply()
                    callback(CustomerAuthResult(true))
                }else callback(CustomerAuthResult(false,error?:"Anonymous sign-ins must be enabled in Supabase Authentication settings"))
            }
        }else{
            authRequest("/auth/v1/verify",JSONObject().put("type","sms").put("phone","+91$phone").put("token",token)){json,error->
                if(json!=null&&json.optString("access_token").isNotBlank()){saveSession(json);callback(CustomerAuthResult(true))}
                else callback(CustomerAuthResult(false,error?:"OTP verification failed"))
            }
        }
    }
    fun beginAuthentication(phone:String,callback:(CustomerAuthResult)->Unit){
        if(BuildConfig.DEVELOPMENT_AUTH){
            main.post{callback(CustomerAuthResult(true,"Use development OTP 123456"))}
            return
        }
        authRequest(
            "/auth/v1/otp",
            JSONObject().put("phone","+91$phone").put("create_user",true)
        ){_,error->callback(CustomerAuthResult(error==null,error))}
    }
    private fun bearer():String=customerAccessToken.ifBlank{anonKey}

    private fun refreshSessionBlocking():Boolean{
        val refresh=prefs.getString("refresh_token",null).orEmpty();if(refresh.isBlank())return false
        return runCatching{
            val connection=(URL("$baseUrl/auth/v1/token?grant_type=refresh_token").openConnection() as HttpURLConnection).apply{requestMethod="POST";connectTimeout=12000;readTimeout=20000;doOutput=true;setRequestProperty("apikey",anonKey);setRequestProperty("Content-Type","application/json")}
            connection.outputStream.use{it.write(JSONObject().put("refresh_token",refresh).toString().toByteArray())}
            val code=connection.responseCode;val text=(if(code in 200..299)connection.inputStream else connection.errorStream)?.bufferedReader()?.use{it.readText()}.orEmpty();connection.disconnect()
            val json=runCatching{JSONObject(text)}.getOrNull();if(code in 200..299&&json!=null&&json.optString("access_token").isNotBlank()){saveSession(json);true}else false
        }.getOrDefault(false)
    }

    private fun authRequest(path:String,body:JSONObject,callback:(JSONObject?,String?)->Unit){
        if(!configured){callback(null,"Supabase is not configured");return}
        Thread{
            try{
                val connection=(URL("$baseUrl$path").openConnection() as HttpURLConnection).apply{
                    requestMethod="POST";connectTimeout=12000;readTimeout=20000;doOutput=true
                    setRequestProperty("apikey",anonKey);setRequestProperty("Content-Type","application/json")
                }
                connection.outputStream.use{it.write(body.toString().toByteArray())}
                val stream=if(connection.responseCode in 200..299)connection.inputStream else connection.errorStream
                val text=stream?.bufferedReader()?.use{it.readText()}.orEmpty();val json=runCatching{JSONObject(text)}.getOrNull()
                if(connection.responseCode !in 200..299)throw IllegalStateException(json?.optString("msg")?.ifBlank{json.optString("message")}?.ifBlank{json.optString("error_description")}?.ifBlank{"Authentication failed (${connection.responseCode})"})
                main.post{callback(json?:JSONObject(),null)}
            }catch(error:Exception){main.post{callback(null,error.message?:"Authentication service is unavailable")}}
        }.start()
    }

    fun marketplace(pincode:String,callback:(List<MarketplaceProvider>,String?)->Unit){
        if(!configured){callback(emptyList(),"Supabase is not configured");return}
        Thread{
            try{
                fun execute():Pair<Int,String>{
                    val connection=(URL("$baseUrl/rest/v1/rpc/customer_marketplace").openConnection() as HttpURLConnection).apply{requestMethod="POST";connectTimeout=12000;readTimeout=18000;doOutput=true;setRequestProperty("apikey",anonKey);setRequestProperty("Authorization","Bearer ${bearer()}");setRequestProperty("Content-Type","application/json")}
                    connection.outputStream.use{it.write(JSONObject().put("target_pincode",pincode).toString().toByteArray())};val code=connection.responseCode;val text=(if(code in 200..299)connection.inputStream else connection.errorStream)?.bufferedReader()?.use{it.readText()}.orEmpty();connection.disconnect();return code to text
                }
                var response=execute();if(response.first==401&&refreshSessionBlocking())response=execute();val(code,text)=response
                if(code !in 200..299)throw IllegalStateException(runCatching{JSONObject(text).optString("message")}.getOrNull().orEmpty().ifBlank{if(code==401)"Your session expired. Please log in again." else "Marketplace request failed ($code)"})
                val array=JSONArray(text);val result=buildList{for(index in 0 until array.length()){val item=array.optJSONObject(index)?:continue
                    val packageJson=item.optJSONArray("packages")?:JSONArray()
                    val packages=buildList{for(packageIndex in 0 until packageJson.length()){
                        val value=packageJson.optJSONObject(packageIndex)?:continue
                        val id=value.optString("id").trim();val kind=value.optString("kind").trim().uppercase();val price=value.optLong("price_paise")
                        if(id.isNotBlank()&&kind in setOf("LUNCH_ONLY","DINNER_ONLY","LUNCH_AND_DINNER")&&price>0)
                            add(MarketplacePackage(id,value.optString("name").trim(),kind,price))
                    }}.distinctBy{it.id}
                    val providerId=item.optString("provider_id").trim();val displayName=item.optString("display_name").trim()
                    if(providerId.isBlank()||displayName.isBlank()||packages.isEmpty())continue
                    fun cleanPath(value:String)=value.trim().takeUnless{it.equals("null",true)||it.equals("undefined",true)}.orEmpty()
                    add(MarketplaceProvider(
                        providerId,displayName,item.optString("locality",item.optString("city","Bhubaneswar")),
                        item.optString("dietary_type","BOTH"),item.optString("description").takeUnless{it.equals("null",true)}.orEmpty(),packages,item.optJSONArray("weekly_menu")?:JSONArray(),
                        cleanPath(item.optString("primary_photo_path")),cleanPath(item.optString("kitchen_photo_path")),cleanPath(item.optString("meal_photo_path"))
                    ))}}
                main.post{callback(result,null)}
            }catch(error:Exception){main.post{callback(emptyList(),error.message?:"Could not load providers")}}
        }.start()
    }

    fun createRazorpayOrder(packageId:String,deliveryAddress:JSONObject,weeklyMenu:JSONObject,startDate:String,firstMeal:String,callback:(RazorpayCheckoutOrder?,String?)->Unit){
        functionRequest("create-razorpay-order",JSONObject().apply{
            put("package_id",packageId);put("delivery_address",deliveryAddress);put("weekly_menu",weeklyMenu)
            put("start_date",startDate);put("first_meal",firstMeal)
        }){json,error->
            if(error!=null||json==null)callback(null,error?:"Could not create payment order")
            else callback(RazorpayCheckoutOrder(
                json.getString("payment_order_id"),json.getString("razorpay_order_id"),json.getString("key_id"),
                json.getLong("amount_paise"),json.optString("currency","INR"),json.getString("receipt"),json.optBoolean("test_mode",true)
            ),null)
        }
    }

    fun activeSubscription(callback:(PersistedSubscription?,String?)->Unit){
        if(!configured){callback(null,"Supabase is not configured");return}
        if(customerAccessToken.isBlank()){callback(null,"Customer authentication is required");return}
        rpc("customer_active_subscription_state",JSONObject()){json,error->
            if(error!=null||json==null){callback(null,error);return@rpc}
            if(!json.optBoolean("has_active_subscription")){callback(null,null);return@rpc}
            runCatching{
                val subscription=json.getJSONObject("subscription")
                val mealRows=json.optJSONArray("daily_meals")?:JSONArray()
                val meals=buildList{for(index in 0 until mealRows.length()){
                    val row=mealRows.getJSONObject(index)
                    add(PersistedDailyMeal(
                        row.optString("id"),row.optString("service_date"),row.optString("meal_slot"),row.optString("status"),
                        row.optString("item_id"),row.optString("item_name"),row.optString("dietary_type"),row.optString("description")
                    ))
                }}
                PersistedSubscription(
                    subscription.getString("id"),subscription.optString("status"),subscription.optString("provider_id"),subscription.optString("provider_name"),
                    subscription.optString("package_id"),subscription.optString("package_name"),subscription.optString("package_kind"),
                    subscription.optString("start_date"),subscription.optString("end_date"),subscription.optString("payment_reference"),
                    json.optJSONObject("address"),json.optJSONArray("weekly_menu")?:JSONArray(),meals,json.optJSONObject("payment")
                )
            }.fold(onSuccess={callback(it,null)},onFailure={callback(null,it.message?:"Could not read subscription")})
        }
    }

    fun requestSubscriptionChange(subscriptionId:String,action:String,replacementProviderId:String?=null,reason:String?=null,callback:(JSONObject?,String?)->Unit){
        if(customerAccessToken.isBlank()){callback(null,"Please sign in again before changing your subscription");return}
        rpc("customer_request_subscription_change",JSONObject().apply{
            put("target_subscription",subscriptionId);put("requested_action",action)
            put("replacement_provider",replacementProviderId?.takeIf{it.isNotBlank()}?:JSONObject.NULL)
            put("customer_reason",reason?.takeIf{it.isNotBlank()}?:JSONObject.NULL)
        },callback)
    }

    fun switchProviderNow(subscriptionId:String,providerId:String,packageId:String,weeklyMenu:JSONObject,callback:(JSONObject?,String?)->Unit){
        if(customerAccessToken.isBlank()){callback(null,"Please sign in again before changing your provider");return}
        rpc("customer_switch_provider",JSONObject().apply{
            put("target_subscription",subscriptionId)
            put("replacement_provider",providerId)
            put("replacement_package",packageId)
            put("target_weekly_menu",weeklyMenu)
        },callback)
    }

    fun saveCheckoutDraft(providerId:String,packageId:String,payload:JSONObject,callback:(String?)->Unit){
        rpc("customer_save_checkout_draft",JSONObject().apply{put("target_provider",providerId);put("target_package",packageId);put("target_payload",payload)}){_,error->callback(error)}
    }

    fun pendingCheckout(callback:(PendingCheckout?,String?)->Unit){
        rpc("customer_pending_checkout",JSONObject()){json,error->
            if(error!=null||json==null){callback(null,error);return@rpc}
            if(!json.optBoolean("has_pending_checkout")){callback(null,null);return@rpc}
            callback(PendingCheckout(json.optString("provider_id"),json.optString("package_id"),json.optJSONObject("payload")?:JSONObject(),json.optString("updated_at")),null)
        }
    }

    fun clearCheckoutDraft(callback:(String?)->Unit){rpc("customer_clear_checkout_draft",JSONObject()){_,error->callback(error)}}
    fun referralProgram(callback:(JSONObject?,String?)->Unit){rpc("customer_referral_program",JSONObject(),callback)}
    fun referralDashboard(callback:(JSONObject?,String?)->Unit){rpc("customer_referral_dashboard",JSONObject(),callback)}
    fun applyReferral(code:String,callback:(JSONObject?,String?)->Unit){
        rpc("customer_apply_referral",JSONObject().put("target_code",code.trim().uppercase()),callback)
    }
    fun changeDailyMeal(mealId:String,itemId:String,callback:(JSONObject?,String?)->Unit){rpc("customer_select_daily_meal",JSONObject().put("target_meal_id",mealId).put("target_item_id",itemId),callback)}
    fun pauseMeals(subscriptionId:String,dates:List<String>,slot:String,callback:(JSONObject?,String?)->Unit){rpc("customer_pause_subscription_meals",JSONObject().put("target_subscription",subscriptionId).put("target_dates",JSONArray(dates)).put("target_slot",slot.uppercase()),callback)}

    fun approvedMedia(path:String,callback:(Bitmap?)->Unit){
        if(path.isBlank()||path.equals("null",true)||path.equals("undefined",true)){callback(null);return}
        Thread{
            val bitmap=runCatching{
                val encoded=path.split('/').joinToString("/"){URLEncoder.encode(it,"UTF-8").replace("+","%20")}
                fun download():Pair<Int,ByteArray?>{
                    val connection=(URL("$baseUrl/storage/v1/object/authenticated/provider-media/$encoded").openConnection() as HttpURLConnection).apply{
                        requestMethod="GET";connectTimeout=12000;readTimeout=20000
                        setRequestProperty("apikey",anonKey);setRequestProperty("Authorization","Bearer ${bearer()}")
                    }
                    val code=connection.responseCode
                    val bytes=if(code in 200..299)connection.inputStream.use{it.readBytes()}else null
                    connection.disconnect();return code to bytes
                }
                var response=download();if(response.first==401&&refreshSessionBlocking())response=download()
                response.second?.let{BitmapFactory.decodeByteArray(it,0,it.size)}
            }.getOrNull()
            main.post{callback(bitmap)}
        }.start()
    }

    fun saveRegistrationProfile(fullName:String,phone:String,callback:(String?)->Unit){
        if(customerAccessToken.isBlank()){callback("Customer authentication is required");return}
        rpc("customer_save_registration_profile",JSONObject().apply{
            put("target_full_name",fullName.trim());put("target_phone",phone)
        }){_,error->callback(error)}
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
                    setRequestProperty("apikey",anonKey);setRequestProperty("Authorization","Bearer ${bearer()}");setRequestProperty("Content-Type","application/json")
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

    private fun rpc(name:String,body:JSONObject,callback:(JSONObject?,String?)->Unit){
        Thread{
            try{
                fun execute():Pair<Int,String>{
                    val connection=(URL("$baseUrl/rest/v1/rpc/$name").openConnection() as HttpURLConnection).apply{requestMethod="POST";connectTimeout=12000;readTimeout=20000;doOutput=true;setRequestProperty("apikey",anonKey);setRequestProperty("Authorization","Bearer ${bearer()}");setRequestProperty("Content-Type","application/json")}
                    connection.outputStream.use{it.write(body.toString().toByteArray())};val code=connection.responseCode;val text=(if(code in 200..299)connection.inputStream else connection.errorStream)?.bufferedReader()?.use{it.readText()}.orEmpty();connection.disconnect();return code to text
                }
                var response=execute();if(response.first==401&&refreshSessionBlocking())response=execute();val(code,text)=response
                if(code !in 200..299)throw IllegalStateException(runCatching{JSONObject(text).optString("message")}.getOrNull().orEmpty().ifBlank{if(code==401)"Your session expired. Please log in again." else "Request failed ($code)"})
                main.post{callback(if(text.isBlank())JSONObject() else JSONObject(text),null)}
            }catch(error:Exception){main.post{callback(null,error.message?:"Customer data is unavailable")}}
        }.start()
    }
}
