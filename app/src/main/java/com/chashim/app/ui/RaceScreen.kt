package com.chashim.app.ui

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.chashim.app.engine.EffectState
import com.chashim.app.engine.race.RaceInfo
import com.chashim.app.engine.race.RacePhase
import kotlinx.coroutines.delay

/** 게임(WebView) → 앱 이벤트 통로 */
private class RaceBridge(private val onEvent: (String) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    @JavascriptInterface
    fun event(json: String) { main.post { onEvent(json) } }
}

/**
 * 엔터테인 모드 "제주 하늘 레이스" 화면.
 * 왼쪽: 3D 게임 (assets/race, three.js), 오른쪽: 실제 차에서 일어나는 4D 효과 패널.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun RaceScreen(
    phase: RacePhase,
    info: RaceInfo,
    effects: EffectState,
    steer: () -> Float,
    braking: () -> Boolean,
    onEvent: (String) -> Unit,
    onStop: () -> Unit,
) {
    val event by rememberUpdatedState(onEvent)
    var web by remember { mutableStateOf<WebView?>(null) }
    var ready by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }

    // 차가 준비되고(Running) 게임도 준비되면 출발
    LaunchedEffect(ready, phase) {
        if (ready && phase == RacePhase.Running && !started) {
            started = true
            web?.evaluateJavascript("race.start()", null)
        }
    }
    // 핸들·브레이크를 초당 30번 게임에 넘긴다
    LaunchedEffect(web) {
        val w = web ?: return@LaunchedEffect
        while (true) {
            w.evaluateJavascript("window.race&&race.input(${"%.3f".format(java.util.Locale.US, steer())},${braking()})", null)
            delay(33)
        }
    }
    DisposableEffect(Unit) { onDispose { web?.destroy() } }

    Row(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.weight(1f).fillMaxHeight()) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.allowFileAccess = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        setBackgroundColor(android.graphics.Color.BLACK)
                        // 에뮬레이터에서 WebGL 화면이 합성되지 않는 문제를 피하려고 하드웨어 레이어로 그린다
                        setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                        webChromeClient = object : WebChromeClient() {
                            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                                Log.i("ChashimRace", "${m.message()} (${m.sourceId().substringAfterLast('/')}:${m.lineNumber()})")
                                return true
                            }
                        }
                        addJavascriptInterface(RaceBridge { json ->
                            if (json.contains("\"ready\"")) ready = true
                            event(json)
                        }, "Android")
                        loadUrl("file:///android_asset/race/index.html" + (if (com.chashim.app.engine.race.RaceEngine.autopilot) "?debug&autopilot" else ""))
                        web = this
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            if (phase == RacePhase.Preparing || phase == RacePhase.Restoring) {
                Text(
                    if (phase == RacePhase.Preparing) "차량 설정을 저장하고 있어요…" else "차량 설정을 원래대로 되돌리는 중…",
                    color = Color.White, fontSize = 22.sp,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp),
                )
            }
        }

        // 오른쪽: 4D 패널
        Column(
            Modifier.width(340.dp).fillMaxHeight().background(Palette.bg).padding(26.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("4D 효과 · 엔터테인", color = Palette.muted, fontSize = 17.sp)
            Text("제주 하늘 레이스", color = Palette.text, fontSize = 25.sp, fontWeight = FontWeight.Bold)
            Text(info.zone ?: "출발 준비", color = Palette.accent, fontSize = 17.sp)
            SpeedWindCard(info.windLevel, info.boost)
            EffectRow("🌬️", if (effects.gust) "바람 · 가속!" else "바람", effects.fan?.let { "${it}단" }, effects.gust)
            EffectRow("🪟", "창문", effects.windowPercent?.let { if (it == 0) "닫힘" else "$it% 열림" }, effects.lastChanged == "window")
            EffectRow("🌧️", "와이퍼", if (effects.wiper) "작동 중" else "멈춤", effects.lastChanged == "wiper" && effects.wiper)
            EffectRow("💺", "시트 열선", effects.seatHeat?.let { if (it == 0) "꺼짐" else "${it}단계" }, effects.lastChanged == "seat")
            EffectRow(
                "💡", "무드등", effects.ambientName ?: effects.ambient?.let { "켜짐" }, effects.lastChanged == "ambient",
                swatch = effects.ambient?.let { Color(0xFF000000 or it.toLong()) },
            )
            Spacer(Modifier.weight(1f))
            Text("핸들로 좌우, 브레이크를 밟으면 아래로. 기어를 바꾸면 바로 멈추고 원래 설정으로 돌아가요", color = Palette.muted, fontSize = 13.sp)
            Button(
                onClick = onStop,
                enabled = phase == RacePhase.Running,
                modifier = Modifier.fillMaxWidth().height(58.dp),
                shape = RoundedCornerShape(29.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.surface, contentColor = Palette.text),
            ) { Text("레이스 그만하기", fontSize = 19.sp) }
        }
    }
}

/** 4D 패널: 속도 = 실제 차 바람 세기 */
@Composable
private fun SpeedWindCard(level: Int, boost: Boolean) {
    val bg by animateColorAsState(if (boost) Color(0xFFA8F0FF).copy(alpha = 0.22f) else Palette.surface, tween(300), label = "speed")
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(bg).padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("💨 속도 → 차 바람", color = Palette.text, fontSize = 17.sp, modifier = Modifier.weight(1f))
            Text(if (boost) "가속!" else "+${level}단", color = Color(0xFFA8F0FF), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(4) { i ->
                val on = if (boost) true else level > i
                Box(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(if (on) Color(0xFFA8F0FF) else Color.White.copy(alpha = 0.12f)))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text("바람길 위에서 빨라질수록 차 바람이 세져요", color = Palette.muted, fontSize = 13.sp)
    }
}

@Composable
fun RaceResultScreen(result: RacePhase.Result, onHome: () -> Unit, onAgain: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Palette.bg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(if (result.finished) "결승 도착!" else "레이스 종료", color = Palette.text, fontSize = 44.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(result.reason, color = Palette.muted, fontSize = 22.sp)
        Spacer(Modifier.height(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            ResultStat("점수", "${result.score}", if (result.newBest) "최고 기록!" else "최고 ${result.best}")
            ResultStat("바람길", "${result.onTrackPct}%", "길 위에 머문 시간")
            ResultStat("별빛", "${result.stars}/${result.starsTotal}", null)
            if (result.timeSec > 0) ResultStat("기록", "%d:%02d".format(result.timeSec / 60, result.timeSec % 60), null)
        }
        Spacer(Modifier.height(18.dp))
        Text("바람 · 창문 · 열선 · 와이퍼 · 무드등을 시작 전 설정으로 되돌렸어요", color = Palette.accent, fontSize = 18.sp)
        Spacer(Modifier.height(36.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(
                onClick = onHome, modifier = Modifier.height(64.dp).width(200.dp), shape = RoundedCornerShape(32.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.surface, contentColor = Palette.text),
            ) { Text("처음으로", fontSize = 20.sp) }
            Button(
                onClick = onAgain, modifier = Modifier.height(64.dp).width(220.dp), shape = RoundedCornerShape(32.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Palette.bg),
            ) { Text("다시 달리기", fontSize = 22.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun ResultStat(label: String, value: String, sub: String?) {
    Column(
        Modifier.width(210.dp).clip(RoundedCornerShape(20.dp)).background(Palette.surface).padding(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, color = Palette.muted, fontSize = 17.sp)
        Text(value, color = Palette.text, fontSize = 44.sp, fontWeight = FontWeight.Bold)
        sub?.let { Text(it, color = Palette.accent, fontSize = 16.sp) }
    }
}
