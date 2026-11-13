package com.chashim.app.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.control.WiperSwitch
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import ai.pleos.playground.vehicle.listener.EvBatteryLevelListener
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.EvChargePortOpenListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import ai.pleos.playground.vehicle.listener.SeatBeltBuckledStateListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.control.FanDirectionType
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

private const val TAG = "Chashim"

/** 차가 지원하는 위치와 범위. 차종마다 달라서 시작할 때 차에 물어본다. */
data class Capabilities(
    val fanArea: SeatArea? = null,
    val fanMin: Int = 1,
    val fanMax: Int = 8,
    val powerArea: SeatArea? = null,
    val tempArea: SeatArea? = null,
    val tempMin: Float = 16f,
    val tempMax: Float = 30f,
    val seatArea: SeatArea? = null,
    val seatMax: Int = 3,
    val windowArea: WindowArea? = null,
    val windowMax: Int = 10,
    val wiperArea: WindowArea? = null,
    /** 이 차가 지원하는 와이퍼 작동 모드 중 가장 약한 것 */
    val wiperOn: WiperSwitch? = null,
    val ambientArea: SeatArea? = null,
    /** 실내 현재 온도를 읽을 위치 */
    val cabinArea: SeatArea? = null,
)

/** 레이스 몰입용 추가 장치 (차종마다 다르다) */
data class ExtraCaps(
    val cabinLight: Boolean = false,
    val readingArea: SeatArea? = null,
    val brightArea: SeatArea? = null,
    val brightMin: Int = 0,
    val brightMax: Int = 100,
    val wheelHeatMax: Int? = null,
    val ventArea: SeatArea? = null,
    val ventMax: Int = 3,
    val dirArea: SeatArea? = null,
    val dirs: List<FanDirectionType> = emptyList(),
    val recircArea: SeatArea? = null,
)

/** 추가 장치의 시작 전 상태 */
data class ExtraSnapshot(
    val cabin: LightSwitch?,
    val reading: LightSwitch?,
    val brightness: Int?,
    val wheelHeat: Int?,
    val vent: Int?,
    val direction: FanDirectionType?,
    val recirc: Boolean?,
)

/** 세션 시작 전 차 상태. 끝나면 이 값으로 되돌린다. */
data class Snapshot(
    val power: Boolean?,
    val fan: Int?,
    val temp: Float?,
    val seatHeat: Int?,
    val window: Int?,
    /** 무드등 색 (0xRRGGBB) */
    val ambientColor: Int?,
)

/**
 * Pleos Vehicle SDK 래퍼.
 * SDK의 콜백 방식을 suspend 함수로 바꾸고, 실패는 앱이 멈추지 않도록 null로 돌려준다.
 */
class VehicleGateway(context: Context) {

    private val vehicle = Vehicle(context).also { it.initialize() }
    private val hvac = vehicle.getHVAC()
    private val seat = vehicle.getSeat()
    private val window = vehicle.getWindow()
    private val wiper = vehicle.getWiper()
    private val gear = vehicle.getGear()
    private val ev = vehicle.getEvBattery()
    private val light = vehicle.getLight()
    private val steering by lazy { vehicle.getSteeringWheel() }
    private val brakeApi by lazy { vehicle.getBrake() }
    private val turnApi by lazy { vehicle.getTurnSignal() }

    /** 방향지시등 (레이스 대시 입력) */
    private val _turn = MutableStateFlow<TurnSignalValue?>(null)
    val turnSignal: StateFlow<TurnSignalValue?> = _turn.asStateFlow()

    /** 운전석 안전벨트. 차가 알려 주지 않으면 null */
    private val _belt = MutableStateFlow<Boolean?>(null)
    val driverBelt: StateFlow<Boolean?> = _belt.asStateFlow()

    /** 조수석에 사람이 있는지 */
    private val _passenger = MutableStateFlow<Boolean?>(null)
    val passenger: StateFlow<Boolean?> = _passenger.asStateFlow()

