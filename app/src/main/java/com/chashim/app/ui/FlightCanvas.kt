package com.chashim.app.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.nativeCanvas
import com.chashim.app.engine.flight.FlightHud
import com.chashim.app.engine.flight.LEAD_SEC
import com.chashim.app.engine.flight.Mark
import com.chashim.app.engine.flight.STAR_FALL_SEC
import com.chashim.app.engine.flight.Segment
import com.chashim.app.engine.flight.WIND_HALF_V
import com.chashim.app.engine.flight.WIND_HALF_X
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** 조준점이 움직이는 범위: 화면 가로의 ±40% */
const val AIM_RANGE = 0.4f
/** 높이 0(위) ~ 1(아래)가 화면 세로 어디에 오는지 */
private const val TOP_Y = 0.36f
private const val SPAN_Y = 0.34f

private val Gold = Color(0xFFFFC94A)
private val Wind = Color(0xFFA8F0FF)
private val Coral = Color(0xFFFF6B6B)

/** 화면 쪽 시간 기준점. 엔진은 20Hz로 알려 주고, 화면은 그 사이를 이어서 매 프레임 그린다 */
private class Clock {
    var segT = 0f
    var at = 0L
    var aim = 0f
    var aimV = 0f
    var glow = 0f
}

/**
 * 비행 화면 위에 그리는 것: 바람길, 속도감 줄기, 먹구름, 별빛, 조준점, 바람 세기.
 * 바람길·먹구름은 멀리 지평선에서 원근감 있게 다가온다.
 */
@Composable
fun FlightCanvas(hud: FlightHud, modifier: Modifier = Modifier) {
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) withFrameNanos { frame = it } }
    val clock = remember { Clock() }
    LaunchedEffect(hud.segT, hud.segment?.id) {
        clock.segT = hud.segT
        clock.at = System.nanoTime()
    }
    val text = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
    }

    Canvas(modifier) {
        frame // 매 프레임 다시 그린다
        val seg = hud.segment ?: return@Canvas
        val lt = clock.segT + ((System.nanoTime() - clock.at) / 1e9f).coerceIn(0f, 0.15f)
        clock.aim += (hud.aim - clock.aim) * 0.3f
        clock.aimV += (hud.aimV - clock.aimV) * 0.3f
        clock.glow += ((if (hud.inWind) 1f else 0f) - clock.glow) * 0.12f

        val w = size.width
        val h = size.height
        val vp = Offset(w / 2, h * seg.horizon)  // 영상 속 지평선의 소실점
        fun sx(x: Float) = w / 2 + x * w * AIM_RANGE
        fun sy(v: Float) = h * (TOP_Y + SPAN_Y * v)
        fun project(x: Float, v: Float, s: Float) = Offset(vp.x + (sx(x) - vp.x) * s, vp.y + (sy(v) - vp.y) * s)

        speedLines(lt, vp, 0.5f + hud.windLevel * 0.25f)

        // 바람길
        val hidden = seg.isHidden(lt)
        windRibbon(seg, lt, ::project, w, h, if (hidden) 0f else 1f, clock.glow)

        // 먹구름
        seg.clouds.forEachIndexed { i, c ->
            val d = (c.t - lt) / LEAD_SEC
            if (d > 1.2f || d < -0.4f) return@forEachIndexed
            val s = perspective(d)
            val fadeIn = ((1.2f - d) / 0.2f).coerceIn(0f, 1f)
            val fadeOut = if (d < 0) (1f + d / 0.4f).coerceIn(0f, 1f) else 1f
            val halfW = c.w / 2 * w * AIM_RANGE * s
            val hit = hud.cloudHit.getOrNull(i)
            val danger = hit == null && d in 0f..0.7f && abs(clock.aim - c.x) <= c.w / 2 &&
                abs(clock.aimV - c.v) <= com.chashim.app.engine.flight.CLOUD_HALF_V
            stormCloud(project(c.x, c.v, s), halfW, fadeIn * fadeOut, lt, i, danger, hit == true)
        }

        // 별빛
        seg.stars.forEachIndexed { i, st ->
            val p = (lt - (st.t - STAR_FALL_SEC)) / STAR_FALL_SEC
            val mark = hud.starMarks.getOrNull(i) ?: Mark.PENDING
            val target = Offset(sx(st.x), sy(st.v))
            if (mark == Mark.HIT) {
                val age = lt - (hud.starAt.getOrNull(i) ?: st.t)
                if (age < 0.9f) {
                    val color = if (st.big) Color.White else Gold
                    sparks(target, age, color, if (st.big) 22 else 12, h * (if (st.big) 0.28f else 0.16f))
                    floatText(if (st.big) "+150" else "+50", target - Offset(0f, h * 0.06f + age * h * 0.1f), 1f - age / 0.9f, text, st.big)
                }
                return@forEachIndexed
            }
            if (p < 0f || p > 1.5f) return@forEachIndexed
            val x = target.x + sin(lt * 2.2f + i) * 5f
            val y = -h * 0.06f + (target.y + h * 0.06f) * p
            val alpha = if (p > 1f) (1f - (p - 1f) / 0.5f).coerceIn(0f, 1f) * 0.6f else (p / 0.12f).coerceIn(0f, 1f)
            fallingStar(Offset(x, y), st.big, alpha, lt + i)
        }

        val aimPos = Offset(sx(clock.aim), sy(clock.aimV))
        reticle(aimPos, clock.glow)
        windMeter(aimPos + Offset(0f, 62f), hud.windLevel)
        altitudeBar(Offset(w - 46f, sy(0f)), sy(1f) - sy(0f), clock.aimV, hud.diving)
    }
}

