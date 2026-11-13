package com.chashim.app.ui

import android.media.AudioManager
import android.media.ToneGenerator
import android.view.TextureView
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.chashim.app.engine.EffectState
import com.chashim.app.engine.flight.Course
import com.chashim.app.engine.flight.FlightHud
import com.chashim.app.engine.flight.FlightPhase
import com.chashim.app.engine.flight.Mark
import kotlin.math.max
import kotlin.random.Random

/** 영상을 화면보다 이만큼 크게 띄워 두고, 핸들에 따라 보이는 영역을 옮긴다 */
private const val OVERSCAN = 1.18f
/** 조준점이 움직이는 범위: 화면 가로의 ±40% */
private const val AIM_RANGE = 0.4f

@Composable
fun FlightScreen(
    course: Course,
    phase: FlightPhase,
    hud: FlightHud,
    effects: EffectState,
    onStop: () -> Unit,
) {
    val glow by animateColorAsState(Color(hud.segment?.glow ?: 0xFF2B6C8F), tween(2_000), label = "glow")
    Sounds(hud)

    Row(Modifier.fillMaxSize().background(Color.Black)) {
        // 왼쪽: 비행 화면
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .border(10.dp, Brush.verticalGradient(listOf(glow, glow.copy(alpha = 0.3f))), RectangleShape)
                .clipToBounds(),
        ) {
            val w = constraints.maxWidth.toFloat()
            val h = constraints.maxHeight.toFloat()
            // 화면 흔들림 (먹구름 충돌)
            val shake = remember { Animatable(0f) }
            LaunchedEffect(hud.shake) {
                if (hud.shake == 0) return@LaunchedEffect
                repeat(8) { shake.snapTo((Random.nextFloat() - 0.5f) * 40f); kotlinx.coroutines.delay(40) }
                shake.snapTo(0f)
            }
            val aim = hud.aim
            FlightVideo(
                path = hud.segment?.video,
                speed = if (hud.hover) 0.5f else 1f,
                modifier = Modifier.graphicsLayer {
                    scaleX = OVERSCAN; scaleY = OVERSCAN
                    // 오른쪽으로 꺾으면 시야가 오른쪽으로 → 영상은 왼쪽으로 이동
                    translationX = -aim * w * (OVERSCAN - 1f) / 2f + shake.value
                    translationY = shake.value * 0.6f
                    rotationZ = -aim * 8f
                },
            )
            Overlay(hud, Modifier.fillMaxSize())

            // 위: 점수, 구간
            Row(
                Modifier.align(Alignment.TopStart).fillMaxWidth().padding(28.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    Modifier.clip(RoundedCornerShape(18.dp)).background(Color.Black.copy(alpha = 0.5f))
                        .padding(horizontal = 22.dp, vertical = 12.dp),
                ) {
                    Text("점수", color = Color.White.copy(alpha = 0.7f), fontSize = 16.sp)
                    Text("${hud.score}", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "링 ${hud.ringsHit}/${hud.ringsTotal}" + if (hud.combo >= 2) " · 콤보 ${hud.combo}" else "",
                        color = Palette.accent, fontSize = 18.sp,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (hud.hover) Pill("🛑 브레이크 호버링 · 0.5배속", Palette.warn)
            }

            // 알림
            hud.toast?.let { t -> ToastView(t.id, t.text, t.good, Modifier.align(Alignment.TopCenter).padding(top = 40.dp)) }

            // 갈림길
            hud.fork?.let { f -> ForkBanner(f, Modifier.align(Alignment.Center).padding(top = 280.dp)) }

            // 아래: 구간 이름
            hud.segment?.let { s ->
                Column(Modifier.align(Alignment.BottomStart).padding(40.dp)) {
                    Text(s.title, color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold)
                    Text(s.caption, color = Color.White.copy(alpha = 0.85f), fontSize = 22.sp)
                }
            }

            // 준비 / 카운트다운 / 복구
            when (phase) {
                is FlightPhase.Calibrate -> CenterCard("핸들을 좌우로 돌려 보세요", "조준점이 따라 움직여요 · ${phase.secLeft}")
                is FlightPhase.Countdown -> Text(
                    "${phase.n}", color = Color.White, fontSize = 160.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.Center),
                )
                FlightPhase.Preparing -> CenterCard("비행 준비 중…", "차량 설정을 저장하고 있어요")
                FlightPhase.Restoring -> CenterCard("착륙했어요", "차량 설정을 원래대로 되돌리는 중…")
                else -> Unit
            }
        }

        // 오른쪽: 4D 패널
        Column(
            Modifier.width(360.dp).fillMaxHeight().background(Palette.bg).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("4D 효과 · 엔터테인", color = Palette.muted, fontSize = 18.sp)
            Text(course.title, color = Palette.text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            if (hud.totalSec > 0) {
                LinearProgressIndicator(
                    progress = { (hud.elapsed / hud.totalSec).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(), color = Palette.accent,
                )
            }
            SteerCard(hud.steerDeg, hud.hover)
            Spacer(Modifier.height(2.dp))
            EffectRow("🌬️", if (effects.gust) "바람 · 돌풍!" else "바람", effects.fan?.let { "${it}단" }, effects.gust)
            EffectRow("🪟", "창문", effects.windowPercent?.let { if (it == 0) "닫힘" else "$it% 열림" }, effects.lastChanged == "window")
            EffectRow("🌧️", "와이퍼", if (effects.wiper) "작동 중" else "멈춤", effects.lastChanged == "wiper" && effects.wiper)
            EffectRow("💺", "시트 열선", effects.seatHeat?.let { if (it == 0) "꺼짐" else "${it}단계" }, effects.lastChanged == "seat")
            EffectRow(
                "💡", "무드등", effects.ambientName ?: effects.ambient?.let { "켜짐" }, effects.lastChanged == "ambient",
                swatch = effects.ambient?.let { Color(0xFF000000 or it.toLong()) },
            )
            Spacer(Modifier.weight(1f))
            Text("기어를 바꾸면 바로 멈추고 원래 설정으로 돌아가요", color = Palette.muted, fontSize = 14.sp)
            Button(
                onClick = onStop,
                enabled = phase is FlightPhase.Flying || phase is FlightPhase.Calibrate || phase is FlightPhase.Countdown,
                modifier = Modifier.fillMaxWidth().height(60.dp),
                shape = RoundedCornerShape(30.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.surface, contentColor = Palette.text),
            ) { Text("비행 그만하기", fontSize = 20.sp) }
        }
    }
}

/** 4D 패널: 핸들 각도 막대 */
@Composable
private fun SteerCard(deg: Float, hover: Boolean) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.surface)
            .padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🎯 핸들", color = Palette.text, fontSize = 18.sp, modifier = Modifier.weight(1f))
            Text("%+.0f°".format(deg), color = Palette.accent, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(8.dp))
        Canvas(Modifier.fillMaxWidth().height(14.dp)) {
            drawRoundRect(Color.White.copy(alpha = 0.12f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(7f, 7f))
            val n = (deg / com.chashim.app.engine.flight.STEER_MAX_DEG).coerceIn(-1f, 1f)
            val cx = size.width / 2
            val x = cx + n * size.width / 2
            drawRect(Palette.accent, topLeft = Offset(minOf(cx, x), 0f), size = Size(kotlin.math.abs(x - cx).coerceAtLeast(3f), size.height))
            drawLine(Color.White, Offset(cx, 0f), Offset(cx, size.height), 2f)
        }
        Spacer(Modifier.height(6.dp))
        Text(if (hover) "브레이크: 호버링 중" else "브레이크를 밟으면 천천히 날아요", color = Palette.muted, fontSize = 14.sp)
    }
}

