package io.github.whereareyou.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.whereareyou.R
import io.github.whereareyou.data.BatteryState

/** Below this level the battery is drawn in red (unless it is charging). */
private const val LOW_BATTERY = 15

/** Small battery icon filled up to the level, a lightning bolt while charging, then the percentage. */
@Composable
fun BatteryBadge(battery: BatteryState, modifier: Modifier = Modifier) {
    val low = battery.level <= LOW_BATTERY && !battery.charging
    val color = if (low) MaterialTheme.colorScheme.error else LocalContentColor.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        BatteryIcon(battery.level, color)
        if (battery.charging) {
            Spacer(Modifier.width(2.dp))
            BoltIcon(color)
        }
        Spacer(Modifier.width(4.dp))
        Text(stringResource(R.string.battery_percent, battery.level), style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
private fun BatteryIcon(level: Int, color: Color) {
    Canvas(Modifier.size(width = 18.dp, height = 10.dp)) {
        val stroke = 1.2.dp.toPx()
        val nub = 2.dp.toPx()
        val body = Size(size.width - nub, size.height)
        val radius = CornerRadius(2.dp.toPx())
        drawRoundRect(color, topLeft = Offset(stroke / 2, stroke / 2), size = Size(body.width - stroke, body.height - stroke), cornerRadius = radius, style = Stroke(stroke))
        drawRect(color, topLeft = Offset(body.width, size.height * 0.3f), size = Size(nub, size.height * 0.4f))
        val inset = stroke * 2
        val fullWidth = body.width - inset * 2
        drawRect(
            color,
            topLeft = Offset(inset, inset),
            size = Size(fullWidth * level.coerceIn(0, 100) / 100f, body.height - inset * 2),
        )
    }
}

/** Lightning bolt shown next to the battery while charging. */
@Composable
private fun BoltIcon(color: Color) {
    Canvas(Modifier.size(width = 7.dp, height = 11.dp)) {
        val w = size.width
        val h = size.height
        val bolt = Path().apply {
            moveTo(w * 0.65f, 0f)
            lineTo(0f, h * 0.58f)
            lineTo(w * 0.45f, h * 0.58f)
            lineTo(w * 0.35f, h)
            lineTo(w, h * 0.42f)
            lineTo(w * 0.55f, h * 0.42f)
            close()
        }
        drawPath(bolt, color)
    }
}
