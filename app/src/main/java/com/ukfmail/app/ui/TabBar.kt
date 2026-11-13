package com.ukfmail.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.floor

/**
 * Floating bar with the four sections. The selection is one liquid blob that follows the pages: while
 * moving, its front edge runs ahead, the back edge lags, and the stretched blob gets thinner, like a
 * drop being pulled. Its color pours from indigo on the left to pink on the right, and each icon
 * turns white as the blob flows over it.
 */
@Composable
fun TabBar(tab: Tab, onTab: (Tab) -> Unit, mailBadge: Int, modifier: Modifier = Modifier) =
    TabBar(tab.ordinal.toFloat(), onTab, mailBadge, modifier)

/** [position] is the pager position: 1.5 is halfway between the second and third tab. */
@Composable
fun TabBar(position: Float, onTab: (Tab) -> Unit, mailBadge: Int, modifier: Modifier = Modifier) {
    val dark = isDark()
    val shape = RoundedCornerShape(30.dp)
    val count = Tab.entries.size
    // Between two tabs the edge in the direction of travel runs ahead and the other lags, so the
    // blob stretches mid-way and pulls back together as it arrives. Works the same in both directions.
    val p = position.coerceIn(0f, count - 1f)
    val i = floor(p)
    val f = p - i
    val left = i + f * f * f
    val right = i + 1f + (1f - (1f - f) * (1f - f) * (1f - f))
    val center = (left + right) / 2

    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .widthIn(max = 560.dp) // a tablet doesn't need the tabs a hand-width apart
            .fillMaxWidth()
            .height(64.dp)
            .shadow(22.dp, shape, ambientColor = Indigo.copy(alpha = 0.5f), spotColor = Indigo.copy(alpha = 0.5f))
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, Color.White.copy(alpha = if (dark) 0.07f else 0.7f), shape)
            .padding(6.dp),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val slot = size.width / count
            val inset = 2.dp.toPx()
            val l = left * slot + inset
            val r = right * slot - inset
            // 0 at rest, up to 1 when the blob spans two tabs: the more it stretches, the thinner it gets.
            val stretch = ((r - l) / slot - 1f).coerceIn(0f, 1f)
            val h = size.height * (1f - 0.16f * stretch)
            val top = (size.height - h) / 2
            val pour = Brush.horizontalGradient(listOf(Indigo, Violet, Pink), startX = 0f, endX = size.width)
            // A soft halo a little larger than the blob.
            val halo = 4.dp.toPx()
            drawRoundRect(
                pour, Offset(l - halo, top - halo), Size(r - l + 2 * halo, h + 2 * halo),
                CornerRadius((h + 2 * halo) / 2), alpha = 0.18f,
            )
            drawRoundRect(pour, Offset(l, top), Size(r - l, h), CornerRadius(minOf(h / 2, 26.dp.toPx())))
        }
        Row(Modifier.fillMaxSize()) {
            Tab.entries.forEachIndexed { i, t ->
                // White while the blob is under this tab's middle, fading at the blob's edges.
                val mid = i + 0.5f
                val inside = ((minOf(mid - left, right - mid)) * 4f).coerceIn(0f, 1f)
                val color = lerp(muted, Color.White, inside)
                // The icon grows a little when the blob is centered on it.
                val cover = (1f - abs(center - mid)).coerceIn(0f, 1f)
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(24.dp))
                        .clickable(remember { MutableInteractionSource() }, indication = null) { onTab(t) },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.graphicsLayer {
                            val s = 1f + 0.08f * cover
                            scaleX = s; scaleY = s
                        },
                    ) {
                        Box {
                            Icon(t.icon, t.label, tint = color, modifier = Modifier.size(23.dp))
                            if (t == Tab.MAIL && mailBadge > 0) {
                                Box(
                                    Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-2).dp).size(9.dp).clip(CircleShape)
                                        .background(if (inside > 0.5f) Brush.linearGradient(listOf(Color.White, Color.White)) else Brush.linearGradient(listOf(Indigo, Pink)))
                                )
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            // The bar has a fixed height, so labels grow with the phone's font size only up to 15 %,
                            // like Android's own navigation bars.
                            t.label, color = color, maxLines = 1, softWrap = false,
                            fontSize = (11f * minOf(LocalDensity.current.fontScale, 1.15f) / LocalDensity.current.fontScale).sp,
                            lineHeight = (14f * minOf(LocalDensity.current.fontScale, 1.15f) / LocalDensity.current.fontScale).sp,
                            fontWeight = if (inside > 0.5f) FontWeight.SemiBold else FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}
