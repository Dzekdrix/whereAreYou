package io.github.whereareyou.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.whereareyou.BuildConfig
import io.github.whereareyou.R
import io.github.whereareyou.WhereApp
import io.github.whereareyou.nostr.RelayPool
import io.github.whereareyou.service.LocationService
import kotlin.math.roundToInt

/** Slider steps, in seconds. */
private val MOVING_STEPS = listOf(30, 60, 120, 300, 600, 900, 1800, 3600, 7200, 10800)
private val STILL_STEPS = listOf(300, 600, 900, 1800, 3600, 7200, 10800, 14400, 21600)

private fun nearestIndex(steps: List<Int>, seconds: Int) = steps.indices.minBy { kotlin.math.abs(steps[it] - seconds) }

@Composable
private fun formatSeconds(seconds: Int): String = when {
    seconds < 60 -> stringResource(R.string.duration_seconds, seconds)
    seconds < 3600 -> stringResource(R.string.duration_minutes, seconds / 60)
    else -> stringResource(R.string.duration_hours, seconds / 3600)
}

/** A titled slider over [steps] (seconds). */
@Composable
private fun IntervalSlider(title: String, hint: String, steps: List<Int>, seconds: Int, onChange: (Int) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(
                when (seconds) {
                    60 -> stringResource(R.string.every_minute)
                    3600 -> stringResource(R.string.every_hour)
                    else -> stringResource(R.string.every, formatSeconds(seconds))
                },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = nearestIndex(steps, seconds).toFloat(),
            onValueChange = { onChange(steps[it.roundToInt().coerceIn(steps.indices)]) },
            valueRange = 0f..(steps.size - 1).toFloat(),
            steps = steps.size - 2,
        )
        Text(hint, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun SettingsScreen(app: WhereApp, onToggleSharing: () -> Unit, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val store = app.store
    var name by remember { mutableStateOf(store.displayName) }
    var moving by remember { mutableIntStateOf(store.movingIntervalSeconds) }
    var still by remember { mutableIntStateOf(store.stillIntervalSeconds) }
    val sharing by app.repo.sharingActive.collectAsStateWithLifecycle()

    // The two settings stay consistent: never confirm more often while still than while moving.
    fun saveIntervals(newMoving: Int, newStill: Int) {
        moving = newMoving
        still = newStill
        store.movingIntervalSeconds = newMoving
        store.stillIntervalSeconds = newStill
        LocationService.refresh(context, sharing)
    }
    var relays by remember { mutableStateOf(store.relays.joinToString("\n")) }
    val status by app.repo.relayStatus.collectAsStateWithLifecycle()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = {
                name = it
                if (it.isNotBlank()) store.displayName = it
            },
            label = { Text(stringResource(R.string.display_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.start_sharing), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Switch(checked = sharing, onCheckedChange = { onToggleSharing() })
        }

        BackgroundLocationSetting(app, sharing, onMessage)

        HorizontalDivider()

        IntervalSlider(
            title = stringResource(R.string.interval_moving),
            hint = stringResource(R.string.interval_moving_hint),
            steps = MOVING_STEPS,
            seconds = moving,
            onChange = { m -> saveIntervals(m, if (still < m) STILL_STEPS.first { it >= m } else still) },
        )
        IntervalSlider(
            title = stringResource(R.string.interval_still),
            hint = stringResource(R.string.interval_still_hint),
            steps = STILL_STEPS,
            seconds = still,
            onChange = { st -> saveIntervals(if (moving > st) MOVING_STEPS.last { it <= st } else moving, st) },
        )

        HorizontalDivider()
        Text(stringResource(R.string.relays), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.relays_hint), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = relays,
            onValueChange = { relays = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
        )
        Button(onClick = {
            app.repo.updateRelays(relays.lines().map { it.trim() }.filter { it.isNotEmpty() })
            onMessage(context.getString(R.string.saved))
        }) { Text(stringResource(R.string.save)) }

        status.toSortedMap().forEach { (url, state) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(10.dp).clip(CircleShape).background(
                        when (state) {
                            RelayPool.State.CONNECTED -> Color(0xFF2E7D32)
                            RelayPool.State.CONNECTING -> Color(0xFFF9A825)
                            RelayPool.State.DISCONNECTED -> Color(0xFFC62828)
                        }
                    )
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "$url — " + stringResource(
                        when (state) {
                            RelayPool.State.CONNECTED -> R.string.relay_connected
                            RelayPool.State.CONNECTING -> R.string.relay_connecting
                            RelayPool.State.DISCONNECTED -> R.string.relay_disconnected
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        HorizontalDivider()
        Text(stringResource(R.string.about), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.about_text, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodySmall)
    }
}
