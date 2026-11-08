package com.chashim.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import kotlin.random.Random

object Palette {
    val bg = Color(0xFF0B0F12)
    val surface = Color(0xFF161D22)
    val text = Color(0xFFE8EDEA)
    val muted = Color(0xFF8A9690)
    val accent = Color(0xFF7FD6A8)
    val warn = Color(0xFFFFB74D)
}

@Composable
fun ChashimTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = darkColorScheme(primary = Palette.accent, background = Palette.bg), content = content)

private data class Drop(val x: Float, val speed: Float, val length: Float, val phase: Float)

/**
 * 영상이 없을 때 쓰는 빗줄기 화면. intensity 0이면 아무것도 그리지 않는다.
 */
@Composable
fun RainCanvas(intensity: Float, modifier: Modifier = Modifier) {
    val drops = remember { List(220) { Drop(Random.nextFloat(), 0.6f + Random.nextFloat() * 0.8f, 0.03f + Random.nextFloat() * 0.05f, Random.nextFloat()) } }
    var time by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) withFrameMillis { time = it } }
    Canvas(modifier) {
        if (intensity <= 0f) return@Canvas
        val t = time / 1000f
        val count = (drops.size * intensity).toInt()
        for (i in 0 until count) {
            val d = drops[i]
            val y = ((d.phase + t * d.speed) % 1f) * (size.height * 1.1f) - size.height * 0.05f
            val x = d.x * size.width - y * 0.08f
            drawLine(
                color = Color.White.copy(alpha = 0.18f + 0.2f * intensity),
                start = Offset(x, y),
                end = Offset(x - d.length * size.height * 0.15f, y + d.length * size.height),
                strokeWidth = 2f,
            )
        }
    }
}