    private val driverSeat = SeatArea(SeatValue.ROW_1_LEFT)
    private val passengerSeat = SeatArea(SeatValue.ROW_1_RIGHT)
    private val turnListener = object : TurnSignalStateListener {
        override fun onTurnSignalStateUpdated(v: TurnSignalValue?) { _turn.value = v }
        override fun onFailed(e: Exception) = warn("방향지시등 감지", e)
    }
    private val beltListener = object : SeatBeltBuckledStateListener {
        override fun onSeatBeltBuckledStateUpdated(v: Boolean?) { _belt.value = v }
        override fun onFailed(e: Exception) = warn("안전벨트 감지", e)
    }
    private val occupancyListener = object : SeatOccupancyListener {
        override fun onSeatOccupancyUpdated(v: Boolean?) { _passenger.value = v }
        override fun onFailed(e: Exception) = warn("조수석 감지", e)
    }

    /** 핸들 각도 (도). 엔터테인 모드 조종에 쓴다 */
    private val _steering = MutableStateFlow(0f)
    val steeringDeg: StateFlow<Float> = _steering.asStateFlow()

    /** 브레이크 페달을 밟고 있는지 */
    private val _brake = MutableStateFlow(false)
    val brakePressed: StateFlow<Boolean> = _brake.asStateFlow()

