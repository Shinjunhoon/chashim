package com.chashim.app.engine.race

import ai.pleos.playground.vehicle.constant.control.VehicleGear
import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.chashim.app.engine.EffectState
import com.chashim.app.vehicle.Snapshot
import com.chashim.app.vehicle.VehicleGateway
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

private const val TAG = "Chashim"

/** 핸들 각도 → 끝까지 (도) */
const val RACE_STEER_MAX_DEG = 60f
private const val DEADZONE_DEG = 3f
/** 같은 장치에 명령을 보내는 최소 간격 */
private const val MIN_CMD_MS = 300L
private const val STAR_GOLD = 0xFFD45A

sealed interface RacePhase {
    data object Idle : RacePhase
    data object Preparing : RacePhase
    data object Running : RacePhase
    data object Restoring : RacePhase
    data class Result(
        val score: Int,
        val stars: Int,
        val starsTotal: Int,
        /** 바람길 위에 머문 비율 (%) */
        val onTrackPct: Int,
        val timeSec: Int,
        val best: Int,
        val newBest: Boolean,
        val reason: String,
        val finished: Boolean,
    ) : RacePhase
}

/** 화면 옆 4D 패널에 보여 줄 게임 상태 */
data class RaceInfo(val windLevel: Int = 0, val zone: String? = null, val boost: Boolean = false)

/**
 * 엔터테인 모드 "제주 하늘 레이스".
 * 3D 화면(WebView)은 게임을 그리고, 이 엔진은 차를 맡는다.
 * - 핸들·브레이크 값을 게임에 넘긴다 (SDK 핸들 각도는 왼쪽이 +라서 부호를 바꾼다)
 * - 게임 이벤트에 맞춰 바람(속도), 무드등(구간·별빛), 창문(해안), 와이퍼(구름), 열선(해돋이)을 움직인다
 * - 기어가 P에서 벗어나면 즉시 멈추고, 끝나면 시작 전 설정으로 복구한다
 */
