package com.zomeal.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private val LocalNavigationPosition = compositionLocalOf<Animatable<Float, androidx.compose.animation.core.AnimationVector1D>?> { null }

@Composable
internal fun CustomerMotionHost(content: @Composable () -> Unit) {
    val navigationPosition = remember { Animatable(0f) }
    CompositionLocalProvider(LocalNavigationPosition provides navigationPosition, content = content)
}

/** Shared visual position survives destination replacement, but contains no route/business data. */
@Composable
internal fun CustomerNavigationSurface(selected: Int, modifier: Modifier, content: @Composable () -> Unit) {
    val fallback = remember { Animatable(selected.toFloat()) }
    val position = LocalNavigationPosition.current ?: fallback
    LaunchedEffect(selected) { position.animateTo(selected.toFloat(), tween(240, easing = FastOutSlowInEasing)) }
    BoxWithConstraints(modifier) {
        val cell = maxWidth / 5
        Box(Modifier.offset(x = cell * position.value + (cell - 56.dp) / 2, y = 7.dp)
            .size(width = 56.dp, height = 32.dp).background(Color(0xFFE6F4EA), CircleShape))
        content()
    }
}

/** Draw-only motion: never retains outgoing screens or duplicates their network effects.
 * Compose animation clocks respect Android's animator duration scale, including zero.
 */
internal fun Modifier.customerEntrance(identity: Any = Unit): Modifier = composed {
    val progress = remember(identity) { Animatable(0f) }
    LaunchedEffect(progress) { progress.animateTo(1f, tween(260, easing = FastOutSlowInEasing)) }
    graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * 12.dp.toPx()
    }
}

@Composable
internal fun CustomerNavIcon(selected: Boolean, content: @Composable () -> Unit) {
    val scale by animateFloatAsState(if (selected) 1.08f else 1f, tween(180), label = "Navigation selection")
    Box(Modifier.graphicsLayer { scaleX = scale; scaleY = scale }, contentAlignment = Alignment.Center) { content() }
}

/** A stable touch target around the contracting visual button. No synthetic success timer. */
@Composable
internal fun CustomerSubmitButton(
    label: String,
    busy: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && enabled && !busy) .97f else 1f, tween(100), label = "Button press")
    val width by animateFloatAsState(if (busy) 0f else 1f, tween(220), label = "Button loading")
    BoxWithConstraints(modifier.heightIn(min = 52.dp), contentAlignment = Alignment.Center) {
        val buttonWidth = 52.dp + (maxWidth - 52.dp) * width
        Button(
            onClick = { if (!busy && enabled) onClick() },
            enabled = enabled && !busy,
            interactionSource = interaction,
            modifier = Modifier.width(buttonWidth).heightIn(min = 52.dp).graphicsLayer { scaleX = scale; scaleY = scale }.semantics {
                contentDescription = label
                stateDescription = if(busy) "Saving, please wait" else if(enabled) "Ready" else "Unavailable"
                liveRegion = LiveRegionMode.Polite
            },
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF087F43), disabledContainerColor = if(busy) Color(0xFF087F43) else MaterialTheme.colorScheme.onSurface.copy(alpha = .12f)),
            contentPadding = PaddingValues(horizontal = if(busy) 12.dp else 16.dp)
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
            else Text(label)
        }
    }
}

@Composable
internal fun CustomerSuccessMark() {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(320, easing = FastOutSlowInEasing)) }
    Surface(
        modifier = Modifier.size(68.dp).graphicsLayer {
            scaleX = .8f + .2f * progress.value
            scaleY = scaleX
            alpha = progress.value
        }.semantics { liveRegion = LiveRegionMode.Polite },
        shape = CircleShape, color = Color(0xFFE6F4EA)
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.Check, "Saved successfully", tint = Color(0xFF087F43), modifier = Modifier.size(34.dp)) }
    }
}
