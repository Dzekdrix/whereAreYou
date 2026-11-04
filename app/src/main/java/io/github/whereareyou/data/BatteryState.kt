package io.github.whereareyou.data

import android.content.Intent
import android.os.BatteryManager

/** Battery level in % and whether the phone is plugged in (charging, or full on the charger). */
data class BatteryState(val level: Int, val charging: Boolean = false) {
    companion object {
        /** Reads an ACTION_BATTERY_CHANGED intent; null if the level is unknown. */
        fun from(intent: Intent): BatteryState? {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level < 0 || scale <= 0) return null
            val percent = (level * 100 / scale).takeIf { it in 0..100 } ?: return null
            return BatteryState(percent, intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0)
        }
    }
}
