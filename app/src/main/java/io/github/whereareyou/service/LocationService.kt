package io.github.whereareyou.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.whereareyou.R
import io.github.whereareyou.WhereApp
import io.github.whereareyou.data.Fix
import io.github.whereareyou.ui.MainActivity

/**
 * Foreground service that reads the position with the platform LocationManager
 * (no Google Play Services) and publishes it to the circles.
 */
class LocationService : Service(), LocationListener {
    private val app get() = application as WhereApp
    private lateinit var locationManager: LocationManager
    private var lastPublishAt = 0L
    private var lastPublished: Location? = null
    private var best: Location? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LocationManager::class.java)
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        )
        app.repo.acquire()
        app.repo.sharingActive.value = true
        startUpdates()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopSelf()
        // Not sticky: restarting a location service from the background is not allowed
        // without the "background location" permission (Android 10+ rules).
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        locationManager.removeUpdates(this)
        app.repo.publishStop()
        app.repo.sharingActive.value = false
        app.repo.release()
        super.onDestroy()
    }

    @SuppressLint("MissingPermission") // checked by the UI before starting the service
    private fun startUpdates() {
        val interval = app.store.intervalSeconds * 1000L
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && locationManager.hasProvider(LocationManager.FUSED_PROVIDER)) {
                add(LocationManager.FUSED_PROVIDER)
            }
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
        }.filter { locationManager.allProviders.contains(it) }

        try {
            providers.forEach { p ->
                locationManager.requestLocationUpdates(p, interval / 2, 0f, this, Looper.getMainLooper())
            }
            // Publish a recent cached position right away, if any.
            providers.mapNotNull { locationManager.getLastKnownLocation(it) }
                .filter { System.currentTimeMillis() - it.time < 2 * 60_000 }
                .maxByOrNull { it.time }?.let { onLocationChanged(it) }
        } catch (e: SecurityException) {
            stopSelf()
        }
    }

    override fun onLocationChanged(location: Location) {
        val now = System.currentTimeMillis()
        val current = best
        // Keep the most relevant fix: newer by a while, or more accurate.
        if (current == null || location.time - current.time > 30_000 || location.accuracy <= current.accuracy) {
            best = location
        }
        val candidate = best ?: return
        val interval = app.store.intervalSeconds * 1000L
        val moved = lastPublished?.distanceTo(candidate) ?: Float.MAX_VALUE
        if (now - lastPublishAt >= interval || (moved > 100f && now - lastPublishAt >= 10_000)) {
            lastPublishAt = now
            lastPublished = candidate
            app.repo.publish(Fix(candidate.latitude, candidate.longitude, candidate.accuracy, candidate.time))
            best = null
        }
    }

    // Must be implemented explicitly: they are abstract on Android 10.
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    private fun buildNotification() = NotificationCompat.Builder(this, WhereApp.CHANNEL_SHARING)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(getString(R.string.notification_title))
        .setContentText(getString(R.string.notification_text))
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        .addAction(
            0, getString(R.string.stop_sharing),
            PendingIntent.getService(
                this, 1, Intent(this, LocationService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        .build()

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "stop"

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, LocationService::class.java))

        fun stop(context: Context) = context.stopService(Intent(context, LocationService::class.java))
    }
}