/** 링·먹구름·조준점 */
@Composable
private fun Overlay(hud: FlightHud, modifier: Modifier) {
    Canvas(modifier) {
        val cx = size.width / 2
        val cy = size.height * 0.48f
        fun sx(x: Float) = cx + x * size.width * AIM_RANGE
        val rFar = size.height * 0.02f
        val rNear = size.height * 0.13f

        hud.clouds.forEach { c ->
            val k = c.k * c.k
            val x = sx(c.x)
            val halfW = (c.w / 2) * size.width * AIM_RANGE * (0.15f + 0.85f * k)
            val col = when (c.hit) { true -> Color(0xFFB03030); else -> Color(0xFF2A2F3A) }
            val a = if (c.hit == null) 0.35f + 0.5f * k else 0.6f
            for (i in -2..2) {
                drawCircle(col.copy(alpha = a), radius = halfW * 0.55f, center = Offset(x + i * halfW * 0.38f, cy + (if (i % 2 == 0) 0f else -halfW * 0.2f)))
            }
        }
        hud.rings.forEach { r ->
            val k = r.k * r.k
            val radius = rFar + (rNear - rFar) * k
            val col = when (r.mark) {
                Mark.PENDING -> Color(0xFFFFD54F)
                Mark.HIT -> Color(0xFF7FD6A8)
                Mark.MISS -> Color(0xFFEF5350)
            }
            val a = if (r.mark == Mark.PENDING) 0.4f + 0.6f * r.k else 0.9f
            drawCircle(col.copy(alpha = a), radius = radius, center = Offset(sx(r.x), cy), style = Stroke(width = 6f + 10f * k))
        }
        // 조준점
        val ax = sx(hud.aim)
        val c = Color.White
        drawCircle(c, radius = 26f, center = Offset(ax, cy), style = Stroke(4f))
        drawCircle(c, radius = 5f, center = Offset(ax, cy))
        drawLine(c, Offset(ax - 52f, cy), Offset(ax - 32f, cy), 4f)
        drawLine(c, Offset(ax + 32f, cy), Offset(ax + 52f, cy), 4f)
        drawLine(c, Offset(ax, cy - 52f), Offset(ax, cy - 32f), 4f)
        drawLine(c, Offset(ax, cy + 32f), Offset(ax, cy + 52f), 4f)
    }
}

