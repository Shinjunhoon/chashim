package com.chashim.app.engine.flight

import ai.pleos.playground.vehicle.constant.control.VehicleGear
import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.chashim.app.engine.Effect
import com.chashim.app.engine.EffectState
import com.chashim.app.vehicle.Snapshot
import com.chashim.app.vehicle.VehicleGateway
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
import kotlin.math.abs
import kotlin.math.roundToInt

private const val TAG = "Chashim"

/** 핸들 각도 → 화면 끝까지 (도). 보통 감도 */
const val STEER_MAX_DEG = 60f
/** 이 각도 안의 흔들림은 무시 */
private const val DEADZONE_DEG = 3f
/** 갈림길에서 이 각도 이상 꺾으면 선택 */
private const val FORK_DEG = 45f
/** 조준점이 링 중심에서 이 거리(-1~1 기준) 안이면 통과 */
private const val RING_HIT = 0.2f
/** 게임 루프 주기 */
private const val TICK_MS = 50L
/** 같은 장치에 명령을 보내는 최소 간격 */
private const val MIN_CMD_MS = 300L

enum class Mark { PENDING, HIT, MISS }

data class RingView(val x: Float, val k: Float, val mark: Mark)
data class CloudView(val x: Float, val w: Float, val k: Float, val hit: Boolean?)

/** chosenLeft: 아직 안 골랐으면 null */
data class ForkView(val leftLabel: String, val rightLabel: String, val secLeft: Int, val chosenLeft: Boolean?)

/** 화면에 띄우는 짧은 알림 (id가 바뀔 때마다 새로 보여 준다) */
data class Toast(val id: Int, val text: String, val good: Boolean)

/** 비행 화면이 그리는 모든 값 */
data class FlightHud(
    val segment: Segment? = null,
    val segT: Float = 0f,
    /** 조준점 (-1 ~ 1) */
    val aim: Float = 0f,
    /** 실제 핸들 각도 */
    val steerDeg: Float = 0f,
    val rings: List<RingView> = emptyList(),
    val clouds: List<CloudView> = emptyList(),
    val score: Int = 0,
    val combo: Int = 0,
    val ringsHit: Int = 0,
    val ringsTotal: Int = 0,
    val fork: ForkView? = null,
    /** 브레이크 호버링 중 (0.5배속) */
    val hover: Boolean = false,
    val shake: Int = 0,
    val toast: Toast? = null,
    val elapsed: Float = 0f,
    val totalSec: Int = 0,
)

sealed interface FlightPhase {
    data object Idle : FlightPhase
    data object Preparing : FlightPhase
    /** 핸들 감도 확인 */
    data class Calibrate(val secLeft: Int) : FlightPhase
    data class Countdown(val n: Int) : FlightPhase
    data object Flying : FlightPhase
    data object Restoring : FlightPhase
    data class Result(
        val score: Int,
        val ringsHit: Int,
        val ringsTotal: Int,
        val route: List<String>,
        val best: Int,
        val newBest: Boolean,
        val reason: String,
    ) : FlightPhase
}

/**
 * 엔터테인 모드 비행.
 * - 핸들 각도 → 조준점·시야·기울기 (데드존 3°, ±60° = 화면 끝, 저역 필터)
 * - 링 통과 판정과 점수 (콤보 3회부터 2배), 먹구름 충돌 감점
 * - 갈림길: 5~6초 안에 ±45° 이상 꺾은 방향으로
 * - 브레이크: 누르는 동안 0.5배속 호버링, 바람 1단
 * - 게임 이벤트마다 바람·와이퍼·무드등이 반응 (같은 장치는 300ms 간격)
 * 기어가 P에서 벗어나면 즉시 멈추고, 끝나면 시작 전 설정으로 복구한다.
 */
