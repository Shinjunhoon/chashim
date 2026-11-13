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
import com.chashim.app.engine.flight.Mark
import com.chashim.app.engine.flight.RING_LEAD_SEC
import com.chashim.app.engine.flight.STAR_FALL_SEC
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** 조준점이 움직이는 범위: 화면 가로의 ±40% */
const val AIM_RANGE = 0.4f

private val Gold = Color(0xFFFFC94A)
private val GoldDeep = Color(0xFFE08A1E)
private val Mint = Color(0xFF7CF2C2)
private val Coral = Color(0xFFFF6B6B)

/** 화면 쪽 시간 기준점. 엔진은 20Hz로 알려 주고, 화면은 그 사이를 이어서 매 프레임 그린다 */
private class Clock {
    var segId: String? = null
    var segT = 0f
    var at = 0L
    var aim = 0f
    var lastFrame = 0L
}

/**
 * 비행 화면 위에 그리는 것: 속도감 줄기, 먹구름, 링, 별빛, 조준점.
 * 멀리 지평선에서 다가오는 원근감으로 그린다.
 */
@Composable
fun FlightCanvas(hud: FlightHud, modifier: Modifier = Modifier) {
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) withFrameNanos { frame = it } }
    val clock = remember { Clock() }
    LaunchedEffect(hud.segT, hud.segment?.id) {
        clock.segId = hud.segment?.id
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
        val speed = if (hud.hover) 0.5f else 1f
        // 엔진 값 사이를 이어 붙인 지금 시각 (최대 0.15초만 앞서 간다)
        val lt = clock.segT + ((System.nanoTime() - clock.at) / 1e9f).coerceIn(0f, 0.15f) * speed
        // 조준점도 프레임마다 부드럽게 따라간다
        clock.aim += (hud.aim - clock.aim) * 0.3f

        val w = size.width
        val h = size.height
        val vp = Offset(w / 2, h * 0.38f)        // 지평선 소실점
        val catchY = h * 0.52f                    // 조준점 높이
        fun sx(x: Float) = w / 2 + x * w * AIM_RANGE

        speedLines(lt, vp, if (hud.hover) 0.4f else 1f)

        // 먹구름
        seg.clouds.forEachIndexed { i, c ->
            val d = (c.t - lt) / RING_LEAD_SEC
            if (d > 1.2f || d < -0.4f) return@forEachIndexed
            val s = perspective(d)
            val fadeIn = ((1.2f - d) / 0.2f).coerceIn(0f, 1f)
            val fadeOut = if (d < 0) (1f + d / 0.4f).coerceIn(0f, 1f) else 1f
            val center = Offset(vp.x + (sx(c.x) - vp.x) * s, vp.y + (catchY - vp.y) * s)
            val halfW = c.w / 2 * w * AIM_RANGE * s
            val hit = hud.cloudHit.getOrNull(i)
            // 충돌 경로 경고: 조준점이 구름 범위 안이면 붉은 기운
            val danger = hit == null && d in 0f..0.7f && kotlin.math.abs(clock.aim - c.x) <= c.w / 2
            stormCloud(center, halfW, fadeIn * fadeOut, lt, i, danger, hit == true)
        }

        // 링
        seg.rings.forEachIndexed { i, r ->
            val d = (r.t - lt) / RING_LEAD_SEC
            if (d > 1.25f || d < -0.35f) return@forEachIndexed
            val s = perspective(d)
            val fadeIn = ((1.25f - d) / 0.25f).coerceIn(0f, 1f)
            val fadeOut = if (d < 0) (1f + d / 0.35f).coerceIn(0f, 1f) else 1f
            val center = Offset(vp.x + (sx(r.x) - vp.x) * s, vp.y + (catchY - vp.y) * s)
            val radius = h * 0.15f * s
            val mark = hud.ringMarks.getOrNull(i) ?: Mark.PENDING
            gateRing(center, radius, fadeIn * fadeOut, mark, lt)
            if (mark == Mark.HIT) {
                val age = lt - (hud.ringAt.getOrNull(i) ?: r.t)
                sparks(Offset(sx(r.x), catchY), age, Mint, 18, h * 0.32f)
            }
        }

        // 별빛
        seg.stars.forEachIndexed { i, st ->
            val p = (lt - (st.t - STAR_FALL_SEC)) / STAR_FALL_SEC
            val mark = hud.starMarks.getOrNull(i) ?: Mark.PENDING
            val x = sx(st.x) + sin(lt * 2.2f + i) * 5f
            if (mark == Mark.HIT) {
                val age = lt - (hud.starAt.getOrNull(i) ?: st.t)
                if (age < 0.9f) {
                    val color = if (st.big) Color.White else Gold
                    sparks(Offset(sx(st.x), catchY), age, color, if (st.big) 22 else 12, h * (if (st.big) 0.28f else 0.16f))
                    floatText(if (st.big) "+150" else "+50", Offset(sx(st.x), catchY - h * 0.06f - age * h * 0.1f), (1f - age / 0.9f), text, st.big)
                }
                return@forEachIndexed
            }
            if (p < 0f || p > 1.5f) return@forEachIndexed
            val y = -h * 0.06f + (catchY + h * 0.06f) * p
            val alpha = if (p > 1f) (1f - (p - 1f) / 0.5f).coerceIn(0f, 1f) * 0.6f else (p / 0.12f).coerceIn(0f, 1f)
            fallingStar(Offset(x, y), st.big, alpha, lt + i)
        }

        reticle(Offset(sx(clock.aim), catchY))
    }
}