@Composable
private fun ForkBanner(f: com.chashim.app.engine.flight.ForkView, modifier: Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(24.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 32.dp, vertical = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        val leftOn = f.chosenLeft != false
        val rightOn = f.chosenLeft != true
        Text("◀ ${f.leftLabel}", color = if (leftOn) Color.White else Color.White.copy(alpha = 0.3f), fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("갈림길", color = Palette.warn, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(if (f.chosenLeft == null) "핸들을 크게 꺾으세요 · ${f.secLeft}" else "선택 완료!", color = Color.White.copy(alpha = 0.8f), fontSize = 18.sp)
        }
        Text("${f.rightLabel} ▶", color = if (rightOn) Color.White else Color.White.copy(alpha = 0.3f), fontSize = 34.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ToastView(id: Int, text: String, good: Boolean, modifier: Modifier) {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(id) {
        alpha.snapTo(1f)
        kotlinx.coroutines.delay(900)
        alpha.animateTo(0f, tween(500))
    }
    if (alpha.value > 0f) {
        Text(
            text,
            color = (if (good) Palette.accent else Color(0xFFFF8A80)).copy(alpha = alpha.value),
            fontSize = 40.sp, fontWeight = FontWeight.Bold,
            modifier = modifier.graphicsLayer { scaleX = 1f + 0.1f * alpha.value; scaleY = scaleX },
        )
    }
}

@Composable
private fun Pill(text: String, color: Color) {
    Text(
        text, color = color, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 18.dp, vertical = 10.dp),
    )
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.CenterCard(title: String, sub: String) {
    Column(
        Modifier.align(Alignment.Center).clip(RoundedCornerShape(24.dp)).background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 40.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(sub, color = Color.White.copy(alpha = 0.8f), fontSize = 22.sp)
    }
}

/** 링 통과·충돌 효과음 */
@Composable
private fun Sounds(hud: FlightHud) {
    val tone = remember { runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 70) }.getOrNull() }
    DisposableEffect(Unit) { onDispose { tone?.release() } }
    LaunchedEffect(hud.toast?.id) {
        val t = hud.toast ?: return@LaunchedEffect
        tone?.startTone(if (t.good) ToneGenerator.TONE_PROP_ACK else ToneGenerator.TONE_SUP_ERROR, 250)
    }
}

/**
 * 비행 영상. 시야 이동·기울기를 적용하려고 TextureView에 그린다 (SurfaceView는 변형이 안 됨).
 * 영상 비율(16:9)을 유지한 채 화면을 꽉 채운다.
 */
@Composable
private fun FlightVideo(path: String?, speed: Float, modifier: Modifier) {
    val context = LocalContext.current
    val player = remember { ExoPlayer.Builder(context).build().apply { repeatMode = Player.REPEAT_MODE_ONE; volume = 0f } }
    DisposableEffect(Unit) { onDispose { player.release() } }
    LaunchedEffect(path) {
        if (path == null) return@LaunchedEffect
        player.setMediaItem(MediaItem.fromUri("asset:///$path"))
        player.prepare()
        player.play()
    }
    LaunchedEffect(speed) { player.setPlaybackSpeed(speed) }

    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        val cw = constraints.maxWidth.toFloat()
        val ch = constraints.maxHeight.toFloat()
        val vw = max(cw, ch * 16f / 9f)
        val vh = vw * 9f / 16f
        AndroidView(
            factory = { TextureView(it).also { tv -> player.setVideoTextureView(tv) } },
            modifier = modifier.requiredSize(with(density) { vw.toDp() }, with(density) { vh.toDp() }),
        )
    }
}

@Composable
fun FlightResultScreen(result: FlightPhase.Result, onHome: () -> Unit, onAgain: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Palette.bg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("비행 완료!", color = Palette.text, fontSize = 44.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(result.reason, color = Palette.muted, fontSize = 22.sp)
        Spacer(Modifier.height(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Stat("점수", "${result.score}", if (result.newBest) "최고 기록!" else "최고 ${result.best}")
            Stat("링 통과", "${result.ringsHit}/${result.ringsTotal}", null)
        }
        Spacer(Modifier.height(20.dp))
        Text("비행 경로: " + result.route.joinToString(" → "), color = Palette.text, fontSize = 20.sp)
        Spacer(Modifier.height(8.dp))
        Text("바람 · 창문 · 열선 · 와이퍼 · 무드등을 시작 전 설정으로 되돌렸어요", color = Palette.accent, fontSize = 18.sp)
        Spacer(Modifier.height(36.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(
                onClick = onHome,
                modifier = Modifier.height(64.dp).width(200.dp),
                shape = RoundedCornerShape(32.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.surface, contentColor = Palette.text),
            ) { Text("처음으로", fontSize = 20.sp) }
            Button(
                onClick = onAgain,
                modifier = Modifier.height(64.dp).width(220.dp),
                shape = RoundedCornerShape(32.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Palette.bg),
            ) { Text("다시 날기", fontSize = 22.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, sub: String?) {
    Column(
        Modifier.width(240.dp).clip(RoundedCornerShape(20.dp)).background(Palette.surface).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, color = Palette.muted, fontSize = 18.sp)
        Text(value, color = Palette.text, fontSize = 48.sp, fontWeight = FontWeight.Bold)
        sub?.let { Text(it, color = Palette.accent, fontSize = 18.sp) }
    }
}