/** d: 도달까지 남은 비율 (1 = 가장 멀리, 0 = 도달, 음수 = 지나감) → 화면 크기 비율 */
private fun perspective(d: Float): Float = if (d >= 0) 1f / (1f + 5f * d) else 1f + (-d) * 3.5f

/**
 * 바람길: 지평선에서 다가오는 오로라 같은 빛줄기 다발.
 * 딱딱한 면 없이 가는 빛줄기 여러 가닥을 겹쳐 그린다. 가운데는 밝고 가장자리로 갈수록 옅어진다.
 * 가닥마다 조금씩 물결치며, 조준점이 바람길 안에 있으면 더 밝아진다.
 */
private fun DrawScope.windRibbon(
    seg: Segment, lt: Float, project: (Float, Float, Float) -> Offset, w: Float, h: Float, visible: Float, glow: Float,
) {
    if (visible <= 0f) return
    val n = 36
    val centers = ArrayList<Offset>(n + 1)
    val halves = FloatArray(n + 1)
    for (k in 0..n) {
        val d = k / n.toFloat()                // 0 = 지금, 1 = 멀리
        val (x, v) = seg.windAt(lt + d * LEAD_SEC)
        val s = perspective(d)
        centers += project(x, v, s)
        halves[k] = WIND_HALF_X * w * AIM_RANGE * s
    }
    fun fade(d: Float) = (1f - d) * (1f - d) * 0.85f + 0.15f * (1f - d)   // 멀수록 옅게
    val bright = 0.6f + 0.4f * glow

    // 1) 바탕: 밝은 배경(파도·구름)에서도 보이도록 아주 옅은 그림자를 깔고, 그 위에 하늘빛 안개
    for (k in 0 until n) {
        val d = k / n.toFloat()
        drawLine(
            Color(0xFF06222E).copy(alpha = 0.16f * fade(d) * visible),
            centers[k], centers[k + 1], strokeWidth = halves[k] * 2.6f, cap = StrokeCap.Butt,
        )
        drawLine(
            Wind.copy(alpha = 0.12f * fade(d) * visible * bright),
            centers[k], centers[k + 1], strokeWidth = halves[k] * 2.0f, cap = StrokeCap.Butt,
        )
    }
    // 2) 빛줄기 가닥
    val strands = 11
    for (j in 0 until strands) {
        val f = j / (strands - 1f) * 2f - 1f         // -1 ~ 1 (가장자리 ~ 가운데 ~ 가장자리)
        val profile = 1f - f * f                       // 가운데가 밝다
        val phase = j * 1.7f
        var prev: Offset? = null
        for (k in 0..n) {
            val d = k / n.toFloat()
            val wave = sin(d * 9f - lt * 2.4f + phase) * halves[k] * 0.12f
            val p = centers[k] + Offset(f * halves[k] * 0.9f, wave)
            prev?.let { q ->
                val a = (0.18f + 0.5f * profile) * fade(d) * visible * bright
                drawLine(lerp(Wind, Color.White, profile).copy(alpha = a), q, p, strokeWidth = 1f + 2.6f * (1f - d) * (0.4f + 0.6f * profile), cap = StrokeCap.Round)
            }
            prev = p
        }
    }
    // 3) 흘러오는 빛 알갱이: 멀리서 가까이로
    for (i in 0 until 22) {
        val seed = (i * 0.618034f) % 1f
        val f = ((i * 0.382f) % 1f) * 2f - 1f
        val d = 1f - ((lt * 0.5f + seed) % 1f)
        val k = (d * n).toInt().coerceIn(0, n - 1)
        val c = centers[k] + (centers[k + 1] - centers[k]) * (d * n - k)
        val p = c + Offset(f * halves[k] * 0.8f, 0f)
        val a = (1f - f * f) * fade(d) * 0.8f * visible * bright
        drawCircle(
            Brush.radialGradient(listOf(Color.White.copy(alpha = a), Color.Transparent), center = p, radius = 3f + 9f * (1f - d)),
            radius = 3f + 9f * (1f - d), center = p,
        )
    }
}

