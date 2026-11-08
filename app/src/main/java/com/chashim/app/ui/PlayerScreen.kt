package com.chashim.app.ui

import androidx.annotation.OptIn
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.chashim.app.engine.EffectState
import com.chashim.app.engine.Scene
import com.chashim.app.engine.SessionPhase
import com.chashim.app.engine.Theme

@Composable
fun PlayerScreen(
    theme: Theme,
    phase: SessionPhase,
    effects: EffectState,
    flash: Int,
    onStop: () -> Unit,
) {
    val playing = phase as? SessionPhase.Playing
    val scene = playing?.let { theme.scenes[it.sceneIndex] }
    val glow by animateColorAsState(Color(scene?.glow ?: 0xFF1E3A4D), tween(3_000), label = "glow")

    Row(Modifier.fillMaxSize().background(Color.Black)) {
        // 왼쪽: 장면 화면
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .border(10.dp, Brush.verticalGradient(listOf(glow, glow.copy(alpha = 0.3f))), androidx.compose.ui.graphics.RectangleShape),
        ) {
            SceneVideo(scene)
            RainCanvas(intensity = if (hasAsset(scene?.video)) 0f else scene?.rain ?: 0f, modifier = Modifier.fillMaxSize())
            FlashOverlay(flash)

            if (scene != null) {
                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(40.dp),
                ) {
                    Text(scene.title, color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold)
                    Text(scene.caption, color = Color.White.copy(alpha = 0.8f), fontSize = 22.sp)
                }
            } else {
                Text(
                    if (phase is SessionPhase.Restoring) "차량 설정을 원래대로 되돌리는 중…" else "준비 중…",
                    color = Color.White, fontSize = 26.sp, modifier = Modifier.align(Alignment.Center),
                )
            }
        }

        // 오른쪽: 4D 효과 패널 (에뮬레이터에는 실제 바람이 없으므로 시연의 핵심 화면)
        Column(
            Modifier
                .width(360.dp)
                .fillMaxHeight()
                .background(Palette.bg)
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("4D 효과", color = Palette.muted, fontSize = 18.sp)
            Text(theme.title, color = Palette.text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            if (playing != null) {
                val done = theme.scenes.take(playing.sceneIndex).sumOf { it.durationSec } + playing.elapsedSec
                LinearProgressIndicator(
                    progress = { done / theme.totalSec.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                    color = Palette.accent,
                )
                Text("장면 ${playing.sceneIndex + 1}/${theme.scenes.size} · 남은 시간 ${formatSec(theme.totalSec - done)}",
                    color = Palette.muted, fontSize = 16.sp)
            }
            Spacer(Modifier.height(4.dp))
            EffectRow("🌬️", "바람", effects.fan?.let { "${it}단" }, effects.lastChanged == "fan")
            EffectRow("🌡️", "온도", effects.temp?.let { "%.1f°C".format(it) }, effects.lastChanged == "temp")
            EffectRow("💺", "시트 열선", effects.seatHeat?.let { if (it == 0) "꺼짐" else "${it}단계" }, effects.lastChanged == "seat")
            EffectRow("🪟", "창문", effects.windowPercent?.let { if (it == 0) "닫힘" else "$it% 열림" }, effects.lastChanged == "window")
            EffectRow("🌧️", "와이퍼", if (effects.wiper) "작동 중" else "멈춤", effects.lastChanged == "wiper" && effects.wiper)
            Spacer(Modifier.weight(1f))
            Text("기어를 바꾸면 바로 멈추고 원래 설정으로 돌아가요", color = Palette.muted, fontSize = 14.sp)
            Button(
                onClick = onStop,
                enabled = phase is SessionPhase.Playing,
                modifier = Modifier.fillMaxWidth().height(60.dp),
                shape = RoundedCornerShape(30.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.surface, contentColor = Palette.text),
            ) { Text("그만 쉬기", fontSize = 20.sp) }
        }
    }
}

@Composable
private fun EffectRow(icon: String, label: String, value: String?, highlight: Boolean) {
    val bg by animateColorAsState(if (highlight) Palette.accent.copy(alpha = 0.25f) else Palette.surface, tween(600), label = "row")
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(icon, fontSize = 24.sp)
        Spacer(Modifier.width(12.dp))
        Text(label, color = Palette.muted, fontSize = 19.sp, modifier = Modifier.weight(1f))
        Text(value ?: "—", color = Palette.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun FlashOverlay(flash: Int) {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(flash) {
        if (flash == 0) return@LaunchedEffect
        alpha.snapTo(0.75f)
        alpha.animateTo(0f, tween(450))
    }
    Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = alpha.value)))
}

/** 장면 영상 + 소리. 파일이 없으면 아무것도 재생하지 않는다 (빗줄기 화면으로 대체). */
@OptIn(UnstableApi::class)
@Composable
private fun SceneVideo(scene: Scene?) {
    val context = LocalContext.current
    val video = remember { ExoPlayer.Builder(context).build().apply { repeatMode = Player.REPEAT_MODE_ONE; volume = 0f } }
    val sound = remember { ExoPlayer.Builder(context).build().apply { repeatMode = Player.REPEAT_MODE_ONE } }
    DisposableEffect(Unit) { onDispose { video.release(); sound.release() } }

    val assets = remember { AssetChecker(context) }
    LaunchedEffect(scene?.id) {
        fun load(p: ExoPlayer, path: String?) {
            if (path != null && assets.exists(path)) {
                p.setMediaItem(MediaItem.fromUri("asset:///$path"))
                p.prepare()
                p.play()
            } else {
                p.stop()
                p.clearMediaItems()
            }
        }
        load(video, scene?.video)
        load(sound, scene?.audio)
    }
    AndroidView(
        factory = {
            PlayerView(it).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                player = video
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

private class AssetChecker(private val context: android.content.Context) {
    private val cache = mutableMapOf<String, Boolean>()
    fun exists(path: String) = cache.getOrPut(path) {
        runCatching { context.assets.open(path).close() }.isSuccess
    }
}

private var assetIndex: Set<String>? = null

/** 영상 파일이 들어 있는지 (빗줄기 대체 화면을 켤지 결정) */
@Composable
private fun hasAsset(path: String?): Boolean {
    if (path == null) return false
    val context = LocalContext.current
    val index = assetIndex ?: run {
        val dir = path.substringBeforeLast('/')
        (context.assets.list(dir)?.map { "$dir/$it" }?.toSet() ?: emptySet()).also { assetIndex = it }
    }
    return path in index
}

private fun formatSec(sec: Int) = "%d:%02d".format(sec / 60, sec % 60)
