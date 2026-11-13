package com.chashim.app.engine.flight

import com.chashim.app.engine.Effect

/**
 * 링: t초에 기준선에 도달한다. x는 화면 가로 위치 (-1 왼쪽 ~ 1 오른쪽).
 * 도달하기 RING_LEAD_SEC 전부터 멀리서 다가오며 커진다.
 */
data class Ring(val t: Float, val x: Float)

/** 먹구름: t초에 도달. 조준점이 x ± w/2 안이면 충돌 */
data class Cloud(val t: Float, val x: Float, val w: Float)

/** 갈림길: 구간의 at초부터 windowSec초 동안 핸들을 크게 꺾어 고른다. 안 고르면 left */
data class Fork(
    val at: Float,
    val windowSec: Float,
    val left: String,
    val leftLabel: String,
    val right: String,
    val rightLabel: String,
)

data class Segment(
    val id: String,
    val title: String,
    val caption: String,
    /** assets 안 영상 경로 (구간 길이보다 짧으면 반복 재생) */
    val video: String,
    val durationSec: Int,
    val glow: Long,
    val rings: List<Ring> = emptyList(),
    val clouds: List<Cloud> = emptyList(),
    /** 구간 기본 4D 효과 (힐링 모드와 같은 효과 정의를 쓴다) */
    val effects: List<Effect> = emptyList(),
    val fork: Fork? = null,
    /** 갈림길이 없을 때 다음 구간. null이면 마지막 구간 */
    val next: String? = null,
)

data class Course(
    val id: String,
    val title: String,
    val subtitle: String,
    val first: String,
    val segments: List<Segment>,
) {
    fun segment(id: String) = segments.first { it.id == id }
}

/** 링이 처음 보이는 시점: 도달 몇 초 전 */
const val RING_LEAD_SEC = 3f

/**
 * 엔터테인 모드 "제주 하늘 비행" (약 2분 30초).
 * 이륙 → 해안 (링 3개, 갈림길) → 폭포 또는 오름 (링 2개) → 한라산 구름 위 (먹구름 2개, 급강하 링 3개) → 일출 착륙.
 * 에어컨 온도는 건드리지 않는다. 바람은 돌풍으로, 시원함·따뜻함은 창문·열선·무드등으로 표현한다.
 */
val JejuFlight = Course(
    id = "jeju_flight",
    title = "제주 하늘 비행",
    subtitle = "핸들로 조종하는 4D 비행 · 약 2분 30초",
    first = "takeoff",
    segments = listOf(
        Segment(
            id = "takeoff", title = "이륙", caption = "바다 위로 날아올라요",
            video = "jeju_flight/01_takeoff.mp4", durationSec = 10, glow = 0xFF2B6C8F,
            effects = listOf(
                Effect.Ambient(at = 0, color = 0x4FB4E8, name = "하늘 파랑"),
                Effect.Gust(at = 1, plus = 2, seconds = 6),
            ),
            next = "coast",
        ),
        Segment(
            id = "coast", title = "해안 비행", caption = "핸들로 조준점을 움직여 링을 통과하세요",
            video = "jeju_flight/02_coast.mp4", durationSec = 36, glow = 0xFF1F6F8B,
            rings = listOf(Ring(7f, -0.35f), Ring(13f, 0.4f), Ring(19f, 0f), Ring(25f, -0.5f)),
            effects = listOf(
                Effect.Window(at = 2, percent = 10),
                Effect.Ambient(at = 0, color = 0x2FC4C4, name = "바다 청록"),
            ),
            fork = Fork(
                at = 29f, windowSec = 6f,
                left = "waterfall", leftLabel = "폭포",
                right = "ridge", rightLabel = "오름 능선",
            ),
        ),
        Segment(
            id = "waterfall", title = "폭포 통과", caption = "물보라가 앞유리에 튀어요",
            video = "jeju_flight/03a_waterfall.mp4", durationSec = 22, glow = 0xFF2E6B5A,
            rings = listOf(Ring(8f, 0.45f), Ring(15f, -0.25f)),
            effects = listOf(
                Effect.Ambient(at = 0, color = 0x38D0A0, name = "물빛 초록"),
                Effect.Wiper(at = 4, seconds = 3),
                Effect.Gust(at = 4, plus = 2, seconds = 4),
            ),
            next = "halla",
        ),
        Segment(
            id = "ridge", title = "오름 능선 활강", caption = "능선을 따라 바람이 불어요",
            video = "jeju_flight/03b_ridge.mp4", durationSec = 22, glow = 0xFF4E6B2E,
            rings = listOf(Ring(7f, -0.45f), Ring(14f, 0.45f)),
            effects = listOf(
                Effect.Ambient(at = 0, color = 0x8CCB4A, name = "능선 연두"),
                Effect.Gust(at = 3, plus = 1, seconds = 3),
                Effect.Gust(at = 10, plus = 2, seconds = 3),
                Effect.Gust(at = 17, plus = 1, seconds = 3),
            ),
            next = "halla",
        ),
        Segment(
            id = "halla", title = "한라산 구름 위", caption = "먹구름을 피하고, 구름을 뚫고 급강하!",
            video = "jeju_flight/04_clouds.mp4", durationSec = 30, glow = 0xFF5A6B80,
            clouds = listOf(Cloud(7f, 0f, 0.5f), Cloud(13f, -0.45f, 0.45f)),
            rings = listOf(Ring(20f, 0.35f), Ring(23f, 0f), Ring(26f, -0.35f)),
            effects = listOf(
                Effect.Window(at = 0, percent = 0),
                Effect.Ambient(at = 0, color = 0xC8DCFF, name = "구름 흰빛"),
                Effect.Gust(at = 17, plus = 3, seconds = 9),
                Effect.Wiper(at = 18, seconds = 3),
            ),
            next = "landing",
        ),
        Segment(
            id = "landing", title = "일출 착륙", caption = "해가 떠올라요. 천천히 내려갈게요",
            video = "jeju_flight/05_sunrise.mp4", durationSec = 24, glow = 0xFF8F5A2B,
            effects = listOf(
                Effect.Ambient(at = 0, color = 0xFF9A3C, name = "일출 주황"),
                Effect.SeatHeat(at = 3, level = 1),
            ),
        ),
    ),
)
