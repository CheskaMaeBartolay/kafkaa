package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.ui.theme.CyberEmerald
import com.example.ui.theme.StreamingCyan
import com.example.ui.theme.SurfaceVariantDark

@Composable
fun AudioWaveformMeter(
    audioLevel: Float,
    isMicActive: Boolean,
    modifier: Modifier = Modifier
) {
    val barCount = 12
    val infiniteTransition = rememberInfiniteTransition(label = "audio_bars")
    val animatedPhase by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "phase"
    )

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceVariantDark)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (i in 0 until barCount) {
            val barFactor = if (i % 2 == 0) 1.0f else 0.7f
            val baseLevel = if (isMicActive) (audioLevel * barFactor).coerceIn(0.1f, 1f) else 0.08f
            val dynamicHeight = (baseLevel * 24f * if (isMicActive) animatedPhase else 1f).coerceIn(4f, 26f)

            val barColor = when {
                !isMicActive -> Color(0xFF475569)
                i < 8 -> CyberEmerald
                i < 10 -> StreamingCyan
                else -> Color(0xFFF59E0B)
            }

            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(dynamicHeight.dp)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(barColor)
            )
        }
    }
}
