package com.zomeal.provider

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal object ProviderPushNotifications {
    private const val PREFS = "zomeal_provider_session"
    private const val TOKEN = "fcm_token"

    fun initialize(context: Context) {
        createChannel(context)
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(TOKEN, token).apply()
            sync(context)
        }
    }

    fun sync(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val token = prefs.getString(TOKEN, "").orEmpty()
        val accessToken = prefs.getString("access_token", "").orEmpty()
        if (token.isBlank() || accessToken.isBlank() || !BuildConfig.SUPABASE_URL.startsWith("https://")) return
        Thread {
            runCatching {
                val connection = (URL("${BuildConfig.SUPABASE_URL.trimEnd('/')}/rest/v1/rpc/register_push_device").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; connectTimeout = 12_000; readTimeout = 15_000; doOutput = true
                    setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY)
                    setRequestProperty("Authorization", "Bearer $accessToken")
                    setRequestProperty("Content-Type", "application/json")
                }
                connection.outputStream.use { it.write(JSONObject().put("target_token", token).put("target_app", "PROVIDER").put("target_platform", "ANDROID").toString().toByteArray()) }
                connection.responseCode
                connection.disconnect()
            }
        }.start()
    }

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel("zomeal_provider_updates", "Zomeal Provider updates", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Orders, approvals, payouts and operational updates"
                }
            )
        }
    }
}

class ZomealProviderMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        getSharedPreferences("zomeal_provider_session", MODE_PRIVATE).edit().putString("fcm_token", token).apply()
        ProviderPushNotifications.sync(this)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        ProviderPushNotifications.createChannel(this)
        val title = message.notification?.title ?: message.data["title"] ?: "Zomeal Provider"
        val body = message.notification?.body ?: message.data["body"] ?: return
        val destination = message.data["destination"] ?: "dashboard"
        val intent = Intent(this, MainActivity::class.java).putExtra("notification_destination", destination)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(this, destination.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, "zomeal_provider_updates")
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body)).setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true).setContentIntent(pending).build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify((System.currentTimeMillis() and 0x7fffffff).toInt(), notification)
    }
}
