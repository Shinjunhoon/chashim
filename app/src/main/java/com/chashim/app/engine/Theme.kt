package com.chashim.app.engine

/** 한 장면에서 특정 시점(초)에 실행할 4D 효과 */
sealed interface Effect {
    val at: Int

    /** 송풍 세기 (단). rampSec 동안 한 단씩 천천히 바꾼다 */
    data class Fan(override val at: Int, val level: Int, val rampSec: Int = 5) : Effect
    /** 설정 온도 (°C). rampSec 동안 0.5도씩 천천히 바꾼다 */
    data class Temp(override val at: Int, val celsius: Float, val rampSec: Int = 6) : Effect
    /** 운전석 시트 열선 (0~3) */
    data class SeatHeat(override val at: Int, val level: Int) : Effect
    /** 운전석 창문 열림 비율 (0~20%). 안전을 위해 20%를 넘기지 않는다 */
    data class Window(override val at: Int, val percent: Int) : Effect
    /** 와이퍼를 seconds초 동안만 짧게 작동 */
    data class Wiper(override val at: Int, val seconds: Int = 4) : Effect
    /** 번개 같은 화면 플래시 (무드등도 하얗게 번쩍) */
    data class Flash(override val at: Int) : Effect
    /**
     * 돌풍: 운전자가 맞춘 바람 세기보다 plus단 세게 seconds초 불고 원래대로 돌아간다.
     * 에어컨 온도와 평소 바람은 운전자 설정을 그대로 둔다.
     */
    data class Gust(override val at: Int, val plus: Int, val seconds: Int) : Effect
    /** 실내 무드등 색 (0xRRGGBB) */
    data class Ambient(override val at: Int, val color: Int, val name: String) : Effect
}

/** 장면의 역할. 충전 중에는 중간 장면을 충전 완료 시간에 맞춰 반복한다 */
enum class SceneRole { INTRO, MIDDLE, OUTRO }

data class Scene(
    val id: String,
    val role: SceneRole,
    val title: String,
    val caption: String,
    /** assets 안 영상 경로. 파일이 없으면 앱이 그리는 비 화면으로 대신한다 */
    val video: String,
    /** assets 안 소리 경로 (없으면 무음) */
    val audio: String?,
    val durationSec: Int,
    /** 화면 가장자리 빛 색 */
    val glow: Long,
    /** 영상이 없을 때 그리는 비의 양 (0 = 없음) */
    val rain: Float,
    val effects: List<Effect>,
)

data class Theme(
    val id: String,
    val title: String,
    val subtitle: String,
    val scenes: List<Scene>,
) {
    val totalSec get() = scenes.sumOf { it.durationSec }
}

/**
 * 힐링 모드 "비 오는 숲".
 * - 충전 중이 아니면: 다섯 장면을 한 번 (약 3분)
 * - 충전 중이면: 도입 → (폭우 ↔ 처마 밑) 반복 → 충전 완료 시점에 "비가 그쳤어요"
 * 에어컨 온도는 건드리지 않는다. 바람은 돌풍으로만, 따뜻함은 시트 열선으로 표현한다.
 */
val RainForest = Theme(
    id = "rain_forest",
    title = "비 오는 숲",
    subtitle = "머리가 복잡할 때 · 충전 시간에 맞춰",
    scenes = listOf(
        Scene(
            id = "mist", role = SceneRole.INTRO, title = "안개 낀 숲", caption = "숨을 천천히 쉬어 보세요",
            video = "rain_forest/01_mist.mp4", audio = "rain_forest/birds.mp3",
            durationSec = 30, glow = 0xFF2E5E4E, rain = 0f,
            effects = listOf(
                Effect.Ambient(at = 0, color = 0x3FB37A, name = "숲 초록"),
            ),
        ),
        Scene(
            id = "raindrops", role = SceneRole.INTRO, title = "빗방울이 떨어지기 시작해요", caption = "앞유리에도 비가 내려요",
            video = "rain_forest/02_raindrops.mp4", audio = "rain_forest/rain_light.mp3",
            durationSec = 35, glow = 0xFF1E4D5B, rain = 0.35f,
            effects = listOf(
                Effect.Ambient(at = 0, color = 0x3A8FD0, name = "비 파랑"),
                Effect.Wiper(at = 3, seconds = 4),
                Effect.Gust(at = 8, plus = 1, seconds = 6),
            ),
        ),
        Scene(
            id = "heavy_rain", role = SceneRole.MIDDLE, title = "숲에 비가 쏟아져요", caption = "빗소리에 생각을 흘려보내세요",
            video = "rain_forest/03_heavy_rain.mp4", audio = "rain_forest/rain_heavy.mp3",
            durationSec = 40, glow = 0xFF1E3A4D, rain = 1f,
            effects = listOf(
                Effect.Ambient(at = 0, color = 0x2A3FA0, name = "깊은 남색"),
                Effect.Gust(at = 1, plus = 2, seconds = 8),
                Effect.Wiper(at = 4, seconds = 5),
                Effect.Flash(at = 18),
                Effect.Flash(at = 19),
                Effect.Gust(at = 19, plus = 3, seconds = 4),
            ),
        ),
        Scene(
            id = "eaves", role = SceneRole.MIDDLE, title = "처마 밑에서 몸을 녹여요", caption = "시트가 따뜻해져요",
            video = "rain_forest/04_eaves.mp4", audio = "rain_forest/rain_light.mp3",
            durationSec = 40, glow = 0xFF5B3A1E, rain = 0.5f,
            effects = listOf(
                Effect.Ambient(at = 0, color = 0xFF8A2A, name = "따뜻한 주황"),
                Effect.SeatHeat(at = 2, level = 1),
            ),
        ),
        Scene(
            id = "after_rain", role = SceneRole.OUTRO, title = "비가 그쳤어요", caption = "창문을 살짝 열어 맑은 공기를 들여요",
            video = "rain_forest/05_sunlight.mp4", audio = "rain_forest/birds.mp3",
            durationSec = 35, glow = 0xFF6B8F3A, rain = 0f,
            effects = listOf(
                Effect.Ambient(at = 0, color = 0xFFD45A, name = "햇살 노랑"),
                Effect.SeatHeat(at = 0, level = 0),
                Effect.Window(at = 3, percent = 10),
            ),
        ),
    ),
)
