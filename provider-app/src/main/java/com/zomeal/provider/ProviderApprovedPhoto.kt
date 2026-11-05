package com.zomeal.provider

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ImageNotSupported
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

@Composable
internal fun ProviderApprovedPhoto(repository: SupabaseProviderRepository, path: String, label: String, modifier: Modifier, compact: Boolean = false) {
    val source = path.takeUnless { it == "null" }.orEmpty()
    var bitmap by remember(source) { mutableStateOf<Bitmap?>(null) }
    var loading by remember(source) { mutableStateOf(source.isNotBlank()) }
    var attempt by remember(source) { mutableIntStateOf(0) }
    val requests = remember { OperationsRequestGate() }
    DisposableEffect(Unit) { onDispose { requests.invalidate() } }
    LaunchedEffect(source, attempt) {
        val request = requests.next()
        loading = source.isNotBlank()
        if (source.isNotBlank()) repository.approvedMedia(source) { result ->
            if (requests.accepts(request)) { bitmap = result; loading = false }
        }
    }
    Box(modifier.background(Color(0xFFF0F7F2)), contentAlignment = Alignment.Center) {
        when {
            bitmap != null -> Image(bitmap!!.asImageBitmap(), label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            loading -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = Color(0xFF087F43))
            source.isBlank() -> if (compact) Icon(Icons.Outlined.ImageNotSupported, "No published $label photo", tint = Color(0xFF087F43)) else Text("No published photo", fontSize = ProviderType.caption, modifier = Modifier.padding(8.dp))
            compact -> IconButton(onClick = { attempt++ }) { Icon(Icons.Outlined.Refresh, "Retry $label photo", tint = Color(0xFF087F43)) }
            else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Photo couldn't load", fontSize = ProviderType.caption)
                TextButton(onClick = { attempt++ }) { Text("Retry photo") }
            }
        }
    }
}