/** 지평선에서 퍼져 나가는 옅은 바람 줄기 */
private fun DrawScope.speedLines(t: Float, vp: Offset, strength: Float) {
    val maxD = size.width * 0.75f
    for (i in 0 until 34) {
        val seed = (i * 0.618034f) % 1f
        val angle = (i * 2.399963f) % (2 * PI.toFloat())
        val prog = ((t * 0.45f * (0.8f + strength * 0.4f) + seed) % 1f)
        val d0 = prog * prog * maxD + size.height * 0.08f
        val len = 20f + prog * 160f
        val dir = Offset(cos(angle), sin(angle) * 0.75f)
        val a = (prog * 0.14f * strength).coerceAtMost(0.22f)
        drawLine(Color.White.copy(alpha = a), vp + dir * d0, vp + dir * (d0 + len), strokeWidth = 1.5f + prog * 2f, cap = StrokeCap.Round)
    }
}

/** 부풀어 오른 폭풍 구름 + 가끔 번개 */
private fun DrawScope.stormCloud(c: Offset, halfW: Float, alpha: Float, t: Float, idx: Int, danger: Boolean, hit: Boolean) {
    if (alpha <= 0f || halfW < 2f) return
    fun rnd(n: Int) = ((sin((n + idx * 13) * 12.9898f) * 43758.547f) % 1f + 1f) % 1f
    if (danger || hit) {
        val pulse = 0.55f + 0.45f * sin(t * 10f)
        drawCircle(
            Brush.radialGradient(listOf(Coral.copy(alpha = 0.35f * alpha * pulse), Color.Transparent), center = c, radius = halfW * 1.6f),
            radius = halfW * 1.6f, center = c,
        )
    }
    scale(1f, 0.5f, pivot = c) {
        drawCircle(
            Brush.radialGradient(
                0f to Color(0xFF1E232D).copy(alpha = 0.45f * alpha),
                0.55f to Color(0xFF1E232D).copy(alpha = 0.2f * alpha),
                1f to Color.Transparent,
                center = c, radius = halfW * 1.5f,
            ),
            radius = halfW * 1.5f, center = c,
        )
    }
    for (j in 0 until 18) {
        val u = rnd(j) - 0.5f
        val o = Offset(u * 2.0f * halfW, -(1f - 4f * u * u) * halfW * 0.35f * rnd(j + 30) + halfW * 0.04f)
        val rad = halfW * (0.32f + rnd(j + 60) * 0.3f) * (1.1f - abs(u))
        drawCircle(
            Brush.radialGradient(
                0f to Color(0xFF353C4A).copy(alpha = 0.8f * alpha),
                0.4f to Color(0xFF2A303C).copy(alpha = 0.6f * alpha),
                0.7f to Color(0xFF222833).copy(alpha = 0.25f * alpha),
                1f to Color.Transparent,
                center = c + o, radius = rad,
            ),
            radius = rad, center = c + o,
        )
    }
    for (j in 0 until 8) {
        val u = rnd(j + 90) - 0.5f
        val o = Offset(u * 1.5f * halfW, -halfW * (0.18f + (1f - 4f * u * u) * 0.25f))
        val rad = halfW * (0.22f + rnd(j + 150) * 0.16f)
        drawCircle(
            Brush.radialGradient(
                0f to Color(0xFFB8C0CF).copy(alpha = 0.28f * alpha),
                0.6f to Color(0xFF8E97A8).copy(alpha = 0.1f * alpha),
                1f to Color.Transparent,
                center = c + o, radius = rad,
            ),
            radius = rad, center = c + o,
        )
    }
    val flick = sin(t * 7.3f + idx * 2.1f)
    if (flick > 0.9f || hit) {
        val path = Path()
        var p = c + Offset((rnd(200) - 0.5f) * halfW, -halfW * 0.1f)
        path.moveTo(p.x, p.y)
        for (k in 0 until 5) {
            p += Offset((rnd(210 + k + (t * 3).toInt()) - 0.5f) * halfW * 0.35f, halfW * 0.16f)
            path.lineTo(p.x, p.y)
        }
        drawPath(path, Color(0xFFBFD8FF).copy(alpha = 0.35f * alpha), style = Stroke(halfW * 0.06f, cap = StrokeCap.Round))
        drawPath(path, Color.White.copy(alpha = 0.95f * alpha), style = Stroke((halfW * 0.018f).coerceAtLeast(1.5f), cap = StrokeCap.Round))
    }
}