class FlightEngine(
    private val vehicle: VehicleGateway,
    private val scope: CoroutineScope,
    context: Context,
) {
    private val prefs = context.getSharedPreferences("flight", Context.MODE_PRIVATE)

    private val _phase = MutableStateFlow<FlightPhase>(FlightPhase.Idle)
    val phase: StateFlow<FlightPhase> = _phase.asStateFlow()

    private val _hud = MutableStateFlow(FlightHud())
    val hud: StateFlow<FlightHud> = _hud.asStateFlow()

    private val _effects = MutableStateFlow(EffectState())
    val effects: StateFlow<EffectState> = _effects.asStateFlow()

    /**
     * 오른쪽으로 꺾은 각도 (도, 오른쪽 +).
     * SDK는 왼쪽을 +로 준다 (버추얼 콘솔에서 오른쪽 40° → SDK -40°). 게임에서는 오른쪽을 +로 쓴다.
     */
    private fun steerRight() = steerOverride ?: -vehicle.steeringDeg.value

    /** 테스트용: 핸들 각도(오른쪽 +)·브레이크를 이 값으로 가정 (null이면 차 값) */
    var steerOverride: Float? = null
    var brakeOverride: Boolean? = null

    private var job: Job? = null
    private var snapshot: Snapshot? = null
    private var stopReason = "무사히 착륙했어요"
    private var baseFan = 1
    private var burstJob: Job? = null
    private var lastFanCmd = 0L
    private var hovering = false
    private var toastId = 0

    fun start(course: Course) {
        if (job?.isActive == true) return
        stopReason = "무사히 착륙했어요"
        job = scope.launch {
            val route = mutableListOf<String>()
            var hud = FlightHud()
            try {
                _phase.value = FlightPhase.Preparing
                vehicle.refreshCapabilitiesIfMissing()
                snapshot = vehicle.snapshot()
                baseFan = vehicle.clampFan(snapshot?.fan ?: vehicle.caps.fanMin)
                _effects.value = EffectState(
                    fan = snapshot?.fan, temp = snapshot?.temp, seatHeat = snapshot?.seatHeat,
                    windowPercent = snapshot?.window?.let { toPercent(it) }, ambient = snapshot?.ambientColor,
                )
                vehicle.setPower(true)
                val guard = launch {
                    vehicle.gearState.collect { g ->
                        if (g != null && g != VehicleGear.GEAR_PARK) {
                            Log.i(TAG, "flight: gear $g → stop")
                            stop("기어가 바뀌어 안전하게 멈췄어요")
                        }
                    }
                }

                val first = course.segment(course.first)
                // 실제 비행 시간 (갈림길 왼쪽 기준. 오른쪽도 길이가 같다)
                val total = generateSequence(first) { s -> (s.next ?: s.fork?.left)?.let(course::segment) }.sumOf { it.durationSec }
                hud = FlightHud(segment = first, totalSec = total)

                // 1) 핸들 감도 확인 3초, 2) 카운트다운 3-2-1
                var smoothed = 0f
                fun readAim(): Float {
                    val deg = steerRight()
                    val a = if (abs(deg) < DEADZONE_DEG) 0f else deg
                    val n = (a / STEER_MAX_DEG).coerceIn(-1f, 1f)
                    smoothed += 0.2f * (n - smoothed)
                    return smoothed
                }
                for (phase in listOf(3, 2, 1).map { FlightPhase.Calibrate(it) } + listOf(3, 2, 1).map { FlightPhase.Countdown(it) }) {
                    _phase.value = phase
                    repeat((1000 / TICK_MS).toInt()) {
                        hud = hud.copy(aim = readAim(), steerDeg = steerRight())
                        _hud.value = hud
                        delay(TICK_MS)
                    }
                }
                _phase.value = FlightPhase.Flying

                var seg: Segment? = first
                var elapsed = 0f
                var score = 0
                var combo = 0
                var hits = 0
                var ringsTotal = 0
                while (seg != null) {
                    val s = seg
                    route += s.title
                    Log.i(TAG, "flight segment ${s.id}")
                    val pendingEffects = s.effects.sortedBy { it.at }.toMutableList()
                    val ringMarks = MutableList(s.rings.size) { Mark.PENDING }
                    val cloudHit = MutableList<Boolean?>(s.clouds.size) { null }
                    var chosen: String? = null
                    var chosenAt = 0f
                    var t = 0f
                    val effectJobs = mutableListOf<Job>()
                    var last = SystemClock.elapsedRealtime()
                    while (t < s.durationSec) {
                        val hover = brakeOverride ?: vehicle.brakePressed.value
                        if (hover != hovering) setHover(hover)
                        val speed = if (hover) 0.5f else 1f
                        // 실제 흐른 시간만큼 진행 (화면이 바쁘면 틱 간격이 50ms보다 길어진다)
                        val now = SystemClock.elapsedRealtime()
                        val dt = ((now - last) / 1000f).coerceAtMost(0.25f)
                        last = now
                        t += dt * speed
                        elapsed += dt * speed
                        val aim = readAim()
                        val deg = steerRight()

                        // 구간 기본 효과
                        while (pendingEffects.isNotEmpty() && pendingEffects.first().at <= t) {
                            val e = pendingEffects.removeAt(0)
                            effectJobs += launch { runEffect(e) }
                        }

                        // 링 판정
                        s.rings.forEachIndexed { i, r ->
                            if (ringMarks[i] == Mark.PENDING && t >= r.t) {
                                ringsTotal++
                                if (abs(aim - r.x) <= RING_HIT) {
                                    ringMarks[i] = Mark.HIT
                                    hits++
                                    combo++
                                    val gain = if (combo >= 3) 200 else 100
                                    score += gain
                                    if (combo >= 3) {
                                        toast("콤보 ×$combo! +$gain", true)
                                        burst(vehicle.caps.fanMax, 1_000)
                                    } else {
                                        toast("링 통과 +$gain", true)
                                        burst(baseFan + 2, 600)
                                    }
                                } else {
                                    ringMarks[i] = Mark.MISS
                                    combo = 0
                                    toast("링을 놓쳤어요", false)
                                }
                                Log.i(TAG, "ring ${s.id}#$i x=${r.x} aim=${"%.2f".format(aim)} → ${ringMarks[i]}")
                            }
                        }
                        // 먹구름 판정
                        s.clouds.forEachIndexed { i, c ->
                            if (cloudHit[i] == null && t >= c.t) {
                                val hit = abs(aim - c.x) <= c.w / 2
                                cloudHit[i] = hit
                                if (hit) {
                                    score = (score - 100).coerceAtLeast(0)
                                    combo = 0
                                    hud = hud.copy(shake = hud.shake + 1)
                                    toast("먹구름 충돌 -100", false)
                                    burst(vehicle.caps.fanMin, 1_200)
                                } else {
                                    toast("먹구름을 피했어요", true)
                                }
                                Log.i(TAG, "cloud ${s.id}#$i aim=${"%.2f".format(aim)} hit=$hit")
                            }
                        }
                        // 갈림길
                        val f = s.fork
                        var forkView: ForkView? = null
                        if (f != null && t >= f.at) {
                            if (chosen == null && abs(deg) >= FORK_DEG) {
                                chosen = if (deg < 0) f.left else f.right
                                chosenAt = t
                                val label = if (chosen == f.left) f.leftLabel else f.rightLabel
                                toast("$label 코스로 갑니다", true)
                                burst(baseFan + 2, 1_500)
                                Log.i(TAG, "fork → $chosen (deg=$deg)")
                            }
                            val left = (f.at + f.windowSec - t).coerceAtLeast(0f)
                            forkView = ForkView(f.leftLabel, f.rightLabel, kotlin.math.ceil(left).toInt(), chosen?.let { it == f.left })
                        }

                        hud = hud.copy(
                            segment = s, segT = t, aim = aim, steerDeg = deg,
                            rings = s.rings.mapIndexedNotNull { i, r ->
                                val k = 1f - (r.t - t) / RING_LEAD_SEC
                                if (k < 0f || t > r.t + 0.6f) null else RingView(r.x, k.coerceAtMost(1f), ringMarks[i])
                            },
                            clouds = s.clouds.mapIndexedNotNull { i, c ->
                                val k = 1f - (c.t - t) / RING_LEAD_SEC
                                if (k < 0f || t > c.t + 0.6f) null else CloudView(c.x, c.w, k.coerceAtMost(1f), cloudHit[i])
                            },
                            score = score, combo = combo, ringsHit = hits, ringsTotal = ringsTotal,
                            fork = forkView, hover = hover, toast = pendingToast ?: hud.toast,
                            elapsed = elapsed,
                        )
                        pendingToast = null
                        _hud.value = hud

                        // 갈림길에서 고르면 1.5초 동안 선택을 보여 준 뒤 바로 그 코스로 출발
                        if (f != null && chosen != null && t >= chosenAt + 1.5f) break
                        delay(TICK_MS)
                    }
                    effectJobs.forEach { it.cancel() }
                    seg = when {
                        s.fork != null -> course.segment(chosen ?: s.fork.left)
                        s.next != null -> course.segment(s.next)
                        else -> null
                    }
                }

                val best = prefs.getInt("best_${course.id}", 0)
                val newBest = score > best
                if (newBest) prefs.edit().putInt("best_${course.id}", score).apply()
                pendingResult = FlightPhase.Result(score, hits, ringsTotal, route, maxOf(best, score), newBest, stopReason)
                guard.cancel()
            } finally {
                withContext(NonCancellable) {
                    // 돌풍이 복구 뒤에 바람을 덮어쓰지 않도록 먼저 끝낸다
                    burstJob?.cancelAndJoin()
                    _phase.value = FlightPhase.Restoring
                    snapshot?.let { vehicle.restore(it) }
                    val h = _hud.value
                    _phase.value = pendingResult ?: FlightPhase.Result(
                        h.score, h.ringsHit, h.ringsTotal, route, prefs.getInt("best_${course.id}", 0), false, stopReason,
                    )
                    pendingResult = null
                    hovering = false
                }
            }
        }
    }

    private var pendingResult: FlightPhase.Result? = null
    private var pendingToast: Toast? = null

    fun stop(reason: String = "중간에 멈췄어요") {
        stopReason = reason
        job?.cancel()
    }

    fun reset() {
        if (job?.isActive != true) _phase.value = FlightPhase.Idle
    }

    private fun toast(text: String, good: Boolean) {
        pendingToast = Toast(++toastId, text, good)
    }

    /** 바람 세기 (호버링 중이면 1단) */
    private fun restingFan() = if (hovering) vehicle.caps.fanMin else baseFan

    /**
     * 게임 이벤트용 짧고 강한 바람. level로 ms 동안 불고 평소 세기로 돌아간다.
     * 같은 장치에 300ms 안에 여러 번 명령을 보내지 않는다.
     */
    private fun burst(level: Int, ms: Long) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastFanCmd < MIN_CMD_MS) return
        lastFanCmd = now
        burstJob?.cancel()
        burstJob = scope.launch {
            try {
                vehicle.setFan(vehicle.clampFan(level))?.let { r -> _effects.update { it.copy(fan = r, gust = true, lastChanged = "fan") } }
                delay(ms)
            } finally {
                withContext(NonCancellable) {
                    vehicle.setFan(restingFan())?.let { r -> _effects.update { it.copy(fan = r, gust = false) } }
                        ?: _effects.update { it.copy(gust = false) }
                }
            }
        }
    }

    /** 브레이크 호버링: 바람을 1단으로 줄이고, 떼면 원래대로 */
    private fun setHover(on: Boolean) {
        hovering = on
        Log.i(TAG, "hover $on")
        if (burstJob?.isActive == true) return // 돌풍이 끝나면 restingFan()으로 돌아간다
        scope.launch {
            vehicle.setFan(restingFan())?.let { r -> _effects.update { it.copy(fan = r, gust = false, lastChanged = "fan") } }
        }
    }

    private suspend fun runEffect(e: Effect) {
        Log.i(TAG, "flight effect $e")
        when (e) {
            is Effect.Gust -> {
                burst(baseFan + e.plus, e.seconds * 1_000L)
            }
            is Effect.Window -> {
                val pct = e.percent.coerceIn(0, 20) // 안전 규칙: 최대 20%
                val pos = (vehicle.caps.windowMax * pct / 100f).roundToInt().coerceAtLeast(if (pct > 0) 1 else 0)
                vehicle.setWindow(pos)?.let { r -> _effects.update { it.copy(windowPercent = toPercent(r), lastChanged = "window") } }
            }
            is Effect.Wiper -> {
                vehicle.setWiper(true)?.let { _effects.update { it.copy(wiper = true, lastChanged = "wiper") } }
                try {
                    delay(e.seconds * 1_000L)
                } finally {
                    withContext(NonCancellable) {
                        vehicle.setWiper(false)
                        _effects.update { it.copy(wiper = false) }
                    }
                }
            }
            is Effect.SeatHeat ->
                vehicle.setSeatHeat(e.level)?.let { r -> _effects.update { it.copy(seatHeat = r, lastChanged = "seat") } }
            is Effect.Ambient ->
                vehicle.setAmbientColor(e.color)?.let { r -> _effects.update { it.copy(ambient = r, ambientName = e.name, lastChanged = "ambient") } }
            is Effect.Fan ->
                vehicle.setFan(e.level)?.let { r -> _effects.update { it.copy(fan = r, lastChanged = "fan") } }
            // 비행 코스에서는 에어컨 온도를 바꾸지 않는다
            is Effect.Temp, is Effect.Flash -> Unit
        }
    }

    private fun toPercent(position: Int) = (position * 100f / vehicle.caps.windowMax.coerceAtLeast(1)).roundToInt()
}
