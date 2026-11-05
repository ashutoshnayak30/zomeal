package com.zomeal.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

internal object CustomerPushNotifications {
    private const val PREFS = "zomeal_customer_session"
    private const val TOKEN = "fcm_token"
    private val syncing = AtomicBoolean(false)
    private val renewing = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())

    fun initialize(context: Context) {
        createChannel(context)
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(TOKEN, token).apply()
            sync(context)
        }
    }

    fun sync(context: Context) = syncAttempt(context.applicationContext, 0)

    private fun syncAttempt(context: Context, attempt: Int) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val token = prefs.getString(TOKEN, "").orEmpty()
        val accessToken = prefs.getString("access_token", "").orEmpty()
        if (token.isBlank() || accessToken.isBlank() || !BuildConfig.SUPABASE_URL.startsWith("https://")) return
        if (!syncing.compareAndSet(false, true)) return
        Thread {
            var retry = false
            var refresh = false
            try {
                val connection = (URL("${BuildConfig.SUPABASE_URL.trimEnd('/')}/rest/v1/rpc/register_customer_push_device").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; connectTimeout = 12_000; readTimeout = 15_000; doOutput = true
                    setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY)
                    setRequestProperty("Authorization", "Bearer $accessToken")
                    setRequestProperty("Content-Type", "application/json")
                }
                try {
                    connection.outputStream.use { it.write(JSONObject().put("target_token", token).toString().toByteArray()) }
                    if (connection.responseCode in 200..299) {
                        val result = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                        refresh = result.optBoolean("refresh_required")
                        retry = !refresh && !result.optBoolean("registered")
                    } else retry = true
                } finally { connection.disconnect() }
            } catch (_: Exception) { retry = true
            } finally { syncing.set(false) }
            if (refresh) main.post { renewToken(context) }
            else if (retry && attempt < 2) main.postDelayed({ syncAttempt(context, attempt + 1) }, 30_000L)
        }.start()
    }

    private fun renewToken(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Bound renewal attempts if Play Services/network is temporarily unavailable.
        if (System.currentTimeMillis() - prefs.getLong("push_token_renewed_at", 0L) < 600_000L) return
        if (!renewing.compareAndSet(false, true)) return
        FirebaseMessaging.getInstance().deleteToken().addOnCompleteListener { deleted ->
            if (!deleted.isSuccessful) { renewing.set(false); return@addOnCompleteListener }
            prefs.edit().remove(TOKEN).apply()
            FirebaseMessaging.getInstance().token.addOnCompleteListener { fresh ->
                renewing.set(false)
                if (fresh.isSuccessful) {
                    prefs.edit().putString(TOKEN, fresh.result).putLong("push_token_renewed_at", System.currentTimeMillis()).apply()
                    sync(context)
                }
            }
        }
    }

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("zomeal_updates", "Zomeal updates", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Meal, wallet, subscription and account updates"
            })
        }
    }
}

class ZomealMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        getSharedPreferences("zomeal_customer_session", MODE_PRIVATE).edit().putString("fcm_token", token).apply()
        CustomerPushNotifications.sync(this)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        CustomerPushNotifications.createChannel(this)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val title = message.data["title"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: message.notification?.title?.trim()?.takeIf { it.isNotEmpty() } ?: "Zomeal"
        val body = message.data["body"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: message.notification?.body?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val destination = message.data["destination"] ?: "home"
        val intent = Intent(this, MainActivity::class.java).putExtra("notification_destination", destination)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(this, destination.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, "zomeal_updates")
            .setSmallIcon(R.drawable.ic_zomeal_status).setContentTitle(title).setContentText(body)
            .setColor(android.graphics.Color.rgb(8, 131, 65))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body)).setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true).setContentIntent(pending).build()
        val notificationId = message.data["notification_id"]
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (notificationId != null) manager.notify(notificationId, 0, notification)
        else manager.notify((System.currentTimeMillis() and 0x7fffffff).toInt(), notification)
    }
}
