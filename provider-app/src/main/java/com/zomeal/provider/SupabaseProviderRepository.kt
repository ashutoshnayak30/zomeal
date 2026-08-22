package com.zomeal.provider

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
import java.util.UUID

data class AuthResult(val success: Boolean, val message: String? = null)

class SupabaseProviderRepository(context: Context) {
    private val developmentPhones = setOf("9999999999", "7000000001", "7000000002", "7000000003", "7000000004", "7000000005")
    private val appContext = context.applicationContext
    private val prefs = context.getSharedPreferences("zomeal_provider_session", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val baseUrl = BuildConfig.SUPABASE_URL.trimEnd('/')
    private val anonKey = BuildConfig.SUPABASE_ANON_KEY

    val isConfigured: Boolean get() = baseUrl.startsWith("https://") && anonKey.isNotBlank()
    val isAuthenticated: Boolean get() = !prefs.getBoolean("signed_out", false) && !prefs.getString("access_token", null).isNullOrBlank()

    val developmentAuthEnabled: Boolean get() = BuildConfig.DEVELOPMENT_AUTH

    fun sendOtp(phone: String, callback: (AuthResult) -> Unit) = requestAsync(
        path = "/auth/v1/otp",
        body = JSONObject().put("phone", "+91$phone").put("create_user", true),
        authenticated = false
    ) { code, json -> callback(if (code in 200..299) AuthResult(true) else AuthResult(false, errorMessage(json, "Unable to send OTP"))) }

    fun beginAuthentication(phone: String, callback: (AuthResult) -> Unit) {
        if (developmentAuthEnabled && phone in developmentPhones) {
            main.post { callback(AuthResult(true, "Use development OTP 123456")) }
        } else sendOtp(phone, callback)
    }

    fun verifyOtp(phone: String, token: String, callback: (AuthResult) -> Unit) = requestAsync(
        path = "/auth/v1/verify",
        body = JSONObject().put("type", "sms").put("phone", "+91$phone").put("token", token),
        authenticated = false
    ) { code, json ->
        if (code in 200..299 && json.has("access_token")) {
            prefs.edit()
                .putString("access_token", json.optString("access_token"))
                .putString("refresh_token", json.optString("refresh_token"))
                .putString("user_id", json.optJSONObject("user")?.optString("id"))
                .apply()
            callback(AuthResult(true))
        } else callback(AuthResult(false, errorMessage(json, "OTP verification failed")))
    }

    fun completeAuthentication(phone: String, token: String, callback: (AuthResult) -> Unit) {
        if (developmentAuthEnabled && phone in developmentPhones) {
            if (token != "123456") {
                main.post { callback(AuthResult(false, "For this development number, enter OTP 123456")) }
                return
            }
            // Reuse the same anonymous development identity after an in-app
            // sign-out so its draft/provider application remains addressable.
            if (prefs.getBoolean("development_session", false) && prefs.getString("development_phone","")==phone && !prefs.getString("refresh_token", null).isNullOrBlank()) {
                prefs.edit().putBoolean("signed_out", false).apply()
                claimSeededProvider(phone,callback)
                return
            }
            if(prefs.getBoolean("development_session",false))prefs.edit().clear().apply()
            createAnonymousDevelopmentSession(phone, callback)
        } else verifyOtp(phone, token, callback)
    }

    private fun createAnonymousDevelopmentSession(phone: String, callback: (AuthResult) -> Unit) = requestAsync(
        path = "/auth/v1/signup",
        body = JSONObject().put("data", JSONObject()
            .put("zomeal_test_phone", "+91$phone")
            .put("account_type", "PROVIDER_TEST")),
        authenticated = false
    ) { code, json ->
        if (code in 200..299 && json.has("access_token")) {
            prefs.edit()
                .putString("access_token", json.optString("access_token"))
                .putString("refresh_token", json.optString("refresh_token"))
                .putString("user_id", json.optJSONObject("user")?.optString("id"))
                .putBoolean("development_session", true)
                .putString("development_phone",phone)
                .putBoolean("signed_out", false)
                .apply()
            claimSeededProvider(phone,callback)
        } else callback(AuthResult(false, errorMessage(json, "Anonymous sign-ins must be enabled in Supabase Authentication settings")))
    }

    private fun claimSeededProvider(phone:String,callback:(AuthResult)->Unit){
        if(phone=="9999999999"){main.post{callback(AuthResult(true))};return}
        requestAsync(
            path="/rest/v1/rpc/provider_claim_seeded_test_account",
            body=JSONObject().put("target_phone",phone),authenticated=true
        ){code,json->callback(if(code in 200..299)AuthResult(true) else AuthResult(false,errorMessage(json,"This demo provider account could not be opened")))}
    }

    fun saveDraft(payload: JSONObject, callback: ((AuthResult) -> Unit)? = null) = requestAsync(
        path = "/rest/v1/rpc/save_provider_form_draft",
        body = JSONObject()
            .put("target_provider_id", JSONObject.NULL)
            .put("scope_name", "provider_mobile_onboarding")
            .put("draft_payload", payload),
        authenticated = true
    ) { code, json -> callback?.invoke(if (code in 200..299) AuthResult(true) else AuthResult(false, errorMessage(json, "Draft could not be saved"))) }

    fun savePrimaryDeliveryContact(name: String, phone: String, callback: (AuthResult) -> Unit) = requestAsync(
        path = "/rest/v1/rpc/provider_upsert_primary_delivery_contact",
        body = JSONObject().put("contact_name", name.trim().ifBlank { JSONObject.NULL }).put("contact_phone", phone),
        authenticated = true
    ) { code, json -> callback(if (code in 200..299) AuthResult(true) else AuthResult(false, errorMessage(json, "Delivery contact could not be saved"))) }

    fun loadDraft(callback: (JSONObject?) -> Unit) = requestAsync(
        path = "/rest/v1/rpc/get_provider_form_draft",
        body = JSONObject().put("target_provider_id", JSONObject.NULL).put("scope_name", "provider_mobile_onboarding"),
        authenticated = true
    ) { code, json ->
        if (code !in 200..299 || json.length() == 0 || json.toString() == "null") callback(null)
        else callback(json.optJSONObject("payload"))
    }

    fun submitApplication(payload: JSONObject, callback: (AuthResult) -> Unit) = requestAsync(
        path = "/rest/v1/rpc/submit_provider_mobile_application",
        body = JSONObject().put("payload", payload),
        authenticated = true
    ) { code, json ->
        if (code !in 200..299) {
            callback(AuthResult(false, errorMessage(json, "Application could not be submitted")))
            return@requestAsync
        }
        val providerId = json.optString("provider_id")
        if (providerId.isBlank()) {
            callback(AuthResult(false, "Provider record was not returned"))
            return@requestAsync
        }
        if (ProviderDraft.bothEnabled) {
            requestAsync(
                path = "/rest/v1/rpc/provider_set_combined_package_values",
                body = JSONObject().put("target_lunch_daily_rupees", ProviderDraft.bothLunchDailyPrice.toDoubleOrNull() ?: 0.0),
                authenticated = true
            ) { splitCode, splitJson ->
                if (splitCode in 200..299) uploadAllSelectedMedia(providerId, callback = callback)
                else callback(AuthResult(false, errorMessage(splitJson, "Combined lunch and dinner values could not be saved")))
            }
        } else uploadAllSelectedMedia(providerId, callback = callback)
    }

    /**
     * Active listings remain customer-visible while this complete replacement
     * workspace is reviewed. New prices, menus and photographs are staged as
     * pending rather than overwriting the approved catalogue in-place.
     */
    fun submitActiveBusinessUpdate(payload: JSONObject, callback: (AuthResult) -> Unit) = requestAsync(
        path = "/rest/v1/rpc/provider_submit_business_update",
        body = JSONObject().put("payload", payload),
        authenticated = true
    ) { code, json ->
        if (code !in 200..299) {
            callback(AuthResult(false, errorMessage(json, "Business changes could not be submitted")))
            return@requestAsync
        }
        val providerId = json.optString("provider_id")
        val changeRequestId = json.optString("change_request_id")
        if (providerId.isBlank()) {
            callback(AuthResult(false, "Provider record was not returned"))
            return@requestAsync
        }
        uploadAllSelectedMedia(providerId, changeRequestId.ifBlank { null }, onlyChanged = true) { mediaResult ->
            if (!mediaResult.success) { callback(mediaResult); return@uploadAllSelectedMedia }
            savePrimaryDeliveryContact(ProviderDraft.deliveryName, ProviderDraft.deliveryPhone) { deliveryResult ->
                if (deliveryResult.success) ProviderDraft.acceptCurrentAsBaseline()
                callback(if (deliveryResult.success) AuthResult(true, "Only your changed information was submitted for Zomeal approval. Your current listing remains active.") else deliveryResult)
            }
        }
    }

    fun loadApplicationStatus(callback: (JSONObject?) -> Unit) = requestAsync(
        path = "/rest/v1/rpc/provider_application_status",
        body = JSONObject(),
        authenticated = true
    ) { code, json -> callback(if (code in 200..299) json else null) }

    fun loadSubmittedApplication(markPhotoBaseline: Boolean = true, callback: (JSONObject?) -> Unit) = requestAsync(
        path = "/rest/v1/rpc/provider_submitted_application",
        body = JSONObject(),
        authenticated = true
    ) { code, json ->
        val candidate = if (code in 200..299) json.optJSONObject("payload") else null
        // Never replace the in-memory approved listing with an empty historical
        // onboarding draft. The database RPC now falls back to the latest full
        // accepted/requested snapshot; this guard also keeps older deployments
        // from rendering a completely blank editor.
        val payload = candidate?.takeIf { it.optString("businessName").isNotBlank() }
        if (payload != null) {
            ProviderDraft.restore(payload)
            if (markPhotoBaseline) ProviderDraft.markEditBaseline(payload)
        }
        callback(payload)
    }

    fun loadLatestBusinessChange(callback: (JSONObject?) -> Unit) = requestAsync(
        path = "/rest/v1/rpc/provider_latest_business_change",
        body = JSONObject(),
        authenticated = true
    ) { code, json -> callback(if (code in 200..299) json else null) }

    fun loadDailyDashboard(slot: String, date: String? = null, callback: (JSONObject?, String?) -> Unit) = requestAsync(
        path = "/rest/v1/rpc/provider_daily_dashboard",
        body = JSONObject().put("target_slot", slot).put("target_date", date?.takeIf { it.isNotBlank() } ?: JSONObject.NULL),
        authenticated = true
    ) { code, json ->
        if (code in 200..299) callback(json, null)
        else callback(null, errorMessage(json, "Dashboard could not be loaded"))
    }

    fun updateDailyMealStatus(slot: String, status: String, date: String? = null, callback: (AuthResult) -> Unit) = requestAsync(
        path = "/rest/v1/rpc/provider_update_daily_meal_status",
        body = JSONObject().put("target_slot", slot).put("target_date", date?.takeIf { it.isNotBlank() } ?: JSONObject.NULL).put("new_status", status),
        authenticated = true
    ) { code, json -> callback(if (code in 200..299) AuthResult(true) else AuthResult(false, errorMessage(json, "Meal status could not be updated"))) }

    fun assignDeliveryBatch(personnelId: String, slot: String, count: Int, date: String? = null, callback: (AuthResult) -> Unit) = requestAsync(
        path = "/rest/v1/rpc/provider_assign_delivery_batch",
        body = JSONObject().put("target_personnel_id", personnelId).put("target_slot", slot)
            .put("target_date", date?.takeIf { it.isNotBlank() } ?: JSONObject.NULL).put("maximum_meals", count),
        authenticated = true
    ) { code, json -> callback(if (code in 200..299) AuthResult(true) else AuthResult(false, errorMessage(json, "Delivery batch could not be assigned"))) }

    fun autoAssignRoutes(slot: String, date: String? = null, callback: (AuthResult) -> Unit) = requestAsync(
        path = "/rest/v1/rpc/provider_auto_assign_routes",
        body = JSONObject().put("target_slot", slot).put("target_date", date?.takeIf { it.isNotBlank() } ?: JSONObject.NULL),
        authenticated = true
    ) { code, json -> callback(if (code in 200..299) AuthResult(true, "${json.optInt("assigned")} meals assigned") else AuthResult(false, errorMessage(json, "Routes could not be assigned"))) }

    fun loadEarningsSummary(callback: (JSONObject?, String?) -> Unit) = requestAsync(
        path = "/rest/v1/rpc/provider_earnings_summary",
        body = JSONObject(),
        authenticated = true
    ) { code, json ->
        if (code in 200..299) callback(json, null)
        else callback(null, errorMessage(json, "Earnings could not be loaded"))
    }

    fun requestPayout(amountPaise: Long, method: String, note: String, callback: (AuthResult) -> Unit) = requestAsync(
        path = "/rest/v1/rpc/provider_request_payout",
        body = JSONObject().put("target_amount_paise", amountPaise).put("target_method", method)
            .put("target_note", note.ifBlank { JSONObject.NULL }),
        authenticated = true
    ) { code, json ->
        callback(if (code in 200..299) AuthResult(true, "Payout request submitted")
        else AuthResult(false, errorMessage(json, "Payout request could not be submitted")))
    }

    fun requestAdvance(amountPaise: Long, purpose: String, callback: (AuthResult) -> Unit) = requestAsync(
        path = "/rest/v1/rpc/provider_request_advance",
        body = JSONObject().put("target_amount_paise", amountPaise).put("target_purpose", purpose),
        authenticated = true
    ) { code, json ->
        callback(if (code in 200..299) AuthResult(true, "Advance request submitted for review")
        else AuthResult(false, errorMessage(json, "Advance request could not be submitted")))
    }

    fun loadCommissionSummary(callback: (JSONObject?, String?) -> Unit) = requestAsync(
        path = "/rest/v1/rpc/provider_commission_summary",
        body = JSONObject().put("target_provider", JSONObject.NULL), authenticated = true
    ) { code, json -> if (code in 200..299) callback(json, null) else callback(null, errorMessage(json, "Commission profile could not be loaded")) }

    fun loadProviderProfileHub(callback: (JSONObject?, String?) -> Unit) = requestAsync(
        path = "/rest/v1/rpc/provider_profile_hub",
        body = JSONObject(), authenticated = true
    ) { code, json -> if (code in 200..299) callback(json, null) else callback(null, errorMessage(json, "Provider profile could not be loaded")) }

    fun approvedMedia(path:String,callback:(Bitmap?)->Unit){
        if(path.isBlank()){callback(null);return}
        Thread{
            val bitmap=runCatching{
                val encoded=path.split('/').joinToString("/"){URLEncoder.encode(it,"UTF-8").replace("+","%20")}
                val connection=(URL("$baseUrl/storage/v1/object/authenticated/provider-media/$encoded").openConnection() as HttpURLConnection).apply{
                    connectTimeout=12000;readTimeout=20000;setRequestProperty("apikey",anonKey);setRequestProperty("Authorization","Bearer ${prefs.getString("access_token","")}")
                }
                val result=if(connection.responseCode in 200..299)connection.inputStream.use(BitmapFactory::decodeStream) else null
                connection.disconnect();result
            }.getOrNull();main.post{callback(bitmap)}
        }.start()
    }

    fun loadPayoutDestination(callback: (JSONObject?, String?) -> Unit) = requestAsync(
        path = "/rest/v1/rpc/provider_payout_destination", body = JSONObject(), authenticated = true
    ) { code, json -> if (code in 200..299) callback(json, null) else callback(null, errorMessage(json, "Payout details could not be loaded")) }

    fun savePayoutDestination(method: String, holder: String, upi: String, account: String, ifsc: String, bank: String, note: String, callback: (AuthResult) -> Unit) = requestAsync(
        path = "/rest/v1/rpc/provider_save_payout_destination",
        body = JSONObject().put("target_method", method).put("target_holder", holder)
            .put("target_upi", upi.ifBlank { JSONObject.NULL }).put("target_account", account.ifBlank { JSONObject.NULL })
            .put("target_ifsc", ifsc.ifBlank { JSONObject.NULL }).put("target_bank", bank.ifBlank { JSONObject.NULL })
            .put("target_note", note.ifBlank { JSONObject.NULL }), authenticated = true
    ) { code, json -> callback(if (code in 200..299) AuthResult(true, "Payout details sent for verification") else AuthResult(false, errorMessage(json, "Payout details could not be saved"))) }

    fun loadNotifications(callback: (JSONObject?, String?) -> Unit) = requestAsync(
        path = "/rest/v1/rpc/provider_notification_feed",
        body = JSONObject().put("target_limit", 75), authenticated = true
    ) { code, json -> if (code in 200..299) callback(json, null) else callback(null, errorMessage(json, "Notifications could not be loaded")) }

    fun markNotificationsRead(notificationId: String? = null, callback: (AuthResult) -> Unit) = requestAsync(
        path = "/rest/v1/rpc/provider_mark_notifications_read",
        body = JSONObject().put("target_notification", notificationId ?: JSONObject.NULL), authenticated = true
    ) { code, json -> callback(if (code in 200..299) AuthResult(true) else AuthResult(false, errorMessage(json, "Notification could not be updated"))) }

    fun resumeReturnedApplication(callback: (AuthResult) -> Unit) = requestAsync(
        path = "/rest/v1/rpc/provider_resume_application",
        body = JSONObject(),
        authenticated = true
    ) { code, json ->
        if (code in 200..299) { ProviderDraft.restore(json); callback(AuthResult(true)) }
        else callback(AuthResult(false, errorMessage(json, "Application cannot be edited yet")))
    }

    fun signOut() {
        if (developmentAuthEnabled && prefs.getBoolean("development_session", false)) {
            prefs.edit().putBoolean("signed_out", true).apply()
        } else {
            prefs.edit().clear().apply()
        }
    }

    private fun requestAsync(path: String, body: JSONObject, authenticated: Boolean, callback: (Int, JSONObject) -> Unit) {
        if (!isConfigured) {
            main.post { callback(0, JSONObject().put("message", "Supabase URL and anon key are not configured in local.properties")) }
            return
        }
        Thread {
            try {
                var response = executeJsonRequest(path, body, authenticated)
                if (authenticated && response.first == 401 && refreshSessionBlocking()) {
                    response = executeJsonRequest(path, body, true)
                }
                main.post { callback(response.first, response.second) }
            } catch (error: Exception) {
                main.post { callback(0, JSONObject().put("message", error.message ?: "Network error")) }
            }
        }.start()
    }

    private fun executeJsonRequest(path: String, body: JSONObject, authenticated: Boolean): Pair<Int, JSONObject> {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(baseUrl + path).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("apikey", anonKey)
            val accessToken = prefs.getString("access_token", null)
            connection.setRequestProperty("Authorization", "Bearer ${if (authenticated) accessToken else anonKey}")
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            code to parseObject(raw)
        } finally { connection?.disconnect() }
    }