    private val steeringListener = object : SteeringWheelAngleListener {
        override fun onSteeringWheelAngleUpdated(angle: Float?) { angle?.let { _steering.value = it } }
        override fun onFailed(e: Exception) = warn("핸들 각도 감지", e)
    }
    private val brakeListener = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(status: Boolean?) { _brake.value = status == true }
        override fun onFailed(e: Exception) = warn("브레이크 감지", e)
    }

    var caps = Capabilities()
        private set

    private val _gear = MutableStateFlow<VehicleGear?>(null)
    val gearState: StateFlow<VehicleGear?> = _gear.asStateFlow()

    private val _charging = MutableStateFlow(false)
    val charging: StateFlow<Boolean> = _charging.asStateFlow()

    private val _battery = MutableStateFlow<Int?>(null)
    val battery: StateFlow<Int?> = _battery.asStateFlow()

    /** 배터리에 들어 있는 에너지 (Wh). 충전 속도를 잴 때 쓴다 */
    private val _energyWh = MutableStateFlow<Float?>(null)
    val energyWh: StateFlow<Float?> = _energyWh.asStateFlow()

    /** 배터리 전체 용량 (Wh) */
    var capacityWh: Float? = null
        private set

    private val gearListener = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) { _gear.value = gear }
        override fun onFailed(e: Exception) = warn("기어 감지", e)
    }
    private val chargeListener = object : EvChargePortOpenListener {
        override fun onEvChargePortOpenUpdated(open: Boolean?) { _charging.value = open == true }
        override fun onFailed(e: Exception) = warn("충전구 감지", e)
    }
    private val levelListener = object : EvBatteryLevelListener {
        override fun onEvBatteryLevelUpdated(wh: Float?) { _energyWh.value = wh }
        override fun onFailed(e: Exception) = warn("배터리 에너지 감지", e)
    }
    private val socListener = object : EvBatteryStateOfChargeListener {
        override fun onEvBatteryStateOfChargeUpdated(soc: Int?) { _battery.value = soc }
        override fun onFailed(e: Exception) = warn("배터리 감지", e)
    }

    // ---- 시작 / 종료 ----

    suspend fun start() {
        caps = loadCapabilities()
        Log.i(TAG, "capabilities: $caps")

        _gear.value = call { ok, fail -> gear.getCurrentGearSelectionState(ok, fail) }
        _charging.value = call { ok, fail -> ev.isEvChargePortOpen(ok, fail) } == true
        _battery.value = call { ok, fail -> ev.getEvBatteryStateOfCharge(ok, fail) }
        _energyWh.value = call { ok, fail -> ev.getEvBatteryLevel(ok, fail) }
        capacityWh = call { ok, fail -> ev.getEvBatteryCapacity(ok, fail) }
        Log.i(TAG, "battery ${_battery.value}% energy ${_energyWh.value}Wh capacity ${capacityWh}Wh")
        runCatching { gear.registerGearSelection(gearListener) }
        runCatching { ev.registerEvChargePortOpen(chargeListener) }
        runCatching { ev.registerEvBatteryStateOfCharge(socListener) }
        runCatching { ev.registerEvBatteryLevel(levelListener) }
        runCatching { steering.registerSteeringWheelAngle(steeringListener) }.onFailure { Log.w(TAG, "핸들 등록 실패 $it") }
        runCatching { brakeApi.registerBrakePedalStatus(brakeListener) }.onFailure { Log.w(TAG, "브레이크 등록 실패 $it") }
        _belt.value = call { ok, fail -> seat.getCurrentSeatBeltBuckledState(driverSeat, ok, fail) }
        _passenger.value = call { ok, fail -> seat.getCurrentSeatOccupancy(passengerSeat, ok, fail) }
        _turn.value = call { ok, fail -> turnApi.getCurrentTurnSignalState(ok, fail) }
        runCatching { turnApi.registerTurnSignalState(turnListener) }.onFailure { Log.w(TAG, "방향지시등 등록 실패 $it") }
        runCatching { seat.registerSeatBeltBuckledState(driverSeat, beltListener) }.onFailure { Log.w(TAG, "안전벨트 등록 실패 $it") }
        runCatching { seat.registerSeatOccupancy(passengerSeat, occupancyListener) }.onFailure { Log.w(TAG, "조수석 등록 실패 $it") }
        Log.i(TAG, "belt ${_belt.value} passenger ${_passenger.value} turn ${_turn.value}")
    }

    /** 앱이 켜질 때 공조 등이 아직 준비 안 돼 못 받은 정보가 있으면 다시 받는다 */
    suspend fun refreshCapabilitiesIfMissing() {
        val c = caps
        if (c.fanArea == null || c.tempArea == null || c.seatArea == null || c.windowArea == null ||
            c.wiperArea == null || c.ambientArea == null
        ) {
            caps = loadCapabilities()
            Log.i(TAG, "capabilities (reloaded): $caps")
        }
    }

    fun release() {
        runCatching { gear.unregisterGearSelection(gearListener) }
        runCatching { ev.unregisterEvChargePortOpen(chargeListener) }
        runCatching { ev.unregisterEvBatteryStateOfCharge(socListener) }
        runCatching { ev.unregisterEvBatteryLevel(levelListener) }
        runCatching { steering.unregisterSteeringWheelAngle(steeringListener) }
        runCatching { brakeApi.unregisterBrakePedalStatus(brakeListener) }
        runCatching { turnApi.unregisterTurnSignalState(turnListener) }
        runCatching { seat.unregisterSeatBeltBuckledState(driverSeat, beltListener) }
        runCatching { seat.unregisterSeatOccupancy(passengerSeat, occupancyListener) }
        vehicle.release()
    }

    private suspend fun loadCapabilities(): Capabilities {
        var c = Capabilities()
        call<Map<SeatArea, ai.pleos.playground.vehicle.api.model.capability.AreaBounds<Int>>> { ok, fail ->
            hvac.checkHvacFanSpeedCapabilities(ok, fail)
        }?.entries?.firstOrNull()?.let { (a, b) ->
            c = c.copy(fanArea = a, fanMin = b.minValue ?: c.fanMin, fanMax = b.maxValue ?: c.fanMax)
        }
        call<List<SeatArea>> { ok, fail -> hvac.checkHvacPowerCapabilities(ok, fail) }
            ?.firstOrNull()?.let { c = c.copy(powerArea = it) }
        call<Map<SeatArea, ai.pleos.playground.vehicle.api.model.capability.AreaBounds<Float>>> { ok, fail ->
            hvac.checkHvacTemperatureCapabilities(ok, fail)
        }?.let { m ->
            // 운전석을 우선으로 쓴다
            val e = m.entries.firstOrNull { it.key == SeatArea(SeatValue.ROW_1_LEFT) } ?: m.entries.firstOrNull()
            e?.let { (a, b) -> c = c.copy(tempArea = a, tempMin = b.minValue ?: c.tempMin, tempMax = b.maxValue ?: c.tempMax) }
        }
        call<Map<SeatArea, ai.pleos.playground.vehicle.api.model.capability.AreaBounds<Int>>> { ok, fail ->
            seat.checkSeatTemperatureCapabilities(ok, fail)
        }?.let { m ->
            val e = m.entries.firstOrNull { it.key == SeatArea(SeatValue.ROW_1_LEFT) } ?: m.entries.firstOrNull()
            e?.let { (a, b) -> c = c.copy(seatArea = a, seatMax = b.maxValue ?: c.seatMax) }
        }
        call<Map<WindowArea, ai.pleos.playground.vehicle.api.model.capability.AreaBounds<Int>>> { ok, fail ->
            window.checkWindowPositionCapabilities(ok, fail)
        }?.let { m ->
            val e = m.entries.firstOrNull { it.key == WindowArea(WindowValue.ROW_1_LEFT) } ?: m.entries.firstOrNull()
            e?.let { (a, b) -> c = c.copy(windowArea = a, windowMax = b.maxValue ?: c.windowMax) }
        }
        call<Map<WindowArea, List<WiperSwitch>>> { ok, fail -> wiper.checkWiperSwitchCapabilities(ok, fail) }
            ?.let { m ->
                val a = m.keys.firstOrNull { it == WindowArea(WindowValue.FRONT_WINDSHIELD) } ?: m.keys.firstOrNull()
                val supported = a?.let { m[it] }.orEmpty()
                // 약한 순서로 골라 쓴다. 차종마다 지원 모드가 다르다
                val preferred = listOf(
                    WiperSwitch.INTERMITTENT_LEVEL_1, WiperSwitch.INTERMITTENT_LEVEL_2, WiperSwitch.MIST,
                    WiperSwitch.CONTINUOUS_LEVEL_1, WiperSwitch.CONTINUOUS_LEVEL_2, WiperSwitch.AUTO,
                )
                val on = preferred.firstOrNull { it in supported }
                    ?: supported.firstOrNull { it != WiperSwitch.OFF && it != WiperSwitch.OTHER && it != WiperSwitch.SERVICE }
                Log.i(TAG, "wiper supported: $supported → use $on")
                c = c.copy(wiperArea = a, wiperOn = on)
            }
        call<List<SeatArea>> { ok, fail ->
            light.checkAmbientLightBrightnessColorCapabilities({ _, list -> ok(list) }, fail)
        }?.firstOrNull()?.let { c = c.copy(ambientArea = it) }
        call<List<SeatArea>> { ok, fail ->
            hvac.checkHvacTemperatureCurrentCapabilities({ _, list -> ok(list) }, fail)
        }?.let { list -> c = c.copy(cabinArea = list.firstOrNull { it == SeatArea(SeatValue.ROW_1_LEFT) } ?: list.firstOrNull()) }
        return c
    }

    // ---- 스냅샷 / 복구 ----

    suspend fun snapshot() = Snapshot(
        power = caps.powerArea?.let { a -> call { ok, fail -> hvac.getHvacPowerState(a, ok, fail) } },
        fan = caps.fanArea?.let { a -> call { ok, fail -> hvac.getHvacFanSpeed(a, ok, fail) } },
        temp = caps.tempArea?.let { a -> call { ok, fail -> hvac.getHvacTemperature(a, TemperatureUnit.CELSIUS, ok, fail) } },
        seatHeat = caps.seatArea?.let { a -> call { ok, fail -> seat.getHvacSeatTemperature(a, ok, fail) } },
        window = caps.windowArea?.let { a -> call { ok, fail -> window.getCurrentWindowPosition(a, ok, fail) } },
        ambientColor = caps.ambientArea?.let { a -> call { ok, fail -> light.getAmbientLightBrightnessColor(a, ok, fail) } },
    ).also { Log.i(TAG, "snapshot: $it") }

    suspend fun restore(s: Snapshot) {
        Log.i(TAG, "restore: $s")
        s.window?.let { setWindow(it) }
        s.seatHeat?.let { setSeatHeat(it) }
        s.temp?.let { setTemp(it) }
        s.fan?.let { setFan(it) }
        setWiper(false)
        s.ambientColor?.let { setAmbientColor(it) }
        s.power?.let { setPower(it) }
    }

    // ---- 제어 (성공하면 차가 확인한 값, 실패하면 null) ----

    suspend fun setPower(on: Boolean): Boolean? = caps.powerArea?.let { a ->
        call { ok, fail -> hvac.setHvacPower(a, on, { _, v -> ok(v) }, fail) }
    }

    /** 바람 세기를 범위 안으로 */
    fun clampFan(level: Int) = level.coerceIn(caps.fanMin, caps.fanMax)

    suspend fun setFan(level: Int): Int? = caps.fanArea?.let { a ->
        val v = level.coerceIn(caps.fanMin, caps.fanMax)
        call { ok, fail -> hvac.setHvacFanSpeed(a, v, { _, r -> ok(r) }, fail) }
    }

    suspend fun setTemp(celsius: Float): Float? = caps.tempArea?.let { a ->
        val v = celsius.coerceIn(caps.tempMin, caps.tempMax)
        call { ok, fail -> hvac.setHvacTemperature(a, v, TemperatureUnit.CELSIUS, { _, r -> ok(r) }, fail) }
    }

    suspend fun setSeatHeat(level: Int): Int? = caps.seatArea?.let { a ->
        val v = level.coerceIn(0, caps.seatMax)
        call { ok, fail -> seat.setHvacSeatTemperature(a, v, { _, r -> ok(r) }, fail) }
    }

    /** 창문 위치(차 단위 그대로). 0 = 닫힘 */
    suspend fun setWindow(position: Int): Int? = caps.windowArea?.let { a ->
        val v = position.coerceIn(0, caps.windowMax)
        call { ok, fail -> window.setWindowPosition(a, v, { _, r -> ok(r) }, fail) }
    }

    suspend fun setWiper(on: Boolean): WiperSwitch? = caps.wiperArea?.let { a ->
        val v = if (on) caps.wiperOn ?: return null else WiperSwitch.OFF
        call { ok, fail -> wiper.setWiperSwitch(a, v, { _, r -> ok(r) }, fail) }
    }

    /** 실내 현재 온도 (°C). 차가 지원하지 않으면 null */
    suspend fun cabinTemp(): Float? = caps.cabinArea?.let { a ->
        call { ok, fail -> hvac.getHvacTemperatureCurrent(a, TemperatureUnit.CELSIUS, ok, fail) }
    }

    /** 무드등 색 (0xRRGGBB) */
    suspend fun setAmbientColor(rgb: Int): Int? = caps.ambientArea?.let { a ->
        call { ok, fail -> light.setAmbientLightBrightnessColor(a, rgb and 0xFFFFFF, { _, r -> ok(r) }, fail) }
    }

    // ---- 레이스 몰입용 추가 장치 ----

    var extra = ExtraCaps()
        private set

    suspend fun loadExtras(): ExtraCaps {
        var c = ExtraCaps()
        call<List<LightSwitch>> { ok, fail -> light.checkCabinLightSwitchCapabilities(ok, fail) }
            ?.let { if (LightSwitch.ON in it) c = c.copy(cabinLight = true) }
        call<Map<SeatArea, List<LightSwitch>>> { ok, fail -> light.checkReadingLightSwitchCapabilities(ok, fail) }
            ?.keys?.let { k -> c = c.copy(readingArea = k.firstOrNull { it == driverSeat } ?: k.firstOrNull()) }
        call<Map<SeatArea, ai.pleos.playground.vehicle.api.model.capability.AreaBounds<Int>>> { ok, fail ->
            light.checkAmbientLightBrightnessCapabilities({ _, m -> ok(m) }, fail)
        }?.entries?.firstOrNull()?.let { (a, b) -> c = c.copy(brightArea = a, brightMin = b.minValue ?: 0, brightMax = b.maxValue ?: 100) }
        call<ai.pleos.playground.vehicle.api.model.capability.AreaBounds<Int>> { ok, fail ->
            steering.checkHvacSteeringWheelHeatCapabilities(ok, fail)
        }?.let { b -> c = c.copy(wheelHeatMax = b.maxValue ?: 3) }
        call<Map<SeatArea, ai.pleos.playground.vehicle.api.model.capability.AreaBounds<Int>>> { ok, fail ->
            seat.checkSeatVentilationCapabilities(ok, fail)
        }?.let { m ->
            val e = m.entries.firstOrNull { it.key == driverSeat } ?: m.entries.firstOrNull()
            e?.let { (a, b) -> c = c.copy(ventArea = a, ventMax = b.maxValue ?: 3) }
        }
        call<Map<SeatArea, List<FanDirectionType>>> { ok, fail -> hvac.checkHvacFanDirectionCapabilities(ok, fail) }
            ?.entries?.firstOrNull()?.let { (a, d) -> c = c.copy(dirArea = a, dirs = d) }
        call<List<SeatArea>> { ok, fail -> hvac.checkHvacRecirculationCapabilities(ok, fail) }
            ?.firstOrNull()?.let { c = c.copy(recircArea = it) }
        extra = c
        Log.i(TAG, "extra capabilities: $c")
        return c
    }

    suspend fun extraSnapshot() = ExtraSnapshot(
        cabin = if (extra.cabinLight) call { ok, fail -> light.getCabinLightSwitch(ok, fail) } else null,
        reading = extra.readingArea?.let { a -> call { ok, fail -> light.getReadingLightSwitch(a, ok, fail) } },
        brightness = extra.brightArea?.let { a -> call { ok, fail -> light.getAmbientLightBrightness(a, ok, fail) } },
        wheelHeat = extra.wheelHeatMax?.let { call { ok, fail -> steering.getHvacSteeringWheelHeatState(ok, fail) } },
        vent = extra.ventArea?.let { a -> call { ok, fail -> seat.getHvacSeatVentilationState(a, ok, fail) } },
        direction = extra.dirArea?.let { a -> call { ok, fail -> hvac.getHvacFanDirection(a, ok, fail) } },
        recirc = extra.recircArea?.let { a -> call { ok, fail -> hvac.getHvacRecirculationState(a, ok, fail) } },
    ).also { Log.i(TAG, "extra snapshot: $it") }

    suspend fun restoreExtras(s: ExtraSnapshot) {
        Log.i(TAG, "extra restore: $s")
        s.cabin?.let { setCabinLight(it == LightSwitch.ON) }
        s.reading?.let { setReadingLight(it == LightSwitch.ON) }
        s.brightness?.let { setAmbientBrightness(it) }
        s.wheelHeat?.let { setWheelHeat(it) }
        s.vent?.let { setSeatVent(it) }
        s.direction?.let { if (it != FanDirectionType.UNKNOWN) setFanDirection(it) }
        s.recirc?.let { setRecirc(it) }
    }

    suspend fun setCabinLight(on: Boolean): LightSwitch? = if (!extra.cabinLight) null else
        call { ok, fail -> light.setCabinLightSwitch(if (on) LightSwitch.ON else LightSwitch.OFF, ok, fail) }

    suspend fun setReadingLight(on: Boolean): LightSwitch? = extra.readingArea?.let { a ->
        call { ok, fail -> light.setReadingLightSwitch(a, if (on) LightSwitch.ON else LightSwitch.OFF, ok, fail) }
    }

    suspend fun setAmbientBrightness(v: Int): Int? = extra.brightArea?.let { a ->
        call { ok, fail -> light.setAmbientLightBrightness(a, v.coerceIn(extra.brightMin, extra.brightMax), { _, r -> ok(r) }, fail) }
    }

    suspend fun setWheelHeat(level: Int): Int? = extra.wheelHeatMax?.let { max ->
        call { ok, fail -> steering.setHvacSteeringWheelHeat(level.coerceIn(0, max), ok, fail) }
    }

    suspend fun setSeatVent(level: Int): Int? = extra.ventArea?.let { a ->
        call { ok, fail -> seat.setHvacSeatVentilation(a, level.coerceIn(0, extra.ventMax), { _, r -> ok(r) }, fail) }
    }

    suspend fun setFanDirection(d: FanDirectionType): FanDirectionType? = extra.dirArea?.let { a ->
        if (extra.dirs.isNotEmpty() && d !in extra.dirs) return null
        call { ok, fail -> hvac.setHvacFanDirection(a, d, { _, r -> ok(r) }, fail) }
    }

    suspend fun setRecirc(on: Boolean): Boolean? = extra.recircArea?.let { a ->
        call { ok, fail -> hvac.setHvacRecirculation(a, on, { _, r -> ok(r) }, fail) }
    }

    // ---- 공통 ----

    /** 콜백 API를 suspend로. 실패하거나 3초 안에 답이 없으면 null. */
    private suspend fun <T> call(block: (ok: (T?) -> Unit, fail: (Exception) -> Unit) -> Unit): T? =
        withTimeoutOrNull(3_000) {
            suspendCancellableCoroutine<T?> { c ->
                runCatching {
                    block(
                        { v -> if (c.isActive) c.resume(v) },
                        { e -> warn("호출", e); if (c.isActive) c.resume(null) },
                    )
                }.onFailure { e -> warn("호출", e as? Exception ?: Exception(e)); if (c.isActive) c.resume(null) }
            }
        }

    private fun warn(what: String, e: Exception) { Log.w(TAG, "$what 실패: ${e.javaClass.simpleName} ${e.message}") }
}
