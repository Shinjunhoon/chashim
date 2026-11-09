package com.chashim.app.engine

import ai.pleos.playground.vehicle.constant.control.VehicleGear
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

private const val TAG = "Chashim"

/** 충전 목표 (%). SDK로 차의 충전 한도를 읽을 수 없어서 급속충전 권장값을 쓴다 */
const val CHARGE_TARGET = 80

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
    /** 방금 바뀐 항목. 패널에서 강조 표시한다 */
    val lastChanged: String? = null,
)

sealed interface SessionPhase {
    data object Idle : SessionPhase
    data object Preparing : SessionPhase
    data class Playing(val sceneIndex: Int, val elapsedSec: Int) : SessionPhase
    data object Restoring : SessionPhase
    data class Finished(val reason: String) : SessionPhase
}

/**
 * 테마를 처음부터 끝까지 재생한다.
 * 1) 시작 전 차 상태 저장 → 2) 장면마다 정해진 시점에 효과 실행 → 3) 끝나거나 멈추면 원래대로 복구.
 * 기어가 P에서 벗어나면 즉시 멈춘다.
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

    /** 테스트용: 실내 온도를 이 값으로 가정한다 (null이면 차에서 읽은 값) */
    var cabinOverride: Float? = null

    /** 화면 플래시 신호 (값이 바뀔 때마다 한 번 번쩍) */
    private val _flash = MutableStateFlow(0)
    val flash: StateFlow<Int> = _flash.asStateFlow()

    private var job: Job? = null
    private var guardJob: Job? = null
    private var snapshot: Snapshot? = null
    private var stopReason = "끝까지 쉬었어요"

    fun start(theme: Theme) {
        if (job?.isActive == true) return
        stopReason = "끝까지 쉬었어요"
        job = scope.launch {
            try {
                _phase.value = SessionPhase.Preparing
                vehicle.refreshCapabilitiesIfMissing()
                // 실내 온도를 읽고 효과를 맞춘다
                val cabin = cabinOverride ?: vehicle.cabinTemp()
                val (adapted, adapt) = adaptToCabin(theme, cabin)
                _climate.value = adapt
                Log.i(TAG, "cabin $cabin → ${adapt?.feel}")
                snapshot = vehicle.snapshot()
                _effects.value = EffectState(
                    fan = snapshot?.fan, temp = snapshot?.temp, seatHeat = snapshot?.seatHeat,
                    windowPercent = snapshot?.window?.let { toPercent(it) },
                    ambient = snapshot?.ambientColor,
                )
                vehicle.setPower(true)
                startGuard()
                adapted.scenes.forEachIndexed { i, scene -> playScene(i, scene) }
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

    /**
     * 실행 조건 감시
     * - 기어가 P가 아니면 즉시 정지
     * - 충전 중에 시작했다면: 충전기를 뽑거나 목표 %에 도달하면 마무리
     */
    private fun startGuard() {
        guardJob = scope.launch {
            launch {
                vehicle.gearState.collect { g ->
                    if (g != null && g != VehicleGear.GEAR_PARK) {
                        Log.i(TAG, "gear changed to $g → stop")
                        stop("기어가 바뀌어 안전하게 멈췄어요")
                    }
                }
            }
            if (vehicle.charging.value) {
                val startSoc = vehicle.battery.value
                launch {
                    combine(vehicle.charging, vehicle.battery) { c, soc -> c to soc }.collect { (c, soc) ->
                        when {
                            !c -> {
                                Log.i(TAG, "charge port closed → finish")
                                stop("충전기를 분리했어요. 출발 준비가 됐어요")
                            }
                            soc != null && soc >= CHARGE_TARGET && (startSoc == null || startSoc < CHARGE_TARGET) -> {
                                Log.i(TAG, "charge target reached ($soc%) → finish")
                                stop("충전이 끝났어요 ($soc%). 출발하셔도 돼요")
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 효과는 이 장면의 자식 작업으로 실행한다.
     * 세션이 멈추면 진행 중인 효과도 함께 멈춘 뒤에 복구가 시작된다.
     * 따로 실행하면 복구가 끝난 뒤에도 효과가 차 설정을 덮어쓴다.
     */
    private suspend fun playScene(index: Int, scene: Scene) = coroutineScope {
        Log.i(TAG, "scene ${scene.id}")
        val pending = scene.effects.sortedBy { it.at }.toMutableList()
        for (sec in 0 until scene.durationSec) {
            _phase.value = SessionPhase.Playing(index, sec)
            while (pending.isNotEmpty() && pending.first().at <= sec) {
                val e = pending.removeAt(0)
                launch { run(e) }
            }
            delay(1_000)
        }
        coroutineContext.cancelChildren()
    }

    private suspend fun run(e: Effect) {
        Log.i(TAG, "effect $e")
        when (e) {
            is Effect.Fan -> {
                val from = _effects.value.fan ?: e.level
                val steps = stepsBetween(from, e.level)
                val wait = if (steps.size > 1) e.rampSec * 1_000L / steps.size else 0L
                for (v in steps) {
                    vehicle.setFan(v)?.let { r -> _effects.update { it.copy(fan = r, lastChanged = "fan") } }
                    delay(wait)
                }
            }
            is Effect.Temp -> {
                val from = _effects.value.temp ?: e.celsius
                val n = (abs(e.celsius - from) / 0.5f).roundToInt().coerceAtLeast(1)
                val wait = e.rampSec * 1_000L / n
                for (i in 1..n) {
                    val v = from + (e.celsius - from) * i / n
                    vehicle.setTemp(v)?.let { r -> _effects.update { it.copy(temp = r, lastChanged = "temp") } }
                    delay(wait)
                }
            }
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

    private fun stepsBetween(from: Int, to: Int): List<Int> =
        if (from == to) listOf(to) else if (to > from) ((from + 1)..to).toList() else (from - 1 downTo to).toList()

    private fun toPercent(position: Int) = (position * 100f / vehicle.caps.windowMax.coerceAtLeast(1)).roundToInt()
}
