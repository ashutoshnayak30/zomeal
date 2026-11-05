package com.zomeal.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri

// Opens a composer only. Opening an app is not proof that a referral was sent.
internal fun shareZomealReferral(context: Context, channel: String, text: String): String {
    fun launch(intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (_: android.content.ActivityNotFoundException) { false }
      catch (_: SecurityException) { false }
    fun send() = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    when (channel) {
        "WhatsApp" -> {
            if (launch(send().setPackage("com.whatsapp")) || launch(send().setPackage("com.whatsapp.w4b")))
                return "Choose a contact in WhatsApp, then tap Send."
        }
        "Messages" -> {
            if (launch(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")).putExtra("sms_body", text)))
                return "Choose a recipient, then send your invitation."
        }
        "Instagram" -> {
            // Keep a paste fallback because Instagram versions handle text shares differently.
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("Zomeal invitation", text))
            if (launch(send().setPackage("com.instagram.android")))
                return "Invitation copied too. Paste it in an Instagram message if needed."
            val app = context.packageManager.getLaunchIntentForPackage("com.instagram.android")
            if (app != null && launch(app)) return "Invitation copied. Open an Instagram chat and paste it."
        }
    }
    return if (launch(Intent.createChooser(send(), "Share Zomeal invitation")))
        "Choose an app to share your invitation."
    else "No sharing app is available on this phone. You can copy your referral code instead."
}
