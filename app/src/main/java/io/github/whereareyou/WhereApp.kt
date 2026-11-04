package io.github.whereareyou

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import io.github.whereareyou.data.BatteryState
import io.github.whereareyou.data.LocationRepository
import io.github.whereareyou.data.Store
import io.github.whereareyou.service.KeepAlive
import org.maplibre.android.MapLibre

class WhereApp : Application() {
    lateinit var store: Store
        private set
    lateinit var repo: LocationRepository
        private set

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        repo = LocationRepository(store) { batteryState() }

        MapLibre.getInstance(this)

        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_SHARING, getString(R.string.channel_sharing), NotificationManager.IMPORTANCE_LOW)
        )

        // Process restarted after being killed: resume sharing if the "background location" setting allows it.
        KeepAlive.ensureRunning(this)
        KeepAlive.update(this)
    }

    /** Current battery state (read from the sticky ACTION_BATTERY_CHANGED broadcast), or null if unknown. */
    private fun batteryState(): BatteryState? =
        ContextCompat.registerReceiver(this, null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
            ?.let { BatteryState.from(it) }

    companion object {
        const val CHANNEL_SHARING = "sharing"
    }
}
