package com.chashim.app.engine

import ai.pleos.playground.vehicle.constant.control.VehicleGear
import android.os.SystemClock
import android.util.Log
import com.chashim.app.vehicle.Snapshot
import com.chashim.app.vehicle.VehicleGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private const val TAG = "Chashim"

/** 충전 목표 (%). SDK로 차의 충전 한도를 읽을 수 없어서 급속충전 권장값을 쓴다 */
const val CHARGE_TARGET = 80

/** 충전이 먼저 끝났을 때 마무리 장면을 보여 주는 시간 (초) */
private const val SHORT_OUTRO_SEC = 15

/** 중간 장면을 이보다 짧게 자르지 않는다 (초) */
private const val MIN_SCENE_SEC = 10

/** 4D 효과 패널에 보여 줄 현재 차 상태 (차가 '성공'이라고 답한 값만 반영) */
data class EffectState(
    val fan: Int? = null,
    val temp: Float? = null,
    val seatHeat: Int? = null,
    val windowPercent: Int? = null,
    val wiper: Boolean = false,
    /** 무드등 색 (0xRRGGBB)과 이름 */
    val ambient: Int? = null,
    val ambientName: String? = null,
    /** 돌풍이 부는 중인지 */
    val gust: Boolean = false,
    /** 방금 바뀐 항목. 패널에서 강조 표시한다 */
    val lastChanged: String? = null,
)

sealed interface SessionPhase {
    data object Idle : SessionPhase
    data object Preparing : SessionPhase

    /**
     * @param sceneIndex 테마 안 장면 번호
     * @param elapsedSec 이 장면에서 지난 시간
     * @param sessionElapsed 세션 전체에서 지난 시간
     * @param sessionTotal 세션 전체 예상 길이. 충전 속도를 재는 중이면 null
     */
    data class Playing(
        val sceneIndex: Int,
        val elapsedSec: Int,
        val sessionElapsed: Int,
        val sessionTotal: Int?,
    ) : SessionPhase

    data object Restoring : SessionPhase
    data class Finished(val reason: String) : SessionPhase
}

/**
 * 휴식 세션을 재생한다.
 * 1) 시작 전 차 상태 저장
 * 2) 장면마다 정해진 시점에 효과 실행
 *    - 충전 중이 아니면 장면을 한 번씩 (약 3분)
 *    - 충전 중이면 도입 → 중간 장면 반복 → 충전 완료 시점에 마무리 장면
 * 3) 끝나거나 멈추면 원래대로 복구
 * 기어가 P에서 벗어나거나 충전기를 분리하면 즉시 멈춘다.
 */
