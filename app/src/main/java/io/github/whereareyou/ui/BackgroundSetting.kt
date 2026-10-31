package io.github.whereareyou.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.whereareyou.R
import io.github.whereareyou.WhereApp
import io.github.whereareyou.service.KeepAlive
import io.github.whereareyou.service.LocationService

/**
 * "Background location" switch. Turning it on walks the user through what Android requires:
 * location "Allow all the time", then the battery optimization exemption.
 */
@Composable
fun BackgroundLocationSetting(app: WhereApp, sharing: Boolean, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val store = app.store
    var setting by remember { mutableStateOf(store.backgroundLocation) }
    var explain by remember { mutableStateOf(false) }

    // Permissions can change in the system settings: re-check each time we come back.
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumes++
        onPauseOrDispose {}
    }
    val active = remember(setting, resumes) { setting && KeepAlive.isActive(context) }

    fun apply(on: Boolean) {
        store.backgroundLocation = on
        setting = on
        KeepAlive.update(context)
        LocationService.refresh(context, sharing)
    }

    lateinit var next: () -> Unit
    val batteryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (KeepAlive.isBatteryExempt(context)) next() else onMessage(context.getString(R.string.bg_battery_refused))
    }
    val backgroundLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) next() else onMessage(context.getString(R.string.bg_location_refused))
    }
    next = {
        when {
            !KeepAlive.hasForegroundLocation(context) -> onMessage(context.getString(R.string.bg_need_location))
            !KeepAlive.hasBackgroundLocation(context) -> explain = true
            !KeepAlive.isBatteryExempt(context) -> batteryLauncher.launch(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
            )
            else -> {
                apply(true)
                onMessage(context.getString(R.string.bg_enabled))
            }
        }
    }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.bg_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = active, onCheckedChange = { on -> if (on) next() else apply(false) })
        }
        Text(stringResource(R.string.bg_hint), style = MaterialTheme.typography.bodySmall)
        if (setting && !active) {
            // Was on, but a permission was removed meanwhile.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.bg_incomplete),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = next) { Text(stringResource(R.string.bg_fix)) }
            }
        }
        TextButton(
            onClick = {
                val brand = Build.MANUFACTURER.lowercase().replace(" ", "-")
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://dontkillmyapp.com/$brand")))
            },
            modifier = Modifier.padding(top = 4.dp),
        ) { Text(stringResource(R.string.bg_oem)) }
    }

    if (explain) {
        AlertDialog(
            onDismissRequest = { explain = false },
            title = { Text(stringResource(R.string.bg_rationale_title)) },
            text = { Text(stringResource(R.string.bg_rationale_text)) },
            confirmButton = {
                TextButton(onClick = {
                    explain = false
                    backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }) { Text(stringResource(R.string.bg_continue)) }
            },
            dismissButton = { TextButton(onClick = { explain = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