class RaceEngine(
    private val vehicle: VehicleGateway,
    private val scope: CoroutineScope,
    context: Context,
) {
    private val prefs = context.getSharedPreferences("race", Context.MODE_PRIVATE)

    private val _phase = MutableStateFlow<RacePhase>(RacePhase.Idle)
    val phase: StateFlow<RacePhase> = _phase.asStateFlow()

    private val _effects = MutableStateFlow(EffectState())
    val effects: StateFlow<EffectState> = _effects.asStateFlow()

    private val _info = MutableStateFlow(RaceInfo())
    val info: StateFlow<RaceInfo> = _info.asStateFlow()

    /** 테스트용: 핸들 각도(오른쪽 +, 도)·브레이크 가정 */
    var steerOverride: Float? = null
    var brakeOverride: Boolean? = null

    /** 게임에 넘길 핸들 값 (-1 ~ 1, 오른쪽 +) */
    fun steer(): Float {
        val deg = steerOverride ?: -vehicle.steeringDeg.value
        val a = if (abs(deg) < DEADZONE_DEG) 0f else deg
        return (a / RACE_STEER_MAX_DEG).coerceIn(-1f, 1f)
    }
    fun braking(): Boolean = brakeOverride ?: vehicle.brakePressed.value

    private var job: Job? = null
    private var finished: CompletableDeferred<JSONObject>? = null
    private var snapshot: Snapshot? = null
    private var stopReason = "성산일출봉에 도착했어요"
    private var baseFan = 1

    // 바람 (속도 + 이벤트)
    private var windLevel = 0
    private var sentFan: Int? = null
    private var lastFanCmd = 0L
    private var burstUntil = 0L
    private var burstLevel = 0
    private var fanJob: Job? = null
    private var fanTicker: Job? = null
    private var guardJob: Job? = null

    // 무드등
    private var ambientJob: Job? = null
    private var lastAmbientCmd = 0L
    private var sceneAmbient: Int? = null
    private var effectJobs = mutableListOf<Job>()

    fun start() {
        if (job?.isActive == true) return
        stopReason = "성산일출봉에 도착했어요"
        val done = CompletableDeferred<JSONObject>().also { finished = it }
        job = scope.launch {
            var result: JSONObject? = null
            try {
                _phase.value = RacePhase.Preparing
                vehicle.refreshCapabilitiesIfMissing()
                snapshot = vehicle.snapshot()
                baseFan = vehicle.clampFan(snapshot?.fan ?: vehicle.caps.fanMin)
                sentFan = snapshot?.fan
                windLevel = 0
                burstUntil = 0L
                _info.value = RaceInfo()
                _effects.value = EffectState(
                    fan = snapshot?.fan, temp = snapshot?.temp, seatHeat = snapshot?.seatHeat,
                    windowPercent = snapshot?.window?.let { toPercent(it) }, ambient = snapshot?.ambientColor,
                )
                vehicle.setPower(true)
                guardJob = launch {
                    vehicle.gearState.collect { g ->
                        if (g != null && g != VehicleGear.GEAR_PARK) {
                            Log.i(TAG, "race: gear $g → stop")
                            stop("기어가 바뀌어 안전하게 멈췄어요")
                        }
                    }
                }
                // 돌풍이 끝나면 속도 바람으로 돌아가도록 주기적으로 맞춘다
                fanTicker = launch { while (true) { syncFan(); delay(150) } }
                _phase.value = RacePhase.Running
                result = done.await()
            } finally {
                withContext(NonCancellable) {
                    fanTicker?.cancel()
                    guardJob?.cancel() // 감시가 남아 있으면 다음 판을 시작할 수 없다
                    fanJob?.cancelAndJoin()
                    ambientJob?.cancelAndJoin()
                    effectJobs.forEach { it.cancelAndJoin() }
                    effectJobs.clear()
                    _phase.value = RacePhase.Restoring
                    snapshot?.let { vehicle.restore(it) }
                    val score = result?.optInt("score") ?: 0
                    val best = prefs.getInt("best", 0)
                    val newBest = result != null && score > best
                    if (newBest) prefs.edit().putInt("best", score).apply()
                    _phase.value = RacePhase.Result(
                        score = score,
                        stars = result?.optInt("stars") ?: 0,
                        starsTotal = result?.optInt("starsTotal") ?: 0,
                        onTrackPct = result?.optInt("onTrackPct") ?: 0,
                        timeSec = result?.optInt("timeSec") ?: 0,
                        best = maxOf(best, score),
                        newBest = newBest,
                        reason = stopReason,
                        finished = result != null,
                    )
                }
            }
        }
    }

    fun stop(reason: String = "중간에 멈췄어요") {
        stopReason = reason
        job?.cancel()
    }

    fun reset() {
        if (job?.isActive != true) _phase.value = RacePhase.Idle
    }

    /** 게임(WebView)에서 온 이벤트. 메인 스레드에서 부른다 */
    fun onEvent(json: String) {
        val e = runCatching { JSONObject(json) }.getOrNull() ?: return
        if (_phase.value != RacePhase.Running) return
        when (e.optString("type")) {
            "wind" -> {
                windLevel = e.optInt("level").coerceIn(0, 3)
                _info.update { it.copy(windLevel = windLevel) }
                syncFan()
            }
            "boost" -> {
                Log.i(TAG, "race boost")
                burst(level = 4, ms = 1_400)
                _info.update { it.copy(boost = true) }
                scope.launch { delay(1_400); _info.update { it.copy(boost = false) } }
            }
            "star" -> {
                val big = e.optBoolean("big")
                if (big) { pulseAmbient(0xFFFFFF, 500); burst(level = 3, ms = 800) } else pulseAmbient(STAR_GOLD, 350)
            }
            "storm" -> if (e.optBoolean("hit")) burst(level = -99, ms = 1_200) // 바람이 순간 멎는다
            "zone" -> onZone(e)
            "finish" -> {
                Log.i(TAG, "race finish $e")
                finished?.complete(e)
            }
        }
    }

    private fun onZone(e: JSONObject) {
        Log.i(TAG, "race zone ${e.optString("id")}")
        _info.update { it.copy(zone = e.optString("title")) }
        val color = e.optInt("ambient", -1)
        if (color >= 0) {
            sceneAmbient = color
            val name = e.optString("name")
            effectJobs += scope.launch {
                vehicle.setAmbientColor(color)?.let { r -> _effects.update { it.copy(ambient = r, ambientName = name, lastChanged = "ambient") } }
            }
        }
        if (e.has("window")) {
            val pct = e.optInt("window").coerceIn(0, 20) // 안전 규칙: 최대 20%
            effectJobs += scope.launch {
                val pos = (vehicle.caps.windowMax * pct / 100f).roundToInt().coerceAtLeast(if (pct > 0) 1 else 0)
                vehicle.setWindow(pos)?.let { r -> _effects.update { it.copy(windowPercent = toPercent(r), lastChanged = "window") } }
            }
        }
        if (e.has("wiper")) {
            val sec = e.optInt("wiper")
            effectJobs += scope.launch {
                vehicle.setWiper(true)?.let { _effects.update { it.copy(wiper = true, lastChanged = "wiper") } }
                try { delay(sec * 1_000L) } finally {
                    withContext(NonCancellable) { vehicle.setWiper(false); _effects.update { it.copy(wiper = false) } }
                }
            }
        }
        if (e.has("seat")) {
            val lv = e.optInt("seat")
            effectJobs += scope.launch {
                vehicle.setSeatHeat(lv)?.let { r -> _effects.update { it.copy(seatHeat = r, lastChanged = "seat") } }
            }
        }
    }

    // ---- 바람 ----

    private fun desiredFan(): Int {
        val now = SystemClock.elapsedRealtime()
        val level = if (now < burstUntil) burstLevel else windLevel
        return vehicle.clampFan(if (level < -10) vehicle.caps.fanMin else baseFan + level)
    }

    private fun burst(level: Int, ms: Long) {
        burstLevel = level
        burstUntil = SystemClock.elapsedRealtime() + ms
        lastFanCmd = 0L // 이벤트는 바로 반영
        syncFan()
    }

    /** 원하는 세기와 차에 보낸 세기가 다르면 보낸다. 같은 장치에는 300ms 안에 두 번 보내지 않는다 */
    private fun syncFan() {
        val want = desiredFan()
        if (want == sentFan || fanJob?.isActive == true) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastFanCmd < MIN_CMD_MS) return
        lastFanCmd = now
        sentFan = want
        fanJob = scope.launch {
            val r = vehicle.setFan(want)
            if (r != null) _effects.update { it.copy(fan = r, gust = want > baseFan, lastChanged = "fan") } else sentFan = null
        }
    }

    // ---- 무드등 ----

    private fun pulseAmbient(color: Int, ms: Long) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAmbientCmd < MIN_CMD_MS) return
        lastAmbientCmd = now
        ambientJob?.cancel()
        ambientJob = scope.launch {
            try {
                vehicle.setAmbientColor(color)?.let { r -> _effects.update { it.copy(ambient = r, lastChanged = "ambient") } }
                delay(ms)
            } finally {
                withContext(NonCancellable) {
                    sceneAmbient?.let { c -> vehicle.setAmbientColor(c)?.let { r -> _effects.update { it.copy(ambient = r) } } }
                }
            }
        }
    }

    private fun toPercent(position: Int) = (position * 100f / vehicle.caps.windowMax.coerceAtLeast(1)).roundToInt()
}
