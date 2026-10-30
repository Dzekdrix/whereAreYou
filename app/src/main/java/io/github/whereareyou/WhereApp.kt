package io.github.whereareyou

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import io.github.whereareyou.data.LocationRepository
import io.github.whereareyou.data.Store
import org.maplibre.android.MapLibre

class WhereApp : Application() {
    lateinit var store: Store
        private set
    lateinit var repo: LocationRepository
        private set

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        repo = LocationRepository(store)

        MapLibre.getInstance(this)

        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_SHARING, getString(R.string.channel_sharing), NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        const val CHANNEL_SHARING = "sharing"
    }
}
