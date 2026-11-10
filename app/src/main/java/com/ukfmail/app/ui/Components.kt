package com.ukfmail.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

val Pink = Color(0xFFEC4899)
val Rose = Color(0xFFF43F5E)
val Sky = Color(0xFF38BDF8)

/** Soft colored light behind headers. [phase] (0..1) slowly moves the blobs when animated. */
@Composable
fun Glow(modifier: Modifier, phase: Float = 0f, strength: Float = 1f) {
    val dark = isDark()
    val a = (if (dark) 0.42f else 0.30f) * strength
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val t = phase * 2 * Math.PI
        fun blob(c: Color, x: Float, y: Float, r: Float) {
            val center = Offset(x, y)
            drawCircle(Brush.radialGradient(listOf(c, Color.Transparent), center = center, radius = r), r, center)
        }
        blob(Indigo.copy(alpha = a), w * (0.12f + 0.06f * cos(t).toFloat()), h * (0.18f + 0.05f * sin(t).toFloat()), w * 0.75f)
        blob(Violet.copy(alpha = a), w * (0.88f + 0.05f * sin(t * 1.3).toFloat()), h * (0.08f + 0.04f * cos(t).toFloat()), w * 0.62f)
        blob(Pink.copy(alpha = a * 0.75f), w * (0.62f + 0.07f * cos(t * 0.8).toFloat()), h * (0.78f + 0.05f * sin(t * 1.1).toFloat()), w * 0.55f)
        blob(Sky.copy(alpha = a * 0.45f), w * (0.05f + 0.04f * sin(t * 0.7).toFloat()), h * 0.95f, w * 0.4f)
    }
}

/** [Glow] that drifts slowly, for full-screen backgrounds. */
@Composable
fun AnimatedGlow(modifier: Modifier, strength: Float = 1f) {
    val tr = rememberInfiniteTransition(label = "glow")
    val phase by tr.animateFloat(0f, 1f, infiniteRepeatable(tween(14_000, easing = LinearEasing), RepeatMode.Restart), label = "phase")
    Glow(modifier, phase, strength)
}

/** Round translucent button used over the glow. */
@Composable
fun GlassButton(icon: ImageVector, description: String, onClick: () -> Unit, size: Dp = 44.dp) {
    val dark = isDark()
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = if (dark) 0.45f else 0.65f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = if (dark) 0.08f else 0.55f)),
        modifier = Modifier.size(size),
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, description, Modifier.size(22.dp)) }
    }
}

/** Main call-to-action: brand gradient with a colored shadow. */
@Composable
fun GradientButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: RoundedCornerShape = RoundedCornerShape(18.dp),
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier
            .alpha(if (enabled) 1f else 0.45f)
            .shadow(if (enabled) 14.dp else 0.dp, shape, ambientColor = Indigo, spotColor = Violet)
            .clip(shape)
            .background(Brush.linearGradient(listOf(Indigo, Violet, Pink.copy(alpha = 0.9f))))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        content = content,
    )
}

@Composable
fun ComposeFab(expanded: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(22.dp)
    Row(
        modifier
            .shadow(18.dp, shape, ambientColor = Indigo, spotColor = Violet)
            .clip(shape)
            .background(Brush.linearGradient(listOf(Indigo, Violet, Pink)))
            .clickable(onClick = onClick)
            .height(60.dp)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Edit, "Napísať", tint = Color.White)
        AnimatedVisibility(expanded, enter = fadeIn() + expandHorizontally(), exit = fadeOut() + shrinkHorizontally()) {
            Row {
                Spacer(Modifier.width(10.dp))
                Text("Napísať", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            }
        }
    }
}

/** Small rounded label; [active] fills it with the brand gradient. */
@Composable
fun Pill(text: String, active: Boolean, onClick: (() -> Unit)? = null, leading: (@Composable () -> Unit)? = null) {
    val shape = RoundedCornerShape(50)
    val base = Modifier
        .clip(shape)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    val bg = if (active) base.background(Brush.linearGradient(listOf(Indigo, Violet)))
    else base.background(MaterialTheme.colorScheme.surfaceContainerHigh)
    Row(bg.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (leading != null) {
            leading(); Spacer(Modifier.width(6.dp))
        }
        Text(
            text,
            color = if (active) Color.White else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            fontSize = 13.5.sp,
        )
    }
}

@Composable
fun Dot(color: Color, size: Dp = 8.dp) = Box(Modifier.size(size).clip(CircleShape).background(color))

/** Thin full-width gradient hairline. */
@Composable
fun GradientRule(modifier: Modifier = Modifier) = Box(
    modifier.fillMaxWidth().height(1.dp)
        .background(Brush.horizontalGradient(listOf(Color.Transparent, Indigo.copy(alpha = 0.35f), Pink.copy(alpha = 0.3f), Color.Transparent)))
)
