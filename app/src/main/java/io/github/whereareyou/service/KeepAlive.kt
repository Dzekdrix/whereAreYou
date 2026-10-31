package io.github.whereareyou.service

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.whereareyou.WhereApp

/**
 * "Background location" setting: keeps sharing alive when Android kills the app.
 *
 * Android only lets an app restart a location service from the background if the user
 * granted location "Allow all the time" AND exempted the app from battery optimization.
 * With both, sharing is restarted:
 *  - by the system itself (sticky service) after the process was killed,
 *  - after a reboot or an app update ([KeepAliveReceiver]),
 *  - by a watchdog alarm every ~15 min, as a safety net.
 * Sharing stopped by the user is never restarted (see Store.sharingWanted).
 */
object KeepAlive {
    private const val TAG = "WhereAreYou"
    private const val WATCHDOG_MS = 15 * 60_000L
    const val ACTION_WATCHDOG = "io.github.whereareyou.WATCHDOG"

    private fun app(context: Context) = context.applicationContext as WhereApp

    fun hasForegroundLocation(context: Context) =
        granted(context, Manifest.permission.ACCESS_FINE_LOCATION) || granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)

    fun hasBackgroundLocation(context: Context) = granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    fun isBatteryExempt(context: Context) =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    /** Setting on and everything Android requires is granted. */
    fun isActive(context: Context) =
        app(context).store.backgroundLocation && hasForegroundLocation(context) &&
            hasBackgroundLocation(context) && isBatteryExempt(context)

    /** Should the sharing service be running right now, without the user opening the app? */
    private fun shouldRun(context: Context) = isActive(context) && app(context).store.sharingWanted

    /** Restarts sharing if it should run but does not. */
    fun ensureRunning(context: Context) {
        if (!shouldRun(context) || app(context).repo.sharingActive.value) return
        Log.i(TAG, "restarting sharing in the background")
        try {
            ContextCompat.startForegroundService(context, Intent(context, LocationService::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "cannot restart sharing", e) // not allowed right now: the watchdog will retry
        }
    }

    /** Arms or cancels the watchdog according to the current state. */
    fun update(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val pending = PendingIntent.getBroadcast(
            context, 0,
            Intent(context, KeepAliveReceiver::class.java).setAction(ACTION_WATCHDOG),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        if (shouldRun(context)) {
            // Inexact, allowed in Doze: cheap, and precise enough for a safety net.
            alarms.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + WATCHDOG_MS, pending
            )
        } else {
            alarms.cancel(pending)
        }
    }

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

/** Phone restarted, app updated, or watchdog alarm: restart sharing if needed. */
class KeepAliveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, KeepAlive.ACTION_WATCHDOG -> {
                KeepAlive.ensureRunning(context)
                KeepAlive.update(context)
            }
        }
    }
}
