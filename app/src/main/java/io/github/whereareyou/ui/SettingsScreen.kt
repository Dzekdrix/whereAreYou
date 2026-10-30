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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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

private val INTERVALS = listOf(30 to "30 s", 60 to "1 min", 300 to "5 min", 900 to "15 min")

@Composable
fun SettingsScreen(app: WhereApp, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val store = app.store
    var name by remember { mutableStateOf(store.displayName) }
    var interval by remember { mutableIntStateOf(store.intervalSeconds) }
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

        Text(stringResource(R.string.interval), style = MaterialTheme.typography.titleMedium)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            INTERVALS.forEachIndexed { i, (seconds, label) ->
                SegmentedButton(
                    selected = interval == seconds,
                    onClick = {
                        interval = seconds
                        store.intervalSeconds = seconds
                    },
                    shape = SegmentedButtonDefaults.itemShape(i, INTERVALS.size),
                ) { Text(label) }
            }
        }
        Text(stringResource(R.string.interval_hint), style = MaterialTheme.typography.bodySmall)

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