/** 꼬리를 그리며 떨어지는 별빛 */
private fun DrawScope.fallingStar(p: Offset, big: Boolean, alpha: Float, t: Float) {
    if (alpha <= 0f) return
    val core = size.height * (if (big) 0.036f else 0.024f)
    val color = if (big) Color(0xFFFFE6A0) else Gold
    val tail = size.height * (if (big) 0.2f else 0.13f)
    drawLine(
        Brush.verticalGradient(listOf(Color.Transparent, color.copy(alpha = 0.7f * alpha)), startY = p.y - tail, endY = p.y),
        Offset(p.x, p.y - tail), p, strokeWidth = core * 0.45f, cap = StrokeCap.Round,
    )
    drawCircle(
        Brush.radialGradient(listOf(color.copy(alpha = 0.55f * alpha), Color.Transparent), center = p, radius = core * 3.2f),
        radius = core * 3.2f, center = p,
    )
    rotate(t * 70f, p) {
        val path = Path()
        for (k in 0 until 8) {
            val a = k * (PI.toFloat() / 4) - PI.toFloat() / 2
            val rr = if (k % 2 == 0) core * 1.6f else core * 0.32f
            val q = p + Offset(cos(a), sin(a)) * rr
            if (k == 0) path.moveTo(q.x, q.y) else path.lineTo(q.x, q.y)
        }
        path.close()
        drawPath(path, Color.White.copy(alpha = alpha))
    }
    drawCircle(Color.White.copy(alpha = alpha), radius = core * 0.35f, center = p)
}