    private fun refreshSessionBlocking(): Boolean {
        val refreshToken = prefs.getString("refresh_token", null)?.takeIf { it.isNotBlank() } ?: return false
        var connection: HttpURLConnection? = null
        return try {
            connection = URL("$baseUrl/auth/v1/token?grant_type=refresh_token").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("apikey", anonKey)
            connection.setRequestProperty("Authorization", "Bearer $anonKey")
            connection.outputStream.use { it.write(JSONObject().put("refresh_token", refreshToken).toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) return false
            val json = parseObject(connection.inputStream.bufferedReader().use { it.readText() })
            val newAccess = json.optString("access_token")
            if (newAccess.isBlank()) return false
            prefs.edit().putString("access_token", newAccess)
                .putString("refresh_token", json.optString("refresh_token").ifBlank { refreshToken }).apply()
            true
        } catch (_: Exception) { false }
        finally { connection?.disconnect() }
    }

    private data class PendingMedia(val uri: String, val type: String, val dishName: String?, val alt: String)

    private fun uploadAllSelectedMedia(providerId: String, changeRequestId: String? = null, onlyChanged: Boolean = false, callback: (AuthResult) -> Unit) {
        val media = mutableListOf<PendingMedia>()
        ProviderDraft.profilePhoto?.let { media += PendingMedia(it, "OWNER_PROFILE", null, ProviderDraft.businessName + " owner") }
        ProviderDraft.kitchenPhoto?.let { media += PendingMedia(it, "KITCHEN", null, ProviderDraft.businessName + " kitchen") }
        ProviderDraft.mealPhoto?.let { media += PendingMedia(it, "MEAL", null, ProviderDraft.businessName + " meal") }
        ProviderDraft.menus.forEach { day -> (day.lunch + day.dinner).forEach { dish ->
            dish.photo?.let { if (dish.name.isNotBlank()) media += PendingMedia(it, "MENU_ITEM", dish.name, dish.name) }
        } }
        val selected = media.distinctBy { it.uri + it.type + it.dishName }
            .filter { !onlyChanged || ProviderDraft.isNewOrReplacedPhoto(it.uri) }
        if (selected.isEmpty()) { callback(AuthResult(true)); return }
        uploadNext(providerId, changeRequestId, selected, 0, callback)
    }

    private fun uploadNext(providerId: String, changeRequestId: String?, media: List<PendingMedia>, index: Int, callback: (AuthResult) -> Unit) {
        if (index >= media.size) { callback(AuthResult(true)); return }
        val item = media[index]
        val uri = android.net.Uri.parse(item.uri)
        Thread {
            try {
                val resolver = appContext.contentResolver
                val mime = resolver.getType(uri)?.takeIf { it in setOf("image/jpeg", "image/png", "image/webp") } ?: "image/jpeg"
                val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("A previously selected photo is no longer accessible. Please return to the relevant menu or photos step and select it again.")
                if (bytes.isEmpty() || bytes.size > 5 * 1024 * 1024) throw IllegalStateException("Each photo must be between 1 byte and 5 MB")
                val extension = when (mime) { "image/png" -> "png"; "image/webp" -> "webp"; else -> "jpg" }
                val path = "$providerId/${item.type.lowercase()}/${UUID.randomUUID()}.$extension"
                uploadStorageObject(path, mime, bytes) { uploadResult ->
                    if (!uploadResult.success) { callback(uploadResult); return@uploadStorageObject }
                    requestAsync(
                        if (changeRequestId == null) "/rest/v1/rpc/provider_register_uploaded_media" else "/rest/v1/rpc/provider_register_change_media",
                        JSONObject().apply { if (changeRequestId != null) put("target_change_request_id", changeRequestId) }
                            .put("target_provider_id", providerId).put("target_menu_item_name", item.dishName ?: JSONObject.NULL)
                            .put("media_kind", item.type).put("object_path", path).put("mime", mime)
                            .put("bytes", bytes.size).put("alt_text_value", item.alt), true
                    ) { code, json ->
                        if (code in 200..299) uploadNext(providerId, changeRequestId, media, index + 1, callback)
                        else callback(AuthResult(false, errorMessage(json, "Photo metadata could not be registered")))
                    }
                }
            } catch (error: Exception) { main.post { callback(AuthResult(false, error.message ?: "Photo upload failed")) } }
        }.start()
    }

    private fun uploadStorageObject(path: String, mime: String, bytes: ByteArray, callback: (AuthResult) -> Unit) {
        Thread {
            var connection: HttpURLConnection? = null
            try {
                connection = URL("$baseUrl/storage/v1/object/provider-media/$path").openConnection() as HttpURLConnection
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.connectTimeout = 20_000; connection.readTimeout = 30_000
                connection.setRequestProperty("Content-Type", mime)
                connection.setRequestProperty("apikey", anonKey)
                connection.setRequestProperty("Authorization", "Bearer ${prefs.getString("access_token", "")}")
                connection.setRequestProperty("x-upsert", "false")
                connection.outputStream.use { it.write(bytes) }
                val code = connection.responseCode
                val raw = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                main.post { callback(if (code in 200..299) AuthResult(true) else AuthResult(false, errorMessage(parseObject(raw), "Photo upload failed"))) }
            } catch (error: Exception) { main.post { callback(AuthResult(false, error.message ?: "Photo upload failed")) } }
            finally { connection?.disconnect() }
        }.start()
    }

    private fun parseObject(raw: String): JSONObject = try {
        when {
            raw.trim().startsWith("[") -> JSONArray(raw).optJSONObject(0) ?: JSONObject()
            raw.isBlank() || raw.trim() == "null" -> JSONObject()
            else -> JSONObject(raw)
        }
    } catch (_: Exception) { JSONObject().put("message", raw.take(200)) }

    private fun errorMessage(json: JSONObject, fallback: String): String =
        json.optString("msg").ifBlank { json.optString("message").ifBlank { json.optString("error_description").ifBlank { fallback } } }
}
