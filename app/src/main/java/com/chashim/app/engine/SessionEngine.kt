package com.chashim.app.engine

import ai.pleos.playground.vehicle.constant.control.VehicleGear
import android.util.Log
import com.chashim.app.vehicle.Snapshot
import com.chashim.app.vehicle.VehicleGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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

/** 4D 효과 패널에 보여 줄 현재 차 상태 (차가 '성공'이라고 답한 값만 반영) */
data class EffectState(
    val fan: Int? = null,
    val temp: Float? = null,
    val seatHeat: Int? = null,
    val windowPercent: Int? = null,
    val wiper: Boolean = false,
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
                snapshot = vehicle.snapshot()
                _effects.value = EffectState(
                    fan = snapshot?.fan, temp = snapshot?.temp, seatHeat = snapshot?.seatHeat,
                    windowPercent = snapshot?.window?.let { toPercent(it) },
                )
                vehicle.setPower(true)
                startGuard()
                theme.scenes.forEachIndexed { i, scene -> playScene(i, scene) }
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

    /** 실행 조건 감시: 기어가 P가 아니면 즉시 정지 */
    private fun startGuard() {
        guardJob = scope.launch {
            vehicle.gearState.collect { g ->
                if (g != null && g != VehicleGear.GEAR_PARK) {
                    Log.i(TAG, "gear changed to $g → stop")
                    stop("기어가 바뀌어 안전하게 멈췄어요")
                }
            }
        }
    }

    private suspend fun playScene(index: Int, scene: Scene) {
        Log.i(TAG, "scene ${scene.id}")
        val pending = scene.effects.sortedBy { it.at }.toMutableList()
        val effectJobs = mutableListOf<Job>()
        for (sec in 0 until scene.durationSec) {
            _phase.value = SessionPhase.Playing(index, sec)
            while (pending.isNotEmpty() && pending.first().at <= sec) {
                val e = pending.removeAt(0)
                effectJobs += scope.launch { run(e) }
            }
            delay(1_000)
        }
        effectJobs.forEach { it.cancel() }
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
            is Effect.Flash -> _flash.update { it + 1 }
        }
    }

    private fun stepsBetween(from: Int, to: Int): List<Int> =
        if (from == to) listOf(to) else if (to > from) ((from + 1)..to).toList() else (from - 1 downTo to).toList()

    private fun toPercent(position: Int) = (position * 100f / vehicle.caps.windowMax.coerceAtLeast(1)).roundToInt()
}
