package io.github.whereareyou.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.location.Location
import androidx.core.location.LocationListenerCompat
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import io.github.whereareyou.R
import io.github.whereareyou.WhereApp
import io.github.whereareyou.data.BatteryState
import io.github.whereareyou.data.Fix
import io.github.whereareyou.data.MotionPolicy
import io.github.whereareyou.ui.MainActivity

/**
 * Foreground service that reads the position with the platform LocationManager
 * (no Google Play Services) and publishes it to the circles.
 * To save battery, the GPS is only used while moving (see [MotionPolicy]).
 */
class LocationService : Service(), LocationListenerCompat {
    private val app get() = application as WhereApp
    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager
    private lateinit var policy: MotionPolicy
    private var appliedMode: MotionPolicy.Mode? = null
    private var running = false
    private val handler = Handler(Looper.getMainLooper())

    /** Hardware "significant motion" sensor: wakes us up for almost no power when we start moving. */
    private val motionSensor: Sensor? by lazy { sensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION) }
    private val motionListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            Log.i(TAG, "motion detected")
            policy.onMotion(System.currentTimeMillis())
            applyMode()
        }
    }

    /** Plugged in or not, as last seen (null = not known yet). */
    private var charging: Boolean? = null
    /** Sends our position again when the phone is plugged in or unplugged, so the circles see it right away. */
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val now = BatteryState.from(intent)?.charging ?: return
            if (charging != null && now != charging && app.store.shareBattery) app.repo.republish()
            charging = now
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            policy.onTick(System.currentTimeMillis())?.let { publish(it) }
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LocationManager::class.java)
        sensorManager = getSystemService(SensorManager::class.java)
        policy = MotionPolicy(
            movingSetting = { app.store.movingIntervalSeconds * 1000L },
            stillSetting = { app.store.stillIntervalSeconds * 1000L },
        )
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } catch (e: Exception) {
            // Restarted from the background without the required permissions: give up quietly.
            Log.w(TAG, "cannot start in foreground", e)
            stopSelf()
            return
        }
        running = true
        app.repo.acquire()
        app.repo.sharingActive.value = true
        applyMode()
        publishRecentCachedFix()
        handler.postDelayed(ticker, TICK_MS)
        ContextCompat.registerReceiver(this, batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // "Stop" button of the notification: a user decision, never restarted.
                app.store.sharingWanted = false
                KeepAlive.update(this)
                stopSelf()
            }
            ACTION_REFRESH -> {
                // Settings changed: apply them now.
                appliedMode = null
                applyMode()
            }
        }
        // Sticky (Android restarts us after killing the app) only with the "background location"
        // setting: without it, Android does not allow restarting a location service from the background.
        return if (KeepAlive.isActive(this)) START_STICKY else START_NOT_STICKY
    }

    override fun onDestroy() {
        if (!running) {
            super.onDestroy()
            return
        }
        handler.removeCallbacks(ticker)
        unregisterReceiver(batteryReceiver)
        locationManager.removeUpdates(this)
        motionSensor?.let { sensorManager.cancelTriggerSensor(motionListener, it) }
        // Only tell the circles we stopped if the user did; otherwise we will be back.
        if (!app.store.sharingWanted) app.repo.publishStop()
        app.repo.sharingActive.value = false
        app.repo.release()
        super.onDestroy()
    }

    /** (Re)configures the location sources for the current mode. */
    @SuppressLint("MissingPermission") // checked by the UI before starting the service
    private fun applyMode() {
        val mode = policy.mode
        if (mode == appliedMode) return
        appliedMode = mode
        Log.i(TAG, "mode: $mode")
        locationManager.removeUpdates(this)
        val sensor = motionSensor
        try {
            when (mode) {
                MotionPolicy.Mode.MOVING -> {
                    sensor?.let { sensorManager.cancelTriggerSensor(motionListener, it) }
                    val interval = policy.sampleMs
                    val precise = if (has(LocationManager.FUSED_PROVIDER)) {
                        listOf(LocationManager.FUSED_PROVIDER)
                    } else {
                        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                    }
                    precise.filter { has(it) }.forEach { request(it, interval, LocationRequestCompat.QUALITY_HIGH_ACCURACY) }
                }
                MotionPolicy.Mode.STATIONARY -> {
                    // GPS off. Free fixes from other apps + cheap Wi-Fi/cell fixes now and then.
                    if (has(LocationManager.PASSIVE_PROVIDER)) {
                        request(LocationManager.PASSIVE_PROVIDER, 60_000L, LocationRequestCompat.QUALITY_LOW_POWER)
                    }
                    val armed = sensor != null && sensorManager.requestTriggerSensor(motionListener, sensor)
                    // The motion sensor does the job; cheap Wi-Fi/cell checks are only a backup.
                    val poll = if (armed) STATIONARY_BACKUP_POLL_MS else STATIONARY_POLL_MS
                    when {
                        has(LocationManager.NETWORK_PROVIDER) ->
                            request(LocationManager.NETWORK_PROVIDER, poll, LocationRequestCompat.QUALITY_LOW_POWER)
                        has(LocationManager.FUSED_PROVIDER) ->
                            request(LocationManager.FUSED_PROVIDER, poll, LocationRequestCompat.QUALITY_BALANCED_POWER_ACCURACY)
                        else -> request(LocationManager.GPS_PROVIDER, poll, LocationRequestCompat.QUALITY_HIGH_ACCURACY)
                    }
                }
            }
        } catch (e: SecurityException) {
            stopSelf()
        }
    }

    private fun has(provider: String) =
        (provider != LocationManager.FUSED_PROVIDER || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) &&
            locationManager.allProviders.contains(provider)

    @SuppressLint("MissingPermission")
    private fun request(provider: String, intervalMs: Long, quality: Int) {
        val req = LocationRequestCompat.Builder(intervalMs).setQuality(quality).build()
        LocationManagerCompat.requestLocationUpdates(locationManager, provider, req, this, Looper.getMainLooper())
    }

    /** Publishes a recent cached position right away, if any. */
    @SuppressLint("MissingPermission")
    private fun publishRecentCachedFix() {
        try {
            locationManager.allProviders.mapNotNull { locationManager.getLastKnownLocation(it) }
                .filter { System.currentTimeMillis() - it.time < 2 * 60_000 }
                .maxByOrNull { it.time }?.let { onLocationChanged(it) }
        } catch (_: SecurityException) {
        }
    }

    override fun onLocationChanged(location: Location) {
        val fix = Fix(location.latitude, location.longitude, location.accuracy, location.time)
        policy.onFix(fix, System.currentTimeMillis())?.let { publish(it) }
        applyMode()
    }

    private fun publish(fix: Fix) = app.repo.publish(fix.copy(maxGapMs = policy.maxGapMs))

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
        private const val TAG = "WhereAreYou"
        private const val NOTIFICATION_ID = 1
        private const val TICK_MS = 60_000L
        /** Wi-Fi/cell check while stationary, without / with motion sensor. */
        private const val STATIONARY_POLL_MS = 5 * 60_000L
        private const val STATIONARY_BACKUP_POLL_MS = 15 * 60_000L
        private const val ACTION_STOP = "stop"
        private const val ACTION_REFRESH = "refresh"

        fun start(context: Context) {
            (context.applicationContext as WhereApp).store.sharingWanted = true
            ContextCompat.startForegroundService(context, Intent(context, LocationService::class.java))
            KeepAlive.update(context)
        }

        fun stop(context: Context) {
            (context.applicationContext as WhereApp).store.sharingWanted = false
            context.stopService(Intent(context, LocationService::class.java))
            KeepAlive.update(context)
        }

        /** Applies changed settings to a running service (no-op if not sharing). */
        fun refresh(context: Context, running: Boolean) {
            if (running) context.startService(Intent(context, LocationService::class.java).setAction(ACTION_REFRESH))
        }
    }
}
