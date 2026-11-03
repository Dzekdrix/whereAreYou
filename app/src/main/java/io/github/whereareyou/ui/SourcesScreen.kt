package io.github.whereareyou.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.whereareyou.R

/** A project, service or specification the app relies on. */
private class Source(val name: String, val description: Int, val license: String?, val url: String)

private val SOURCES = listOf(
    Source("OpenStreetMap", R.string.src_osm, "ODbL", "https://www.openstreetmap.org/copyright"),
    Source("OpenFreeMap", R.string.src_openfreemap, "MIT", "https://openfreemap.org"),
    Source("OpenMapTiles", R.string.src_openmaptiles, "BSD-3-Clause · CC-BY 4.0", "https://openmaptiles.org"),
    Source("MapLibre Native", R.string.src_maplibre, "BSD-2-Clause", "https://maplibre.org"),
    Source("Nostr", R.string.src_nostr, null, "https://nostr.com"),
    Source("BIP-340 (Schnorr / secp256k1)", R.string.src_bip340, null, "https://github.com/bitcoin/bips/blob/master/bip-0340.mediawiki"),
    Source("OkHttp", R.string.src_okhttp, "Apache-2.0", "https://square.github.io/okhttp/"),
    Source("ZXing", R.string.src_zxing, "Apache-2.0", "https://github.com/zxing/zxing"),
    Source("ZXing Android Embedded", R.string.src_zxing_embedded, "Apache-2.0", "https://github.com/journeyapps/zxing-android-embedded"),
    Source("Jetpack Compose · AndroidX", R.string.src_androidx, "Apache-2.0", "https://developer.android.com/jetpack/androidx"),
    Source("Kotlin · kotlinx.coroutines", R.string.src_kotlin, "Apache-2.0", "https://kotlinlang.org"),
    Source("Material Symbols", R.string.src_material_icons, "Apache-2.0", "https://fonts.google.com/icons"),
    Source("Don't kill my app!", R.string.src_dontkillmyapp, null, "https://dontkillmyapp.com"),
)

/** Lists what the app is built on, with a link to each project. */
@Composable
fun SourcesScreen(relays: List<String>) {
    val context = LocalContext.current
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(stringResource(R.string.sources_intro), style = MaterialTheme.typography.bodyMedium)
        }
        items(SOURCES, key = { it.name }) { s ->
            Card(
                onClick = {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.url)))
                    } catch (e: ActivityNotFoundException) {
                        // no browser: nothing to do
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(s.name, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(s.description), style = MaterialTheme.typography.bodyMedium)
                    if (s.name == "Nostr" && relays.isNotEmpty()) {
                        Text(
                            stringResource(R.string.src_nostr_relays, relays.joinToString(", ") { it.removePrefix("wss://") }),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    s.license?.let {
                        Text(
                            stringResource(R.string.src_license, it),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        s.url.removePrefix("https://").removeSuffix("/"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}