/** d: 도달까지 남은 비율 (1 = 가장 멀리, 0 = 도달, 음수 = 지나감) → 화면 크기 비율 */
private fun perspective(d: Float): Float = if (d >= 0) 1f / (1f + 5f * d) else 1f + (-d) * 3.5f

/** 지평선에서 퍼져 나가는 옅은 바람 줄기 */
private fun DrawScope.speedLines(t: Float, vp: Offset, strength: Float) {
    val maxD = size.width * 0.75f
    for (i in 0 until 34) {
        val seed = (i * 0.618034f) % 1f
        val angle = (i * 2.399963f) % (2 * PI.toFloat())
        val prog = ((t * 0.45f + seed) % 1f)
        val d0 = prog * prog * maxD + size.height * 0.08f
        val len = 20f + prog * 160f
        val dir = Offset(cos(angle), sin(angle) * 0.75f)
        val a = (prog * 0.16f * strength).coerceAtMost(0.16f)
        drawLine(
            Color.White.copy(alpha = a),
            vp + dir * d0, vp + dir * (d0 + len),
            strokeWidth = 1.5f + prog * 2f, cap = StrokeCap.Round,
        )
    }
}

/** 빛나는 금빛 관문 */
private fun DrawScope.gateRing(c: Offset, r: Float, alpha: Float, mark: Mark, t: Float) {
    if (alpha <= 0f || r < 2f) return
    val base = when (mark) { Mark.PENDING -> Gold; Mark.HIT -> Mint; Mark.MISS -> Coral }
    val deep = when (mark) { Mark.PENDING -> GoldDeep; Mark.HIT -> Color(0xFF2FB98A); Mark.MISS -> Color(0xFFB83A3A) }
    val band = (r * 0.12f).coerceAtLeast(2f)
    // 은은한 빛무리
    drawCircle(
        Brush.radialGradient(
            0.55f to Color.Transparent,
            0.78f to base.copy(alpha = 0.32f * alpha),
            1f to Color.Transparent,
            center = c, radius = r * 1.45f,
        ),
        radius = r * 1.45f, center = c,
    )
    // 본체 띠 (빛이 돌아가는 금속 느낌)
    rotate(t * 40f, c) {
        drawCircle(
            Brush.sweepGradient(
                listOf(deep, base, Color.White, base, deep, base, Color.White, base, deep).map { it.copy(alpha = alpha) },
                center = c,
            ),
            radius = r, center = c, style = Stroke(band),
        )
    }
    // 안쪽 가는 선
    drawCircle(Color.White.copy(alpha = 0.55f * alpha), radius = r - band * 0.9f, center = c, style = Stroke(1.5f))
    // 둘레의 작은 불빛 8개
    for (k in 0 until 8) {
        val a = t * 0.9f + k * (PI.toFloat() / 4)
        val p = c + Offset(cos(a), sin(a)) * (r + band * 1.1f)
        drawCircle(Color.White.copy(alpha = 0.85f * alpha), radius = (band * 0.28f).coerceAtLeast(1.5f), center = p)
    }
}

/** 부풀어 오른 폭풍 구름 + 가끔 번개 */
private fun DrawScope.stormCloud(c: Offset, halfW: Float, alpha: Float, t: Float, idx: Int, danger: Boolean, hit: Boolean) {
    if (alpha <= 0f || halfW < 2f) return
    fun rnd(n: Int) = ((sin((n + idx * 13) * 12.9898f) * 43758.547f) % 1f + 1f) % 1f
    if (danger || hit) {
        val pulse = 0.55f + 0.45f * sin(t * 10f)
        drawCircle(
            Brush.radialGradient(
                listOf(Coral.copy(alpha = 0.35f * alpha * pulse), Color.Transparent),
                center = c, radius = halfW * 1.6f,
            ),
            radius = halfW * 1.6f, center = c,
        )
    }
    // 넓게 깔리는 어두운 안개 (가로로 넓은 타원, 가장자리는 부드럽게 사라진다)
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
    // 뭉게뭉게한 덩어리: 가장자리가 부드럽게 사라지는 퍼프를 많이 겹친다. 아래는 평평하고 위로 부풀게
    for (j in 0 until 18) {
        val u = rnd(j) - 0.5f
        val o = Offset(u * 2.0f * halfW, -(1f - 4f * u * u) * halfW * 0.35f * rnd(j + 30) + halfW * 0.04f)
        val rad = halfW * (0.32f + rnd(j + 60) * 0.3f) * (1.1f - kotlin.math.abs(u))
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
    // 위쪽은 햇빛을 받아 은빛으로
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
    // 번개
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
    // 네 갈래 반짝임
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

/** 받거나 통과했을 때 흩어지는 빛 입자 */
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

/** 조준점: 네 개의 짧은 호 + 가운데 점 */
private fun DrawScope.reticle(c: Offset) {
    drawCircle(
        Brush.radialGradient(listOf(Color.White.copy(alpha = 0.16f), Color.Transparent), center = c, radius = 70f),
        radius = 70f, center = c,
    )
    val r = 32f
    for (k in 0 until 4) {
        drawArc(
            Color.White.copy(alpha = 0.95f), startAngle = -35f + k * 90f, sweepAngle = 70f, useCenter = false,
            topLeft = c - Offset(r, r), size = Size(r * 2, r * 2), style = Stroke(3.5f, cap = StrokeCap.Round),
        )
    }
    drawCircle(Color.White, radius = 4.5f, center = c)
}
