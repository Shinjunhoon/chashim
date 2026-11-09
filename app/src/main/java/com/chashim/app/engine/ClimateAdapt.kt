package com.chashim.app.engine

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
 * 실내 현재 온도로 "운전자가 보통 직접 켜 두지 않는 것"을 정한다.
 * 에어컨 온도는 운전자가 이미 맞춰 둔 값이라 건드리지 않는다.
 * - 쌀쌀함(20°C 이하): 첫 장면부터 열선 1단계, 처마 밑 열선 2단계
 * - 더움(26°C 이상): 열선을 켜지 않고 돌풍을 한 단계 더 세게
 * - 쾌적함: 처마 밑에서만 열선 1단계
 */
fun adaptToCabin(theme: Theme, cabin: Float?): Pair<Theme, ClimateAdapt?> {
    if (cabin == null) return theme to null
    val shown = "%.0f".format(cabin)
    return when {
        cabin <= COLD_AT -> theme.copy(
            scenes = theme.scenes.mapIndexed { i, scene ->
                val effects = scene.effects.map { e ->
                    if (e is Effect.SeatHeat && e.level > 0) e.copy(level = 2) else e
                }
                scene.copy(effects = if (i == 0) effects + Effect.SeatHeat(at = 5, level = 1) else effects)
            },
        ) to ClimateAdapt(
            cabin, CabinFeel.COLD,
            message = "차 안이 ${shown}°C로 쌀쌀해서 열선을 먼저 켜 드릴게요",
            detail = "에어컨 설정은 그대로 · 열선 먼저 · 처마 밑 2단계",
        )
        cabin >= HOT_AT -> theme.copy(
            scenes = theme.scenes.map { scene ->
                scene.copy(
                    effects = scene.effects.mapNotNull { e ->
                        when (e) {
                            is Effect.SeatHeat -> if (e.level > 0) null else e
                            is Effect.Gust -> e.copy(plus = e.plus + 1)
                            else -> e
                        }
                    },
                )
            },
        ) to ClimateAdapt(
            cabin, CabinFeel.HOT,
            message = "차 안이 ${shown}°C로 더워서 열선 대신 시원한 바람으로 쉬어요",
            detail = "에어컨 설정은 그대로 · 열선 끄기 · 돌풍 더 세게",
        )
        else -> theme to ClimateAdapt(
            cabin, CabinFeel.COMFORT,
            message = "차 안이 ${shown}°C로 쾌적해요",
            detail = "에어컨 설정은 그대로 · 처마 밑에서만 열선",
        )
    }
}
