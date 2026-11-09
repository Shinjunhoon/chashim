package com.chashim.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun FinishScreen(reason: String, onHome: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Palette.bg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("푹 쉬셨나요?", color = Palette.text, fontSize = 44.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(reason, color = Palette.muted, fontSize = 22.sp)
        Spacer(Modifier.height(8.dp))
        Text("바람 · 온도 · 열선 · 창문 · 무드등을 시작 전 설정으로 되돌렸어요", color = Palette.accent, fontSize = 20.sp)
        Spacer(Modifier.height(40.dp))
        Button(
            onClick = onHome,
            modifier = Modifier.height(64.dp).width(220.dp),
            shape = RoundedCornerShape(32.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Palette.bg),
        ) { Text("처음으로", fontSize = 22.sp, fontWeight = FontWeight.Bold) }
    }
}
