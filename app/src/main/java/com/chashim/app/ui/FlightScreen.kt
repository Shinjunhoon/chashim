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
                speed = 1f + hud.windLevel * 0.08f, // 순풍을 타면 조금 빨라진다
                modifier = Modifier.graphicsLayer {
                    scaleX = OVERSCAN; scaleY = OVERSCAN
                    // 오른쪽으로 꺾으면 시야가 오른쪽으로 → 영상은 왼쪽으로 이동
                    translationX = -aim * w * (OVERSCAN - 1f) / 2f + shake.value
                    translationY = shake.value * 0.6f
                    rotationZ = -aim * 8f
                },
            )
            FlightCanvas(hud, Modifier.fillMaxSize())

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
                        "바람길 ${hud.windPct}% · 별빛 ${hud.starsCaught}/${hud.starsTotal}" + if (hud.multiplier >= 2) " · 순풍 ×${hud.multiplier}" else "",
                        color = Palette.accent, fontSize = 18.sp,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (hud.diving) Pill("▼ 브레이크 · 내려가는 중", Palette.warn)
            }

            // 알림
            hud.toast?.let { t -> ToastView(t.id, t.text, t.good, Modifier.align(Alignment.TopCenter).padding(top = 40.dp)) }

            // 바람길이 숨은 구간: 바람 세기만이 단서
            if (hud.hidden && phase is FlightPhase.Flying) {
                Pill("🌬️ 바람길이 사라졌어요 · 바람이 세지는 쪽으로", Color(0xFFA8F0FF), Modifier.align(Alignment.TopCenter).padding(top = 100.dp))
            }

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
                is FlightPhase.Calibrate -> CenterCard("핸들을 돌리고, 브레이크를 밟아 보세요", "핸들은 좌우, 브레이크는 아래로 · ${phase.secLeft}")
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
            SteerCard(hud.steerDeg, hud.diving)
            WindCard(hud.windLevel, hud.inWind, hud.hidden)
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

/** 4D 패널: 바람길과의 거리 = 실제 차 바람 세기 */
@Composable
private fun WindCard(level: Int, inWind: Boolean, hidden: Boolean) {
    val bg by animateColorAsState(if (inWind) Color(0xFFA8F0FF).copy(alpha = 0.18f) else Palette.surface, tween(400), label = "wind")
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(bg)
            .padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🌬️ 바람길", color = Palette.text, fontSize = 18.sp, modifier = Modifier.weight(1f))
            Text(
                when (level) { 3 -> "올라탔어요"; 2 -> "가까워요"; 1 -> "조금 가까워요"; else -> "멀어요" },
                color = if (inWind) Color(0xFFA8F0FF) else Palette.muted, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(3) { i ->
                Box(
                    Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp))
                        .background(if (level > i) Color(0xFFA8F0FF) else Color.White.copy(alpha = 0.12f)),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            if (hidden) "바람길이 숨었어요. 차 바람이 세지는 쪽을 찾아요" else "가까울수록 차 바람이 세져요 (기본 + ${level}단)",
            color = Palette.muted, fontSize = 14.sp,
        )
    }
}

/** 4D 패널: 핸들 각도 막대 */
@Composable
private fun SteerCard(deg: Float, diving: Boolean) {
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
        Text(if (diving) "브레이크: 내려가는 중" else "브레이크를 밟으면 내려가고, 떼면 올라와요", color = Palette.muted, fontSize = 14.sp)
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
            fontSize = 32.sp, fontWeight = FontWeight.SemiBold,
            style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 2f), 12f)),
            modifier = modifier.graphicsLayer { scaleX = 1f + 0.06f * alpha.value; scaleY = scaleX },
        )
    }
}

@Composable
private fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text, color = color, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
        modifier = modifier.clip(RoundedCornerShape(20.dp)).background(Color.Black.copy(alpha = 0.55f))
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
            Stat("바람길", "${result.windPct}%", "바람을 탄 시간")
            Stat("별빛", "${result.starsCaught}/${result.starsTotal}", null)
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
