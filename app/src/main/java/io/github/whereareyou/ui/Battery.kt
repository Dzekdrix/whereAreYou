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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.whereareyou.R

/** Below this level the battery is drawn in red. */
private const val LOW_BATTERY = 15

/** Small battery icon filled up to [level] (%), followed by the percentage. */
@Composable
fun BatteryBadge(level: Int, modifier: Modifier = Modifier) {
    val color = if (level <= LOW_BATTERY) MaterialTheme.colorScheme.error else LocalContentColor.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        BatteryIcon(level, color)
        Spacer(Modifier.width(4.dp))
        Text(stringResource(R.string.battery_percent, level), style = MaterialTheme.typography.labelMedium, color = color)
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
