package com.zomeal.app

import android.provider.Telephony
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap

/** Preserve the actual share destination's identity (including the user's SMS app). */
@Composable
internal fun WalletShareAppIcon(channel: String, fallback: ImageVector, color: Color) {
    val context = LocalContext.current
    val bitmap = remember(context, channel) {
        val packages = when (channel) {
            "WhatsApp" -> listOf("com.whatsapp", "com.whatsapp.w4b")
            "Instagram" -> listOf("com.instagram.android")
            "Messages" -> listOfNotNull(Telephony.Sms.getDefaultSmsPackage(context))
            else -> emptyList()
        }
        packages.firstNotNullOfOrNull { name ->
            runCatching { context.packageManager.getApplicationIcon(name).toBitmap(96, 96).asImageBitmap() }.getOrNull()
        }
    }
    val modifier = Modifier.padding(8.dp).size(26.dp)
    if (bitmap != null) Image(bitmap, contentDescription = null, modifier = modifier)
    else when (channel) {
        "WhatsApp" -> Image(painterResource(R.drawable.ic_share_whatsapp), null, modifier)
        "Instagram" -> Image(painterResource(R.drawable.ic_share_instagram), null, modifier)
        else -> Icon(fallback, contentDescription = null, tint = color, modifier = modifier)
    }
}
