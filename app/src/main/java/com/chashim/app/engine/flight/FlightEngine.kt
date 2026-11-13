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
import kotlin.math.sqrt

private const val TAG = "Chashim"

/** 핸들 각도 → 화면 끝까지 (도). 보통 감도 */
const val STEER_MAX_DEG = 60f
/** 이 각도 안의 흔들림은 무시 */
private const val DEADZONE_DEG = 3f
/** 갈림길에서 이 각도 이상 꺾으면 선택 */
private const val FORK_DEG = 45f
/** 브레이크를 밟고 있으면 초당 이만큼 내려가고, 떼면 이만큼 올라온다 (높이 0~1) */
private const val DIVE_PER_SEC = 0.85f
private const val RISE_PER_SEC = 0.6f
/** 별빛을 받았다고 볼 거리 */
private const val STAR_HIT_X = 0.15f
private const val STAR_HIT_V = 0.2f
/** 바람길 안에 있으면 초당 기본 점수 */
private const val WIND_POINTS_PER_SEC = 20f
/** 게임 루프 주기 */
private const val TICK_MS = 50L
/** 같은 장치에 명령을 보내는 최소 간격 */
private const val MIN_CMD_MS = 300L
/** 별빛을 받으면 무드등이 이 색으로 잠깐 반짝인다 */
private const val STAR_GOLD = 0xFFD45A

enum class Mark { PENDING, HIT, MISS }

/** chosenLeft: 아직 안 골랐으면 null */
data class ForkView(val leftLabel: String, val rightLabel: String, val secLeft: Int, val chosenLeft: Boolean?)

/** 화면에 띄우는 짧은 알림 (id가 바뀔 때마다 새로 보여 준다) */
data class Toast(val id: Int, val text: String, val good: Boolean)

/** 비행 화면이 그리는 모든 값 */
data class FlightHud(
    val segment: Segment? = null,
    val segT: Float = 0f,
    /** 조준점 가로 (-1 ~ 1) */
    val aim: Float = 0f,
    /** 조준점 높이 (0 위 ~ 1 아래). 브레이크로 조절 */
    val aimV: Float = 0f,
    /** 오른쪽으로 꺾은 핸들 각도 */
    val steerDeg: Float = 0f,
    val diving: Boolean = false,
    /** 바람길과 얼마나 가까운지: 0 멀다 ~ 3 바람길 안 (차 바람 세기에 그대로 반영) */
    val windLevel: Int = 0,
    val inWind: Boolean = false,
    /** 바람길 안에 계속 머문 배수 (1~3) */
    val multiplier: Int = 1,
    /** 지금 바람길이 숨은 구간인지 */
    val hidden: Boolean = false,
    /** 지금까지 바람길에 머문 비율 (%) */
    val windPct: Int = 0,
    val cloudHit: List<Boolean?> = emptyList(),
    val starMarks: List<Mark> = emptyList(),
    val starAt: List<Float> = emptyList(),
    val starsCaught: Int = 0,
    val starsTotal: Int = 0,
    val score: Int = 0,
    val fork: ForkView? = null,
    val shake: Int = 0,
    val toast: Toast? = null,
    val elapsed: Float = 0f,
    val totalSec: Int = 0,
)

sealed interface FlightPhase {
    data object Idle : FlightPhase
    data object Preparing : FlightPhase
    /** 핸들·브레이크 확인 */
    data class Calibrate(val secLeft: Int) : FlightPhase
    data class Countdown(val n: Int) : FlightPhase
    data object Flying : FlightPhase
    data object Restoring : FlightPhase
    data class Result(
        val score: Int,
        val windPct: Int,
        val starsCaught: Int,
        val starsTotal: Int,
        val route: List<String>,
        val best: Int,
        val newBest: Boolean,
        val reason: String,
    ) : FlightPhase
}