class SessionEngine(
    private val vehicle: VehicleGateway,
    private val scope: CoroutineScope,
) {
    private val _phase = MutableStateFlow<SessionPhase>(SessionPhase.Idle)
    val phase: StateFlow<SessionPhase> = _phase.asStateFlow()

    private val _effects = MutableStateFlow(EffectState())
    val effects: StateFlow<EffectState> = _effects.asStateFlow()

    /** 실내 온도에 맞춰 조절한 내용 (세션마다 새로 판단) */
    private val _climate = MutableStateFlow<ClimateAdapt?>(null)
    val climate: StateFlow<ClimateAdapt?> = _climate.asStateFlow()

    /** 충전 완료까지 예상 (충전 중일 때만) */
    private val _charge = MutableStateFlow<ChargeEstimate?>(null)
    val charge: StateFlow<ChargeEstimate?> = _charge.asStateFlow()
    private val estimator = ChargeEstimator()

    /** 테스트용: 실내 온도를 이 값으로 가정한다 (null이면 차에서 읽은 값) */
    var cabinOverride: Float? = null

    /** 테스트용: 충전 속도를 이 값(kW)으로 가정한다 */
    var assumedChargeKw: Float?
        get() = estimator.assumedKw
        set(v) { estimator.assumedKw = v }

    /** 화면 플래시 신호 (값이 바뀔 때마다 한 번 번쩍) */
    private val _flash = MutableStateFlow(0)
    val flash: StateFlow<Int> = _flash.asStateFlow()

    private var job: Job? = null
    private var guardJob: Job? = null
    private var snapshot: Snapshot? = null
    private var stopReason = "끝까지 쉬었어요"

    /** 운전자가 맞춰 둔 바람 세기. 돌풍이 끝나면 여기로 돌아간다 */
    private var baseFan = 1

    /** 충전 목표에 도달했는지 (세션 중) */
    @Volatile private var chargeDone = false

    /**
     * 충전 상태를 계속 지켜보며 완료 예상 시간을 계산한다. 앱이 켜져 있는 동안 한 번만 시작한다.
     */
    fun watchCharging() {
        scope.launch {
            while (isActive) {
                val now = SystemClock.elapsedRealtime()
                if (vehicle.charging.value) {
                    val cap = vehicle.capacityWh
                    val soc = vehicle.battery.value
                    if (soc != null && cap != null) estimator.addSoc(now, soc, cap)
                    vehicle.energyWh.value?.let { estimator.addLevel(now, it) }
                    _charge.value = estimator.estimate(now, cap)
                } else {
                    estimator.reset()
                    _charge.value = null
                }
                delay(1_000)
            }
        }
    }

    fun start(theme: Theme) {
        if (job?.isActive == true) return
        stopReason = "끝까지 쉬었어요"
        chargeDone = false
        job = scope.launch {
            try {
                _phase.value = SessionPhase.Preparing
                vehicle.refreshCapabilitiesIfMissing()
                // 실내 온도를 읽고 열선·돌풍을 맞춘다 (에어컨 온도는 건드리지 않는다)
                val cabin = cabinOverride ?: vehicle.cabinTemp()
                val (adapted, adapt) = adaptToCabin(theme, cabin)
                _climate.value = adapt
                Log.i(TAG, "cabin $cabin → ${adapt?.feel}")

                snapshot = vehicle.snapshot()
                baseFan = vehicle.clampFan(snapshot?.fan ?: vehicle.caps.fanMin)
                _effects.value = EffectState(
                    fan = snapshot?.fan, temp = snapshot?.temp, seatHeat = snapshot?.seatHeat,
                    windowPercent = snapshot?.window?.let { toPercent(it) },
                    ambient = snapshot?.ambientColor,
                )
                vehicle.setPower(true)
                val charging = vehicle.charging.value
                startGuard(charging)
                if (charging) playForCharging(adapted) else playOnce(adapted)
            } finally {
                guardJob?.cancel()
                withContext(NonCancellable) {
                    _phase.value = SessionPhase.Restoring
                    snapshot?.let { vehicle.restore(it) }
                    _phase.value = SessionPhase.Finished(stopReason)
                }
            }
        }
    }

    fun stop(reason: String = "중간에 멈췄어요") {
        stopReason = reason
        job?.cancel()
    }

    fun reset() {
        if (job?.isActive != true) _phase.value = SessionPhase.Idle
    }

    // ---- 재생 계획 ----

    private var sessionElapsed = 0

    /** 충전 중이 아닐 때: 모든 장면을 한 번씩 */
    private suspend fun playOnce(theme: Theme) {
        sessionElapsed = 0
        val total = theme.totalSec
        theme.scenes.forEachIndexed { i, scene -> playScene(i, scene, scene.durationSec, { total }) }
    }

    /**
     * 충전 중일 때: 도입 → 중간 장면 반복 → 마무리.
     * 마무리 장면이 충전 완료 시점에 끝나도록 중간 장면 길이를 맞춘다.
     * 예상보다 일찍 충전이 끝나면 바로 짧은 마무리로 넘어간다.
     */
    private suspend fun playForCharging(theme: Theme) {
        sessionElapsed = 0
        val indexed = theme.scenes.withIndex()
        val intro = indexed.filter { it.value.role == SceneRole.INTRO }
        val middle = indexed.filter { it.value.role == SceneRole.MIDDLE }
        val outro = indexed.first { it.value.role == SceneRole.OUTRO }
        val outroLen = outro.value.durationSec

        // 세션 전체 예상 길이 = 지난 시간 + 충전 완료까지 남은 시간
        val total: () -> Int? = { _charge.value?.remainingSec?.let { sessionElapsed + it } }
        fun remaining() = _charge.value?.remainingSec

        for ((i, scene) in intro) {
            if (chargeDone) break
            playScene(i, scene, scene.durationSec, total) { chargeDone }
        }

        var m = 0
        while (!chargeDone) {
            val left = remaining()
            if (left != null && left <= outroLen) break
            val (i, scene) = middle[m % middle.size]
            val len = if (left == null) scene.durationSec
            else (left - outroLen).coerceIn(MIN_SCENE_SEC, scene.durationSec)
            Log.i(TAG, "middle ${scene.id} ${len}s (charge left ${left}s)")
            playScene(i, scene, len, total) {
                // 충전이 끝났거나, 이제 마무리 장면을 시작해야 하면 장면을 끊는다
                chargeDone || (remaining()?.let { it <= outroLen } ?: false)
            }
            m++
        }

        val outroSec = when {
            chargeDone -> SHORT_OUTRO_SEC
            else -> (remaining() ?: outroLen).coerceIn(SHORT_OUTRO_SEC, outroLen)
        }
        Log.i(TAG, "outro ${outroSec}s (charge done=$chargeDone)")
        playScene(outro.index, outro.value, outroSec, total)
        val soc = vehicle.battery.value
        stopReason = if (chargeDone || (soc != null && soc >= CHARGE_TARGET)) {
            "충전이 끝났어요 (${soc ?: CHARGE_TARGET}%). 출발하셔도 돼요"
        } else {
            "충전이 거의 끝났어요 (${soc ?: "-"}%)"
        }
    }

    /**
     * 실행 조건 감시
     * - 기어가 P가 아니면 즉시 정지
     * - 충전 중에 시작했다면: 충전기를 뽑으면 즉시 정지, 목표 %에 도달하면 마무리 장면으로
     */
    private fun startGuard(charging: Boolean) {
        guardJob = scope.launch {
            launch {
                vehicle.gearState.collect { g ->
                    if (g != null && g != VehicleGear.GEAR_PARK) {
                        Log.i(TAG, "gear changed to $g → stop")
                        stop("기어가 바뀌어 안전하게 멈췄어요")
                    }
                }
            }
            if (charging) {
                val startSoc = vehicle.battery.value
                launch {
                    vehicle.charging.collect { c ->
                        if (!c) {
                            Log.i(TAG, "charge port closed → stop")
                            stop("충전기를 분리했어요. 출발 준비가 됐어요")
                        }
                    }
                }
                launch {
                    vehicle.battery.collect { soc ->
                        if (soc != null && soc >= CHARGE_TARGET && (startSoc == null || startSoc < CHARGE_TARGET) && !chargeDone) {
                            Log.i(TAG, "charge target reached ($soc%) → outro")
                            chargeDone = true
                        }
                    }
                }
            }
        }
    }

    /**
     * 장면 하나를 lengthSec초 재생한다. cut()이 true가 되면 일찍 끝낸다.
     * 효과는 이 장면의 자식 작업으로 실행한다. 세션이 멈추면 진행 중인 효과도 함께 멈춘 뒤에 복구가 시작된다.
     */
    private suspend fun playScene(
        index: Int,
        scene: Scene,
        lengthSec: Int,
        total: () -> Int?,
        cut: () -> Boolean = { false },
    ) = coroutineScope {
        Log.i(TAG, "scene ${scene.id}")
        val pending = scene.effects.sortedBy { it.at }.toMutableList()
        for (sec in 0 until lengthSec) {
            if (sec > 0 && cut()) break
            _phase.value = SessionPhase.Playing(index, sec, sessionElapsed, total())
            while (pending.isNotEmpty() && pending.first().at <= sec) {
                val e = pending.removeAt(0)
                launch { run(e) }
            }
            delay(1_000)
            sessionElapsed++
        }
        coroutineContext.cancelChildren()
    }

    private suspend fun run(e: Effect) {
        Log.i(TAG, "effect $e")
        when (e) {
            is Effect.Gust -> {
                val strong = vehicle.clampFan(baseFan + e.plus)
                vehicle.setFan(strong)?.let { r -> _effects.update { it.copy(fan = r, gust = true, lastChanged = "fan") } }
                try {
                    delay(e.seconds * 1_000L)
                } finally {
                    withContext(NonCancellable) {
                        vehicle.setFan(baseFan)?.let { r -> _effects.update { it.copy(fan = r, gust = false) } }
                            ?: _effects.update { it.copy(gust = false) }
                    }
                }
            }
            is Effect.Fan ->
                vehicle.setFan(e.level)?.let { r -> _effects.update { it.copy(fan = r, lastChanged = "fan") } }
            is Effect.Temp ->
                vehicle.setTemp(e.celsius)?.let { r -> _effects.update { it.copy(temp = r, lastChanged = "temp") } }
            is Effect.SeatHeat ->
                vehicle.setSeatHeat(e.level)?.let { r -> _effects.update { it.copy(seatHeat = r, lastChanged = "seat") } }
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
            is Effect.Flash -> {
                _flash.update { it + 1 }
                // 무드등도 번개처럼 잠깐 하얗게
                val back = _effects.value.ambient
                if (back != null && vehicle.setAmbientColor(0xFFFFFF) != null) {
                    try {
                        delay(250)
                    } finally {
                        withContext(NonCancellable) { vehicle.setAmbientColor(back) }
                    }
                }
            }
            is Effect.Ambient ->
                vehicle.setAmbientColor(e.color)?.let { r ->
                    _effects.update { it.copy(ambient = r, ambientName = e.name, lastChanged = "ambient") }
                }
        }
    }

    private fun toPercent(position: Int) = (position * 100f / vehicle.caps.windowMax.coerceAtLeast(1)).roundToInt()
}
