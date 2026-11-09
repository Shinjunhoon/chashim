package com.chashim.app.ui

import ai.pleos.playground.vehicle.constant.control.VehicleGear
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chashim.app.engine.Theme

@Composable
fun HomeScreen(
    theme: Theme,
    ready: Boolean,
    gear: VehicleGear?,
    charging: Boolean,
    battery: Int?,
    charge: com.chashim.app.engine.ChargeEstimate?,
    onStart: () -> Unit,
) {
    val parked = gear == VehicleGear.GEAR_PARK
    // 충전구를 꽂으면 안내창을 띄운다. "나중에"를 누르면 다음 충전 때까지 숨긴다
    var dismissed by remember { mutableStateOf(false) }
    LaunchedEffect(charging) { if (!charging) dismissed = false }
    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.bg)
            .padding(horizontal = 56.dp, vertical = 40.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("차쉼", color = Palette.text, fontSize = 40.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(16.dp))
            Text("기다리는 시간을 쉬는 시간으로", color = Palette.muted, fontSize = 20.sp)
        }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatusChip(
                label = when {
                    !ready -> "차량 연결 중…"
                    parked -> "P 주차 중"
                    else -> "주차(P) 상태에서 이용할 수 있어요"
                },
                ok = parked,
            )
            if (charging) StatusChip("⚡ 충전 중", ok = true)
            battery?.let { StatusChip("배터리 $it%", ok = true) }
        }

        Spacer(Modifier.height(32.dp))
        // 테마 카드
        Box(
            Modifier
                .fillMaxWidth()
                .height(300.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF1E4D3B), Color(0xFF0E2430)))),
        ) {
            RainCanvas(intensity = 0.4f, modifier = Modifier.fillMaxSize())
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(32.dp),
            ) {
                Text("힐링", color = Palette.accent, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(theme.title, color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Bold)
                Text(theme.subtitle, color = Color.White.copy(alpha = 0.75f), fontSize = 20.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    "바람 · 온도 · 시트 열선 · 와이퍼 · 창문 · 무드등이 장면에 맞춰 움직여요",
                    color = Color.White.copy(alpha = 0.6f), fontSize = 17.sp,
                )
            }
            Button(
                onClick = onStart,
                enabled = ready && parked,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(32.dp)
                    .height(72.dp)
                    .width(220.dp),
                shape = RoundedCornerShape(36.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Palette.bg),
            ) {
                Text("시작하기", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
    if (charging && ready && parked && !dismissed) {
        ChargePrompt(battery = battery, charge = charge, onStart = onStart, onLater = { dismissed = true })
    }
    }
}

@Composable
private fun ChargePrompt(battery: Int?, charge: com.chashim.app.engine.ChargeEstimate?, onStart: () -> Unit, onLater: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(640.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(Palette.surface)
                .padding(40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("⚡", fontSize = 56.sp)
            Spacer(Modifier.height(8.dp))
            Text("충전을 시작했어요", color = Palette.text, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(
                battery?.let { "지금 $it% · 목표 ${com.chashim.app.engine.CHARGE_TARGET}%" } ?: "충전 중",
                color = Palette.accent, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Spacer(Modifier.height(4.dp))
            Text(chargeLine(charge), color = Palette.text, fontSize = 20.sp)
            Spacer(Modifier.height(6.dp))
            Text("충전이 끝나는 시간에 맞춰 비 오는 숲에서 쉬어 갈까요?", color = Palette.muted, fontSize = 20.sp)
            Text("충전이 끝날 때쯤 비가 그치고 햇살이 비쳐요", color = Palette.muted, fontSize = 18.sp)
            Spacer(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(
                    onClick = onLater,
                    modifier = Modifier.height(64.dp).width(200.dp),
                    shape = RoundedCornerShape(32.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.bg, contentColor = Palette.text),
                ) { Text("나중에", fontSize = 20.sp) }
                Button(
                    onClick = onStart,
                    modifier = Modifier.height(64.dp).width(240.dp),
                    shape = RoundedCornerShape(32.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Palette.bg),
                ) { Text("쉬기 시작", fontSize = 22.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun StatusChip(label: String, ok: Boolean) {
    Text(
        label,
        color = if (ok) Palette.text else Palette.warn,
        fontSize = 17.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Palette.surface)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
