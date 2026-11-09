package com.chashim.app.engine

import kotlin.math.max
import kotlin.math.roundToInt

/** 충전 완료(목표 %)까지 예상 */
data class ChargeEstimate(
    /** 충전 속도 (kW). 아직 모르면 null */
    val rateKw: Float?,
    /** 목표까지 남은 시간 (초). 아직 모르면 null */
    val remainingSec: Int?,
    /** 테스트용으로 가정한 속도인지 */
    val assumed: Boolean,
)

/**
 * 배터리 에너지가 늘어나는 속도로 충전 속도를 재고, 목표 %까지 남은 시간을 계산한다.
 * SDK에 충전 속도(kW) 값이 없어서 직접 잰다.
 *  - 목표까지 남은 양: 배터리 % 로 판단 (충전 목표와 같은 기준)
 *  - 충전 속도: 배터리 에너지(Wh)가 늘어나는 속도. 에너지 값이 안 변하면 % 변화로 잰다
 *  - MIN_SPAN_MS 이상 지켜봐서 늘었을 때부터 속도를 계산한다
 */
class ChargeEstimator(private val targetPct: Int = CHARGE_TARGET) {

    /** 테스트용: 충전 속도를 이 값(kW)으로 가정 */
    var assumedKw: Float? = null

    /** 배터리 % 기준 에너지 (Wh). 목표까지 얼마나 남았는지 판단 */
    private val socSamples = ArrayDeque<Pair<Long, Float>>()
    /** 배터리 에너지 값 (Wh). 더 세밀해서 속도 재기에 좋다 */
    private val levelSamples = ArrayDeque<Pair<Long, Float>>()

    fun reset() {
        socSamples.clear()
        levelSamples.clear()
    }

    fun addSoc(nowMs: Long, socPct: Int, capacityWh: Float) = push(socSamples, nowMs, capacityWh * socPct / 100f)
    fun addLevel(nowMs: Long, wh: Float) = push(levelSamples, nowMs, wh)

    private fun push(q: ArrayDeque<Pair<Long, Float>>, nowMs: Long, wh: Float) {
        if (q.lastOrNull()?.second == wh) return
        q.addLast(nowMs to wh)
        // 최근 10분만 사용
        while (q.size > 2 && nowMs - q.first().first > 10 * 60_000) q.removeFirst()
    }

    fun estimate(nowMs: Long, capacityWh: Float?): ChargeEstimate {
        val cap = capacityWh?.takeIf { it > 0 } ?: return ChargeEstimate(null, null, false)
        val assumed = assumedKw
        val rateKw = assumed ?: measuredKw(levelSamples) ?: measuredKw(socSamples)
        val last = socSamples.lastOrNull()
        if (rateKw == null || rateKw <= 0f || last == null) return ChargeEstimate(rateKw, null, assumed != null)
        // 마지막으로 % 가 바뀐 뒤 지난 시간만큼은 충전됐다고 본다 (단, 다음 1% 를 넘지 않게)
        val sinceMs = nowMs - last.first
        val gained = (rateKw * 1000f * sinceMs / 3_600_000f).coerceAtMost(cap / 100f)
        val left = max(0f, cap * targetPct / 100f - (last.second + gained))
        return ChargeEstimate(rateKw, (left / (rateKw * 1000f) * 3600f).roundToInt(), assumed != null)
    }

    private fun measuredKw(q: ArrayDeque<Pair<Long, Float>>): Float? {
        if (q.size < 2) return null
        val (t0, e0) = q.first()
        val (t1, e1) = q.last()
        if (t1 - t0 < MIN_SPAN_MS || e1 <= e0) return null
        return (e1 - e0) / ((t1 - t0) / 3_600_000f) / 1000f
    }

    companion object {
        const val MIN_SPAN_MS = 20_000L
    }
}
