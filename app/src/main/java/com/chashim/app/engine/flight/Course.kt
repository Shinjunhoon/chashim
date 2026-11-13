package com.chashim.app.engine.flight

import com.chashim.app.engine.Effect
import kotlin.math.PI
import kotlin.math.cos

/**
 * 바람길의 한 지점. t초에 가로 x (-1 왼쪽 ~ 1 오른쪽), 높이 v (0 위 ~ 1 아래)를 지난다.
 * 지점 사이는 부드럽게 이어진다.
 */
data class WindKey(val t: Float, val x: Float, val v: Float)

/** 먹구름: t초에 (x, v) 위치에 도달. 조준점이 가로 w/2, 세로 CLOUD_HALF_V 안이면 충돌 */
data class Cloud(val t: Float, val x: Float, val v: Float, val w: Float)

/** 별빛: 하늘에서 떨어져 t초에 (x, v)에 닿는다. big이면 황금 별 */
data class Star(val t: Float, val x: Float, val v: Float, val big: Boolean = false)

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
    /** 영상 속 지평선 높이 (화면 세로 비율). 바람길·먹구름이 여기서 다가온다 */
    val horizon: Float = 0.3f,
    val wind: List<WindKey>,
    val clouds: List<Cloud> = emptyList(),
    val stars: List<Star> = emptyList(),
    /** 이 시간 동안은 바람길이 보이지 않는다. 바람 세기만으로 찾아간다 */
    val hidden: ClosedFloatingPointRange<Float>? = null,
    /** 구간 기본 4D 효과 (바람은 바람길이 맡으므로 넣지 않는다) */
    val effects: List<Effect> = emptyList(),
    val fork: Fork? = null,
    /** 갈림길이 없을 때 다음 구간. null이면 마지막 구간 */
    val next: String? = null,
) {
    /** t초의 바람길 위치 (x, v). 지점 사이를 코사인 곡선으로 부드럽게 잇는다 */
    fun windAt(t: Float): Pair<Float, Float> {
        val k = wind
        if (t <= k.first().t) return k.first().x to k.first().v
        if (t >= k.last().t) return k.last().x to k.last().v
        val i = k.indexOfLast { it.t <= t }
        val a = k[i]
        val b = k[i + 1]
        val u = (t - a.t) / (b.t - a.t)
        val e = (1f - cos(u * PI.toFloat())) / 2f
        return (a.x + (b.x - a.x) * e) to (a.v + (b.v - a.v) * e)
    }

    fun isHidden(t: Float) = hidden?.contains(t) == true
}

data class Course(
    val id: String,
    val title: String,
    val subtitle: String,
    val first: String,
    val segments: List<Segment>,
) {
    fun segment(id: String) = segments.first { it.id == id }
}

/** 먹구름·바람길이 처음 보이는 시점: 도달 몇 초 전 */
const val LEAD_SEC = 3f

/** 별빛이 화면 위에서 제자리까지 떨어지는 시간 */
const val STAR_FALL_SEC = 2.4f

/** 바람길 안으로 보는 범위 (가로·세로 반지름, -1~1 / 0~1 기준) */
const val WIND_HALF_X = 0.17f
const val WIND_HALF_V = 0.22f

/** 먹구름 세로 반지름 */
const val CLOUD_HALF_V = 0.2f

/**
 * 엔터테인 모드 "제주 하늘 비행" (약 2분 20초).
 * 핸들(좌우)과 브레이크(밟으면 내려감, 떼면 올라옴)로 바람길에 올라탄다.
 * 바람길 안에 있을수록 실제 차 바람이 세진다. 협곡·오름 구간 중간에는 바람길이 사라져 바람으로만 찾아간다.
 * 에어컨 온도는 건드리지 않는다.
 */
