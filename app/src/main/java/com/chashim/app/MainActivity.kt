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
    private val ready = MutableStateFlow(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vehicle = VehicleGateway(this)
        engine = SessionEngine(vehicle, lifecycleScope)
        lifecycleScope.launch {
            vehicle.start()
            ready.value = true
        }

        setContent {
            ChashimTheme {
                val phase by engine.phase.collectAsState()
                when (val p = phase) {
                    SessionPhase.Idle -> HomeScreen(
                        theme = RainForest,
                        ready = ready.collectAsState().value,
                        gear = vehicle.gearState.collectAsState().value,
                        charging = vehicle.charging.collectAsState().value,
                        battery = vehicle.battery.collectAsState().value,
                        onStart = { engine.start(RainForest) },
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
                        onStop = { engine.stop() },
                    )
                }
            }
        }
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
        when (intent.getStringExtra("cmd")) {
            // 차 정보를 다 받은 뒤에 시작한다. 그래야 시작 전 설정을 저장하고 나중에 복구할 수 있다.
            "start" -> lifecycleScope.launch { ready.first { it }; engine.start(RainForest) }
            "stop" -> engine.stop()
            "home" -> engine.reset()
        }
    }

    override fun onDestroy() {
        engine.stop("앱이 종료됐어요")
        vehicle.release()
        super.onDestroy()
    }
}