/** 받았을 때 흩어지는 빛 입자 */
private fun DrawScope.sparks(c: Offset, age: Float, color: Color, count: Int, reach: Float) {
    val life = 0.7f
    if (age < 0f || age > life) return
    val k = age / life
    val ease = 1f - (1f - k) * (1f - k)
    drawCircle(color.copy(alpha = 0.6f * (1f - k)), radius = 10f + reach * 0.6f * ease, center = c, style = Stroke(3f * (1f - k) + 1f))
    for (i in 0 until count) {
        val a = i * (2 * PI.toFloat() / count) + i * 0.37f
        val dist = reach * ease * (0.6f + (i % 3) * 0.2f)
        drawCircle(color.copy(alpha = 1f - k), radius = 4f * (1f - k) + 1.5f, center = c + Offset(cos(a), sin(a)) * dist)
    }
}

private fun DrawScope.floatText(s: String, p: Offset, alpha: Float, paint: Paint, big: Boolean) {
    paint.textSize = if (big) 64f else 46f
    paint.color = android.graphics.Color.argb((alpha.coerceIn(0f, 1f) * 255).toInt(), 255, if (big) 245 else 212, if (big) 200 else 90)
    paint.setShadowLayer(12f, 0f, 2f, android.graphics.Color.argb((alpha * 160).toInt().coerceIn(0, 255), 0, 0, 0))
    drawContext.canvas.nativeCanvas.drawText(s, p.x, p.y, paint)
}

/** 조준점: 네 개의 짧은 호 + 가운데 점. 바람길 안에 있으면 하늘빛으로 빛난다 */
private fun DrawScope.reticle(c: Offset, glow: Float) {
    val tint = lerp(Color.White, Wind, glow)
    drawCircle(
        Brush.radialGradient(listOf(tint.copy(alpha = 0.16f + 0.2f * glow), Color.Transparent), center = c, radius = 70f + 30f * glow),
        radius = 70f + 30f * glow, center = c,
    )
    val r = 32f
    for (k in 0 until 4) {
        drawArc(
            tint.copy(alpha = 0.95f), startAngle = -35f + k * 90f, sweepAngle = 70f, useCenter = false,
            topLeft = c - Offset(r, r), size = Size(r * 2, r * 2), style = Stroke(3.5f, cap = StrokeCap.Round),
        )
    }
    drawCircle(tint, radius = 4.5f, center = c)
}

/** 조준점 아래 바람 세기 막대 3개 (실제 차 바람 +0~3단과 같다) */
private fun DrawScope.windMeter(c: Offset, level: Int) {
    for (i in 0 until 3) {
        val on = level > i
        val x = c.x + (i - 1) * 18f
        val bh = 10f + i * 7f
        drawRoundRect(
            if (on) Wind else Color.White.copy(alpha = 0.25f),
            topLeft = Offset(x - 5f, c.y + 24f - bh), size = Size(10f, bh),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f, 3f),
        )
    }
}

/** 오른쪽 가장자리 높이 표시: 브레이크를 밟으면 아래로 */
private fun DrawScope.altitudeBar(top: Offset, height: Float, v: Float, diving: Boolean) {
    drawLine(Color.White.copy(alpha = 0.25f), top, top + Offset(0f, height), strokeWidth = 4f, cap = StrokeCap.Round)
    val p = top + Offset(0f, height * v)
    drawCircle(if (diving) Gold else Color.White, radius = 10f, center = p)
    // 아래 화살표
    val a = top + Offset(0f, height + 26f)
    val path = Path().apply { moveTo(a.x - 9f, a.y - 6f); lineTo(a.x + 9f, a.y - 6f); lineTo(a.x, a.y + 6f); close() }
    drawPath(path, (if (diving) Gold else Color.White).copy(alpha = if (diving) 1f else 0.4f))
}

private fun lerp(a: Color, b: Color, t: Float) = Color(
    a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, a.alpha + (b.alpha - a.alpha) * t,
)
