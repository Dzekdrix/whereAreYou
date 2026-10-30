package io.github.whereareyou.ui

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import io.github.whereareyou.R

/** "3 min ago", "2 h ago"... */
fun formatAge(res: Resources, time: Long, now: Long): String {
    val minutes = ((now - time) / 60_000).coerceAtLeast(0)
    return when {
        minutes < 1 -> res.getString(R.string.age_now)
        minutes < 60 -> res.getString(R.string.age_minutes, minutes.toInt())
        minutes < 24 * 60 -> res.getString(R.string.age_hours, (minutes / 60).toInt())
        else -> res.getString(R.string.age_days, (minutes / (24 * 60)).toInt())
    }
}

/** A stable color per member, so everyone keeps the same color on the map. */
fun memberColor(id: String): Int {
    val hue = ((id.hashCode() and 0x7fffffff) % 360).toFloat()
    return android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.65f, 0.75f))
}

/** Round avatar with the member's initial, used as map marker. */
fun avatarBitmap(id: String, name: String, sizePx: Int, stale: Boolean): Bitmap {
    val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val r = sizePx / 2f
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = android.graphics.Color.WHITE
    canvas.drawCircle(r, r, r, paint)
    paint.color = memberColor(id)
    if (stale) paint.alpha = 110
    canvas.drawCircle(r, r, r * 0.86f, paint)
    paint.color = android.graphics.Color.WHITE
    paint.textSize = sizePx * 0.45f
    paint.textAlign = Paint.Align.CENTER
    paint.isFakeBoldText = true
    val initial = name.trim().take(1).uppercase().ifEmpty { "?" }
    canvas.drawText(initial, r, r - (paint.descent() + paint.ascent()) / 2, paint)
    return bmp
}

fun qrBitmap(text: String, size: Int = 720): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val pixels = IntArray(size * size) { i ->
        if (matrix.get(i % size, i / size)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}
