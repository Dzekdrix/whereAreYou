package io.github.whereareyou.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.whereareyou.R
import io.github.whereareyou.data.Emoji
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A selection of fun markers; any other emoji can be typed with the keyboard. */
private val EMOJIS = listOf(
    "😀", "😎", "🤓", "🥳", "😇", "🤠", "👻", "🤖", "👽", "💀", "🎃", "😺",
    "🐶", "🐱", "🦊", "🐻", "🐼", "🐨", "🐯", "🦁", "🐮", "🐷", "🐸", "🐵",
    "🐔", "🐧", "🦉", "🦄", "🐝", "🦋", "🐢", "🐙", "🦖", "🐳", "🐬", "🦈",
    "🌵", "🌻", "🍀", "🍄", "🌈", "⭐", "🔥", "❄️", "🍕", "🍔", "🍦", "☕",
    "⚽", "🏀", "🎾", "🚴", "🏃", "🧗", "🏄", "🎸", "🎮", "🎨", "📚", "🎒",
    "🚗", "🚲", "🛵", "🏍️", "🚀", "✈️", "⛵", "🚂", "🏠", "🏕️", "⛰️", "🏖️",
    "❤️", "💙", "💚", "💛", "💜", "🧡", "🖤", "✨", "👑", "💎", "🎯", "🧭",
)

/** Ready-made colors (all readable with a white letter on top). */
private val PALETTE = listOf(
    0xFFE53935, 0xFFD81B60, 0xFF8E24AA, 0xFF5E35B1, 0xFF3949AB,
    0xFF1E88E5, 0xFF039BE5, 0xFF00ACC1, 0xFF00897B, 0xFF43A047,
    0xFF7CB342, 0xFFC0CA33, 0xFFFDD835, 0xFFFFB300, 0xFFFB8C00,
    0xFFF4511E, 0xFF6D4C41, 0xFF546E7A, 0xFF757575, 0xFF212121,
).map { it.toInt() }

/** Same saturation/brightness as the automatic colors, so custom ones look consistent. */
private fun hueColor(hue: Float) = android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.7f, 0.85f))

/**
 * "My marker on the map": emoji and background color, with a live preview.
 * [color] null = automatic color derived from the member id.
 */
@Composable
fun MarkerSetting(
    memberId: String,
    name: String,
    emoji: String,
    color: Int?,
    onEmoji: (String) -> Unit,
    onColor: (Int?) -> Unit,
) {
    var pickingEmoji by remember { mutableStateOf(false) }
    var pickingColor by remember { mutableStateOf(false) }
    val preview = remember(memberId, name, emoji, color) {
        avatarBitmap(memberId, name, 120, false, emoji, color).asImageBitmap()
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(preview, null, Modifier.size(48.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(stringResource(R.string.marker_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(if (emoji.isEmpty()) R.string.marker_initial else R.string.marker_emoji),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = { pickingEmoji = true }) { Text(stringResource(R.string.marker_choose_emoji)) }
        TextButton(onClick = { pickingColor = true }) { Text(stringResource(R.string.marker_choose_color)) }
    }

    if (pickingEmoji) {
        EmojiDialog(
            onDismiss = { pickingEmoji = false },
            onChoose = {
                onEmoji(Emoji.sanitize(it))
                pickingEmoji = false
            },
        )
    }
    if (pickingColor) {
        ColorDialog(
            current = color ?: memberColor(memberId),
            auto = memberColor(memberId),
            onDismiss = { pickingColor = false },
            onChoose = {
                onColor(it)
                pickingColor = false
            },
        )
    }
}

/** Draws each emoji once into a bitmap: scrolling then only moves images (smooth), no text layout. */
private fun renderEmoji(emoji: String, sizePx: Int): ImageBitmap {
    val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sizePx * 0.8f
        textAlign = Paint.Align.CENTER
    }
    Canvas(bmp).drawText(emoji, sizePx / 2f, sizePx / 2f - (paint.descent() + paint.ascent()) / 2, paint)
    return bmp.asImageBitmap()
}

@Composable
private fun EmojiDialog(onDismiss: () -> Unit, onChoose: (String) -> Unit) {
    var typed by remember { mutableStateOf("") }
    val sizePx = with(LocalDensity.current) { 34.dp.roundToPx() }
    val images by produceState<List<ImageBitmap>?>(null, sizePx) {
        value = withContext(Dispatchers.Default) { EMOJIS.map { renderEmoji(it, sizePx) } }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.marker_emoji_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(6),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                ) {
                    // "None": back to the initial of the name.
                    item(span = { GridItemSpan(maxLineSpan) }, key = "none") {
                        Text(
                            stringResource(R.string.marker_none),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onChoose("") }
                                .padding(8.dp),
                        )
                    }
                    images?.let { list ->
                        itemsIndexed(list, key = { i, _ -> EMOJIS[i] }) { i, img ->
                            Box(
                                Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).clickable { onChoose(EMOJIS[i]) },
                                contentAlignment = Alignment.Center,
                            ) { Image(img, EMOJIS[i], Modifier.size(34.dp)) }
                        }
                    }
                }
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = Emoji.sanitize(it) },
                    label = { Text(stringResource(R.string.marker_type)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = typed.isNotEmpty(), onClick = { onChoose(typed) }) { Text(stringResource(R.string.marker_use)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * Android has no built-in color picker dialog: palette + hue slider, in Material 3 style.
 * [onChoose] null = automatic color.
 */
@Composable
private fun ColorDialog(current: Int, auto: Int, onDismiss: () -> Unit, onChoose: (Int?) -> Unit) {
    var hue by remember {
        mutableFloatStateOf(FloatArray(3).also { android.graphics.Color.colorToHSV(current, it) }[0])
    }
    val custom = hueColor(hue)
    val rainbow = remember { Brush.horizontalGradient((0..6).map { Color(hueColor(it * 60f)) }) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.marker_color_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onChoose(null) }.padding(4.dp),
                ) {
                    Swatch(auto, selected = false, onClick = { onChoose(null) })
                    Text(
                        stringResource(R.string.marker_color_auto),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
                PALETTE.chunked(5).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        row.forEach { c -> Swatch(c, selected = c == current, onClick = { onChoose(c) }) }
                    }
                }
                Text(stringResource(R.string.marker_color_custom), style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Box(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape).background(rainbow))
                        Slider(value = hue, onValueChange = { hue = it }, valueRange = 0f..360f)
                    }
                    Swatch(custom, selected = false, onClick = { onChoose(custom) }, modifier = Modifier.padding(start = 12.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = { onChoose(custom) }) { Text(stringResource(R.string.marker_use_custom)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun Swatch(color: Int, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val ring = if (selected) BorderStroke(3.dp, MaterialTheme.colorScheme.onSurface) else BorderStroke(1.dp, Color(0x33000000))
    Canvas(
        modifier.size(40.dp).clip(CircleShape).border(ring, CircleShape).clickable(onClick = onClick)
    ) { drawCircle(Color(color)) }
}
