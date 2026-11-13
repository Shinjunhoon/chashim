package com.chashim.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import com.chashim.app.engine.RainForest
import com.chashim.app.engine.SessionEngine
import com.chashim.app.engine.SessionPhase
import com.chashim.app.engine.race.RaceEngine
import com.chashim.app.engine.race.RacePhase
import com.chashim.app.ui.RaceResultScreen
import com.chashim.app.ui.RaceScreen
import com.chashim.app.ui.ChashimTheme
import com.chashim.app.ui.FinishScreen
import com.chashim.app.ui.HomeScreen
import com.chashim.app.ui.PlayerScreen
import com.chashim.app.vehicle.VehicleGateway
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var vehicle: VehicleGateway
    private lateinit var engine: SessionEngine
    private lateinit var race: RaceEngine
    private val ready = MutableStateFlow(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vehicle = VehicleGateway(this)
        engine = SessionEngine(vehicle, lifecycleScope)
        race = RaceEngine(vehicle, lifecycleScope, this)
        lifecycleScope.launch {
            vehicle.start()
            ready.value = true
            engine.watchCharging()
        }

        setContent {
            ChashimTheme {
                val phase by engine.phase.collectAsState()
                val racePhase by race.phase.collectAsState()
                val rp = racePhase
                if (rp is RacePhase.Result) {
                    RaceResultScreen(rp, onHome = race::reset, onAgain = { race.reset(); startRace() })
                } else if (rp != RacePhase.Idle) {
                    RaceScreen(
                        phase = rp,
                        info = race.info.collectAsState().value,
                        effects = race.effects.collectAsState().value,
                        steer = race::steer,
                        braking = race::braking,
                        onEvent = race::onEvent,
                        onStop = { race.stop() },
                    )
                } else when (val p = phase) {
                    SessionPhase.Idle -> HomeScreen(
                        theme = RainForest,
                        ready = ready.collectAsState().value,
                        gear = vehicle.gearState.collectAsState().value,
                        charging = vehicle.charging.collectAsState().value,
                        battery = vehicle.battery.collectAsState().value,
                        charge = engine.charge.collectAsState().value,
                        onStart = { engine.start(RainForest) },
                        onFly = ::startRace,
                    )
                    is SessionPhase.Finished -> FinishScreen(reason = p.reason, onHome = engine::reset)
                    else -> PlayerScreen(
                        theme = RainForest,
                        phase = p,
                        effects = engine.effects.collectAsState().value,
                        flash = engine.flash.collectAsState().value,
                        charging = vehicle.charging.collectAsState().value,
                        battery = vehicle.battery.collectAsState().value,
                        climate = engine.climate.collectAsState().value,
                        charge = engine.charge.collectAsState().value,
                        onStop = { engine.stop() },
                    )
                }
            }
        }
    }

    /** 힐링 세션이 돌고 있지 않을 때만 레이스를 시작한다 */
    private fun startRace() {
        if (engine.phase.value == SessionPhase.Idle) race.start()
    }

    /** 시연·테스트용: adb shell am start -n com.chashim.app/.MainActivity --es cmd start|stop */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        // 테스트용: 실내 온도 가정 (--ef cabin 28), 해제 (--ez cabin_reset true)
        if (intent.hasExtra("cabin")) {
            engine.cabinOverride = intent.getFloatExtra("cabin", 0f)
            android.util.Log.i("Chashim", "cabin override ${engine.cabinOverride}")
        }
        if (intent.getBooleanExtra("cabin_reset", false)) engine.cabinOverride = null
        // 테스트용: 충전 속도 가정 (--ef charge_kw 50), 해제 (--ef charge_kw 0)
        if (intent.hasExtra("charge_kw")) {
            engine.assumedChargeKw = intent.getFloatExtra("charge_kw", 0f).takeIf { it > 0f }
            android.util.Log.i("Chashim", "charge kW override ${engine.assumedChargeKw}")
        }
        // 테스트용: 핸들 각도 가정 (--ef steer -30), 해제 (--ez steer_reset true). 브레이크 (--ez brake true|false), 해제 (--ez brake_reset true)
        if (intent.hasExtra("steer")) race.steerOverride = intent.getFloatExtra("steer", 0f)
        if (intent.getBooleanExtra("steer_reset", false)) race.steerOverride = null
        if (intent.hasExtra("brake")) race.brakeOverride = intent.getBooleanExtra("brake", false)
        if (intent.getBooleanExtra("brake_reset", false)) race.brakeOverride = null
        when (intent.getStringExtra("cmd")) {
            "fly", "race" -> lifecycleScope.launch { ready.first { it }; startRace() }
            // 차 정보를 다 받은 뒤에 시작한다. 그래야 시작 전 설정을 저장하고 나중에 복구할 수 있다.
            "start" -> lifecycleScope.launch { ready.first { it }; engine.start(RainForest) }
            "stop" -> { engine.stop(); race.stop() }
            "home" -> { engine.reset(); race.reset() }
        }
    }

    override fun onDestroy() {
        engine.stop("앱이 종료됐어요")
        race.stop("앱이 종료됐어요")
        vehicle.release()
        super.onDestroy()
    }
}
