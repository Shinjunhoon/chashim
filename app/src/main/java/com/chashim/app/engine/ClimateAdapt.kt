package com.chashim.app.engine

import kotlin.math.roundToInt

/** 실내 온도에 따른 판단 */
enum class CabinFeel { COLD, COMFORT, HOT }

/** 실내 온도를 보고 효과를 어떻게 조절했는지 (화면에 이유를 보여 주기 위해) */
data class ClimateAdapt(
    val cabin: Float,
    val feel: CabinFeel,
    val message: String,
    val detail: String,
)

const val COLD_AT = 20f
const val HOT_AT = 26f

/**
 * 실내 현재 온도에 맞춰 테마의 4D 효과를 조절한다.
 * - 쌀쌀함(20°C 이하): 온도 +1.5°C, 첫 장면부터 열선, 처마 밑 열선 2단계, 바람 한 단계 약하게
 * - 더움(26°C 이상): 온도 −2°C, 바람 한 단계 세게, 열선 끄기
 * - 쾌적함: 그대로
 */
fun adaptToCabin(theme: Theme, cabin: Float?): Pair<Theme, ClimateAdapt?> {
    if (cabin == null) return theme to null
    val shown = "%.0f".format(cabin)
    return when {
        cabin <= COLD_AT -> theme.copy(
            scenes = theme.scenes.mapIndexed { i, scene ->
                val effects = scene.effects.map { e ->
                    when (e) {
                        is Effect.Temp -> e.copy(celsius = (e.celsius + 1.5f).coerceAtMost(27f))
                        is Effect.Fan -> e.copy(level = (e.level - 1).coerceAtLeast(1))
                        is Effect.SeatHeat -> if (e.level > 0) e.copy(level = 2) else e
                        else -> e
                    }
                }
                scene.copy(effects = if (i == 0) effects + Effect.SeatHeat(at = 5, level = 1) else effects)
            },
        ) to ClimateAdapt(
            cabin, CabinFeel.COLD,
            message = "차 안이 ${shown}°C로 쌀쌀해서 따뜻하게 맞췄어요",
            detail = "온도 +1.5°C · 열선 먼저 켜기 · 바람 약하게",
        )
        cabin >= HOT_AT -> theme.copy(
            scenes = theme.scenes.map { scene ->
                scene.copy(
                    effects = scene.effects.mapNotNull { e ->
                        when (e) {
                            is Effect.Temp -> e.copy(celsius = (e.celsius - 2f).coerceAtLeast(18f))
                            is Effect.Fan -> e.copy(level = e.level + 1)
                            is Effect.SeatHeat -> if (e.level > 0) null else e
                            else -> e
                        }
                    },
                )
            },
        ) to ClimateAdapt(
            cabin, CabinFeel.HOT,
            message = "차 안이 ${shown}°C로 더워서 시원하게 맞췄어요",
            detail = "온도 −2°C · 바람 세게 · 열선 끄기",
        )
        else -> theme to ClimateAdapt(
            cabin, CabinFeel.COMFORT,
            message = "차 안이 ${shown}°C로 쾌적해서 기본 설정으로 쉬어요",
            detail = "온도 · 바람 · 열선 기본값",
        )
    }
}

/** 화면 표시용 반올림 */
fun Float.oneDecimal() = (this * 10).roundToInt() / 10f
