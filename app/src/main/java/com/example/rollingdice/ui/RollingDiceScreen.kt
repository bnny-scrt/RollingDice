package com.example.rollingdice.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rollingdice.dice.DieSim
import com.example.rollingdice.dice.drawDieScene

private val Ink = Color(0xFF101010)
private val Muted = Color(0xFF9A9A9A)
private val Hairline = Color(0xFFE2E2E2)

/**
 * The whole app: a die that can be thrown across the table, drawn in black and
 * white with soft shadows.
 */
@Composable
fun RollingDiceScreen() {
    val sim = remember { DieSim() }
    val history = remember { mutableStateListOf<Int>() }
    var result by remember { mutableIntStateOf(1) }
    val haptics = LocalHapticFeedback.current

    LaunchedEffect(sim) {
        sim.onSettled = { value ->
            result = value
            history.add(0, value)
            while (history.size > 8) history.removeAt(history.lastIndex)
        }
    }

    // Fixed-ish step physics driven by the display refresh rate.
    LaunchedEffect(sim) {
        var previous = 0L
        while (true) {
            val now = withFrameNanos { it }
            val delta = if (previous == 0L) {
                1f / 60f
            } else {
                ((now - previous) / 1_000_000_000.0).toFloat().coerceIn(0.0005f, 0.05f)
            }
            previous = now
            sim.update(delta)
        }
    }

    val settled = sim.phase == DieSim.Phase.SETTLED || sim.phase == DieSim.Phase.IDLE

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .pointerInput(Unit) {
                detectTapGestures {
                    if (!sim.isBusy) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        sim.roll()
                    }
                }
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
        ) {
            Header()

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                drawDieScene(sim)
            }

            Footer(
                value = result,
                settled = settled,
                history = history
            )
        }
    }
}

@Composable
private fun Header() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "ROLLING DICE",
            color = Ink,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 4.sp
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = "ONE DIE",
            color = Muted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 4.sp
        )
    }
}

@Composable
private fun Footer(
    value: Int,
    settled: Boolean,
    history: List<Int>
) {
    val scale by animateFloatAsState(
        targetValue = if (settled) 1f else 0.90f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 260f),
        label = "valueScale"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value.toString(),
            color = if (settled) Ink else Muted,
            fontSize = 76.sp,
            fontWeight = FontWeight.Thin,
            fontFamily = FontFamily.Default,
            textAlign = TextAlign.Center,
            modifier = Modifier.graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
        )
        Text(
            text = if (settled) "TAP TO ROLL" else "ROLLING",
            color = Muted,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 4.sp
        )

        if (history.isNotEmpty()) {
            Spacer(modifier = Modifier.height(22.dp))
            HistoryStrip(history)
        }
    }
}

@Composable
private fun HistoryStrip(history: List<Int>) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        history.forEach { result ->
            Box(
                modifier = Modifier
                    .size(width = 30.dp, height = 30.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .border(1.dp, Hairline, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = result.toString(),
                    color = Ink,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