val JejuFlight = Course(
    id = "jeju_flight",
    title = "제주 하늘 비행",
    subtitle = "바람길을 타는 4D 비행 · 약 2분 20초",
    first = "takeoff",
    segments = listOf(
        Segment(
            id = "takeoff", title = "이륙", caption = "빛나는 바람길에 올라타세요",
            video = "jeju_flight/01_takeoff.mp4", durationSec = 10, glow = 0xFF2B6C8F, horizon = 0.24f,
            wind = listOf(WindKey(0f, 0f, 0.1f), WindKey(5f, 0.15f, 0.2f), WindKey(10f, -0.1f, 0.25f)),
            stars = listOf(Star(6f, 0.12f, 0.2f), Star(9f, -0.08f, 0.25f)),
            effects = listOf(Effect.Ambient(at = 0, color = 0x4FB4E8, name = "하늘 파랑")),
            next = "coast",
        ),
        Segment(
            id = "coast", title = "해안 비행", caption = "핸들로 좌우, 브레이크를 밟으면 아래로",
            video = "jeju_flight/02_coast.mp4", durationSec = 36, glow = 0xFF1F6F8B, horizon = 0.16f,
            wind = listOf(
                WindKey(0f, -0.1f, 0.25f), WindKey(5f, -0.4f, 0.2f), WindKey(10f, 0.3f, 0.45f),
                WindKey(15f, 0.45f, 0.9f), WindKey(20f, -0.15f, 0.75f), WindKey(25f, -0.5f, 0.2f),
                WindKey(29f, 0f, 0.3f), WindKey(36f, 0f, 0.3f),
            ),
            stars = listOf(
                Star(3f, -0.3f, 0.22f), Star(8f, 0f, 0.3f), Star(13f, 0.42f, 0.75f),
                Star(18f, 0.15f, 0.88f), Star(23f, -0.42f, 0.35f), Star(27f, -0.2f, 0.25f, big = true),
            ),
            effects = listOf(
                Effect.Window(at = 2, percent = 10),
                Effect.Ambient(at = 0, color = 0x2FC4C4, name = "바다 청록"),
            ),
            fork = Fork(
                at = 29f, windowSec = 6f,
                left = "valley", leftLabel = "협곡",
                right = "ridge", rightLabel = "오름",
            ),
        ),
        Segment(
            id = "valley", title = "협곡 통과", caption = "깊은 협곡에선 바람길이 숨어요. 바람으로 찾아보세요",
            video = "jeju_flight/03a_valley.mp4", durationSec = 24, glow = 0xFF2E6B5A, horizon = 0.22f,
            wind = listOf(
                WindKey(0f, 0f, 0.3f), WindKey(5f, 0.35f, 0.6f), WindKey(10f, 0.25f, 0.95f),
                WindKey(15f, -0.35f, 0.7f), WindKey(20f, -0.1f, 0.3f), WindKey(24f, 0f, 0.3f),
            ),
            hidden = 7f..17f,
            stars = listOf(Star(4f, 0.3f, 0.55f), Star(20.5f, -0.1f, 0.3f, big = true)),
            effects = listOf(
                Effect.Ambient(at = 0, color = 0x38D0A0, name = "협곡 초록"),
            ),
            next = "halla",
        ),
        Segment(
            id = "ridge", title = "오름 위 안개", caption = "안개 속에선 바람길이 숨어요. 바람으로 찾아보세요",
            video = "jeju_flight/03b_ridge.mp4", durationSec = 24, glow = 0xFF4E6B2E, horizon = 0.3f,
            wind = listOf(
                WindKey(0f, 0f, 0.3f), WindKey(5f, -0.4f, 0.2f), WindKey(10f, -0.15f, 0.85f),
                WindKey(15f, 0.4f, 0.6f), WindKey(20f, 0.1f, 0.3f), WindKey(24f, 0f, 0.3f),
            ),
            hidden = 7f..17f,
            stars = listOf(Star(4f, -0.35f, 0.22f), Star(20.5f, 0.1f, 0.3f, big = true)),
            effects = listOf(Effect.Ambient(at = 0, color = 0x8CCB4A, name = "능선 연두")),
            next = "halla",
        ),
        Segment(
            id = "halla", title = "한라산 구름 위", caption = "먹구름은 아래로 피하고, 구름을 뚫고 급강하!",
            video = "jeju_flight/04_clouds.mp4", durationSec = 30, glow = 0xFF5A6B80, horizon = 0.32f,
            wind = listOf(
                WindKey(0f, 0f, 0.3f), WindKey(4f, 0f, 0.8f), WindKey(9f, 0f, 0.85f),
                WindKey(12f, -0.35f, 0.45f), WindKey(15f, -0.3f, 0.3f), WindKey(18f, 0f, 0.15f),
                WindKey(22f, 0.35f, 0.95f), WindKey(26f, -0.25f, 0.85f), WindKey(30f, 0f, 0.4f),
            ),
            clouds = listOf(Cloud(7f, 0f, 0.25f, 0.6f), Cloud(13f, 0.3f, 0.4f, 0.5f)),
            stars = listOf(Star(16f, -0.15f, 0.2f), Star(23f, 0.25f, 0.95f), Star(27.5f, -0.1f, 0.7f, big = true)),
            effects = listOf(
                Effect.Window(at = 0, percent = 0),
                Effect.Ambient(at = 0, color = 0xC8DCFF, name = "구름 흰빛"),
                Effect.Wiper(at = 19, seconds = 3),
            ),
            next = "landing",
        ),
        Segment(
            id = "landing", title = "일출 착륙", caption = "별빛이 쏟아져요. 모두 받아 보세요",
            video = "jeju_flight/05_sunrise.mp4", durationSec = 24, glow = 0xFF8F5A2B, horizon = 0.36f,
            wind = listOf(WindKey(0f, 0f, 0.4f), WindKey(8f, 0.2f, 0.5f), WindKey(16f, -0.2f, 0.45f), WindKey(24f, 0f, 0.4f)),
            stars = listOf(
                Star(3f, -0.3f, 0.2f), Star(4.5f, 0f, 0.6f), Star(6f, 0.3f, 0.25f), Star(8f, 0.45f, 0.75f),
                Star(9.5f, 0.15f, 0.3f), Star(11f, -0.15f, 0.8f), Star(12.5f, -0.45f, 0.3f), Star(14.5f, -0.2f, 0.65f),
                Star(16f, 0.1f, 0.2f), Star(18.5f, 0f, 0.45f, big = true),
            ),
            effects = listOf(
                Effect.Ambient(at = 0, color = 0xFF9A3C, name = "일출 주황"),
                Effect.SeatHeat(at = 3, level = 1),
            ),
        ),
    ),
)