/**
 * 엔터테인 모드 비행: 바람길 타기.
 * - 핸들 → 좌우 (데드존 3°, ±60° = 화면 끝, 떨림 필터), 브레이크 → 밟으면 내려가고 떼면 올라온다
 * - 바람길에 가까울수록 실제 차 바람이 세진다 (기본 세기 + 0~3단). 바람길 안에 머물면 점수가 쌓이고 배수가 오른다
 * - 바람길이 숨는 구간에서는 바람 세기만으로 길을 찾는다
 * - 별빛 받기 (무드등 반짝임), 먹구름 피하기 (부딪히면 바람이 멎고 화면이 흔들림), 갈림길
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
    private fun braking() = brakeOverride ?: vehicle.brakePressed.value

    /** 테스트용: 핸들 각도(오른쪽 +)·브레이크를 이 값으로 가정 (null이면 차 값) */
    var steerOverride: Float? = null
    var brakeOverride: Boolean? = null

    private var job: Job? = null
    private var snapshot: Snapshot? = null
    private var stopReason = "무사히 착륙했어요"
    private var baseFan = 1
    private var toastId = 0
    private var pendingToast: Toast? = null
    private var pendingResult: FlightPhase.Result? = null

    // 바람 (바람길 피드백 + 이벤트 돌풍)
    private var windLevel = 0
    private var sentFan: Int? = null
    private var lastFanCmd = 0L
    private var burstUntil = 0L
    private var burstLevel = 0
    private var fanJob: Job? = null

    // 무드등 반짝임
    private var ambientJob: Job? = null
    private var lastAmbientCmd = 0L
    private var sceneAmbient: Int? = null

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
                sentFan = snapshot?.fan
                windLevel = 0
                burstUntil = 0L
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
                val total = generateSequence(first) { s -> (s.next ?: s.fork?.left)?.let(course::segment) }.sumOf { it.durationSec }
                hud = FlightHud(segment = first, totalSec = total)

                var smoothed = 0f
                var aimV = 0f
                var last = SystemClock.elapsedRealtime()
                fun readInput(): Float {
                    val now = SystemClock.elapsedRealtime()
                    val dt = ((now - last) / 1000f).coerceAtMost(0.25f)
                    last = now
                    val deg = steerRight()
                    val a = if (abs(deg) < DEADZONE_DEG) 0f else deg
                    val n = (a / STEER_MAX_DEG).coerceIn(-1f, 1f)
                    smoothed += 0.2f * (n - smoothed)
                    aimV = (aimV + (if (braking()) DIVE_PER_SEC else -RISE_PER_SEC) * dt).coerceIn(0f, 1f)
                    return dt
                }

                // 1) 핸들·브레이크 확인 3초, 2) 카운트다운 3-2-1
                for (phase in listOf(3, 2, 1).map { FlightPhase.Calibrate(it) } + listOf(3, 2, 1).map { FlightPhase.Countdown(it) }) {
                    _phase.value = phase
                    repeat((1000 / TICK_MS).toInt()) {
                        readInput()
                        hud = hud.copy(aim = smoothed, aimV = aimV, steerDeg = steerRight(), diving = braking())
                        _hud.value = hud
                        delay(TICK_MS)
                    }
                }
                _phase.value = FlightPhase.Flying

                var seg: Segment? = first
                var elapsed = 0f
                var windTime = 0f
                var scoreF = 0f
                var streak = 0f
                var multiplier = 1
                var starsCaught = 0
                var starsTotal = 0
                var wasHidden = false
                while (seg != null) {
                    val s = seg
                    route += s.title
                    Log.i(TAG, "flight segment ${s.id}")
                    val pendingEffects = s.effects.sortedBy { it.at }.toMutableList()
                    val cloudHit = MutableList<Boolean?>(s.clouds.size) { null }
                    val starMarks = MutableList(s.stars.size) { Mark.PENDING }
                    val starAt = MutableList(s.stars.size) { 0f }
                    var chosen: String? = null
                    var chosenAt = 0f
                    var t = 0f
                    val effectJobs = mutableListOf<Job>()
                    while (t < s.durationSec) {
                        val dt = readInput()
                        t += dt
                        elapsed += dt
                        val aim = smoothed
                        val deg = steerRight()

                        // 구간 기본 효과
                        while (pendingEffects.isNotEmpty() && pendingEffects.first().at <= t) {
                            val e = pendingEffects.removeAt(0)
                            effectJobs += launch { runEffect(e) }
                        }

                        // 바람길: 얼마나 가까운지 → 차 바람 세기, 안에 있으면 점수
                        val (wx, wv) = s.windAt(t)
                        val dist = sqrt(((aim - wx) / WIND_HALF_X).let { it * it } + ((aimV - wv) / WIND_HALF_V).let { it * it })
                        val inWind = dist <= 1f
                        val level = when {
                            dist <= 1f -> 3
                            dist <= 1.8f -> 2
                            dist <= 2.8f -> 1
                            else -> 0
                        }
                        setWindLevel(level)
                        if (inWind) {
                            windTime += dt
                            streak += dt
                            val m = 1 + (streak / 3f).toInt().coerceAtMost(2)
                            if (m > multiplier) toast("순풍 ×$m", true)
                            multiplier = m
                            scoreF += WIND_POINTS_PER_SEC * multiplier * dt
                        } else {
                            streak = 0f
                            multiplier = 1
                        }

                        // 숨은 바람길 구간 안내
                        val hidden = s.isHidden(t)
                        if (hidden != wasHidden) {
                            toast(if (hidden) "바람길이 사라졌어요 · 바람이 세지는 쪽을 찾아보세요" else "바람길이 다시 보여요", true)
                            wasHidden = hidden
                        }

                        // 별빛
                        s.stars.forEachIndexed { i, st ->
                            if (starMarks[i] == Mark.PENDING && t >= st.t) {
                                starsTotal++
                                starAt[i] = t
                                if (abs(aim - st.x) <= STAR_HIT_X && abs(aimV - st.v) <= STAR_HIT_V) {
                                    starMarks[i] = Mark.HIT
                                    starsCaught++
                                    scoreF += if (st.big) 150 else 50
                                    if (st.big) {
                                        toast("황금 별 +150", true)
                                        pulseAmbient(0xFFFFFF, 500)
                                        burst(level = 4, ms = 900)
                                    } else {
                                        pulseAmbient(STAR_GOLD, 350)
                                    }
                                } else {
                                    starMarks[i] = Mark.MISS
                                }
                                Log.i(TAG, "star ${s.id}#$i (${st.x},${st.v}) aim=(${"%.2f".format(aim)},${"%.2f".format(aimV)}) → ${starMarks[i]}")
                            }
                        }
                        // 먹구름
                        s.clouds.forEachIndexed { i, c ->
                            if (cloudHit[i] == null && t >= c.t) {
                                val hit = abs(aim - c.x) <= c.w / 2 && abs(aimV - c.v) <= CLOUD_HALF_V
                                cloudHit[i] = hit
                                if (hit) {
                                    scoreF = (scoreF - 100f).coerceAtLeast(0f)
                                    streak = 0f
                                    multiplier = 1
                                    hud = hud.copy(shake = hud.shake + 1)
                                    toast("먹구름 충돌 -100", false)
                                    burst(level = -99, ms = 1_200) // 바람이 순간 멎는다
                                } else {
                                    toast("먹구름을 피했어요", true)
                                }
                                Log.i(TAG, "cloud ${s.id}#$i aim=(${"%.2f".format(aim)},${"%.2f".format(aimV)}) hit=$hit")
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
                                Log.i(TAG, "fork → $chosen (deg=$deg)")
                            }
                            val left = (f.at + f.windowSec - t).coerceAtLeast(0f)
                            forkView = ForkView(f.leftLabel, f.rightLabel, kotlin.math.ceil(left).toInt(), chosen?.let { it == f.left })
                        }

                        hud = hud.copy(
                            segment = s, segT = t, aim = aim, aimV = aimV, steerDeg = deg, diving = braking(),
                            windLevel = level, inWind = inWind, multiplier = multiplier, hidden = hidden,
                            windPct = if (elapsed > 0f) (windTime / elapsed * 100f).roundToInt() else 0,
                            cloudHit = cloudHit.toList(), starMarks = starMarks.toList(), starAt = starAt.toList(),
                            starsCaught = starsCaught, starsTotal = starsTotal,
                            score = scoreF.roundToInt(), fork = forkView, toast = pendingToast ?: hud.toast,
                            elapsed = elapsed,
                        )
                        pendingToast = null
                        _hud.value = hud

                        // 갈림길에서 고르면 1.5초 동안 선택을 보여 준 뒤 그 코스로 출발
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

                val score = scoreF.roundToInt()
                val windPct = if (elapsed > 0f) (windTime / elapsed * 100f).roundToInt() else 0
                val best = prefs.getInt("best_${course.id}", 0)
                val newBest = score > best
                if (newBest) prefs.edit().putInt("best_${course.id}", score).apply()
                pendingResult = FlightPhase.Result(score, windPct, starsCaught, starsTotal, route, maxOf(best, score), newBest, stopReason)
                guard.cancel()
            } finally {
                withContext(NonCancellable) {
                    // 바람·무드등 명령이 복구 뒤에 덮어쓰지 않도록 먼저 끝낸다
                    fanJob?.cancelAndJoin()
                    ambientJob?.cancelAndJoin()
                    windLevel = 0
                    burstUntil = 0L
                    _phase.value = FlightPhase.Restoring
                    snapshot?.let { vehicle.restore(it) }
                    val h = _hud.value
                    _phase.value = pendingResult ?: FlightPhase.Result(
                        h.score, h.windPct, h.starsCaught, h.starsTotal, route, prefs.getInt("best_${course.id}", 0), false, stopReason,
                    )
                    pendingResult = null
                }
            }
        }
    }

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

    // ---- 바람 ----

    /** 지금 차에 보내야 할 바람 세기: 이벤트 돌풍 중이면 그 값, 아니면 기본 세기 + 바람길 가까움 */
    private fun desiredFan(): Int {
        val now = SystemClock.elapsedRealtime()
        val level = if (now < burstUntil) burstLevel else windLevel
        return vehicle.clampFan(if (level < -10) vehicle.caps.fanMin else baseFan + level)
    }

    /** 바람길 가까움이 바뀌면 바람 세기를 맞춘다 */
    private fun setWindLevel(level: Int) {
        windLevel = level
        syncFan()
    }

    /** 이벤트 돌풍: level(기본 세기 기준 +단, -99면 최저)으로 ms 동안 */
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
            if (r != null) {
                _effects.update { it.copy(fan = r, gust = want > baseFan, lastChanged = "fan") }
            } else {
                sentFan = null // 실패하면 다음에 다시 보낸다
            }
        }
    }

    // ---- 무드등 ----

    /** 별빛을 받으면 무드등이 잠깐 반짝이고 구간 색으로 돌아간다 */
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

    // ---- 구간 기본 효과 ----

    private suspend fun runEffect(e: Effect) {
        Log.i(TAG, "flight effect $e")
        when (e) {
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
            is Effect.Ambient -> {
                sceneAmbient = e.color
                vehicle.setAmbientColor(e.color)?.let { r -> _effects.update { it.copy(ambient = r, ambientName = e.name, lastChanged = "ambient") } }
            }
            // 바람은 바람길이 맡고, 에어컨 온도는 바꾸지 않는다
            is Effect.Gust, is Effect.Fan, is Effect.Temp, is Effect.Flash -> Unit
        }
    }

    private fun toPercent(position: Int) = (position * 100f / vehicle.caps.windowMax.coerceAtLeast(1)).roundToInt()
}
