package io.github.whereareyou.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.whereareyou.R
import io.github.whereareyou.data.Fix
import io.github.whereareyou.data.LocationRepository
import io.github.whereareyou.data.Member
import kotlinx.coroutines.delay
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

private const val STALE_MS = 15 * 60_000L

/** Holds the osmdroid MapView and its markers (classic View, wrapped in Compose). */
private class MapHolder {
    var map: MapView? = null
    var myLocation: MyLocationNewOverlay? = null
    val markers = HashMap<String, Marker>()
    var centered = false

    fun create(context: Context): MapView = MapView(context).apply {
        setTileSource(TileSourceFactory.MAPNIK)
        setMultiTouchControls(true)
        zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        isTilesScaledToDpi = true
        controller.setZoom(4.0)
        controller.setCenter(GeoPoint(46.6, 2.4))
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        ) {
            val overlay = MyLocationNewOverlay(GpsMyLocationProvider(context), this)
            overlay.enableMyLocation()
            overlay.runOnFirstFix {
                post {
                    if (!centered) {
                        centered = true
                        controller.animateTo(overlay.myLocation, 16.0, 800L)
                    }
                }
            }
            overlays.add(overlay)
            myLocation = overlay
        }
        map = this
    }

    fun sync(members: List<Member>, now: Long) {
        val map = map ?: return
        val ids = members.map { it.id }.toSet()
        markers.keys.filter { it !in ids }.forEach { id -> map.overlays.remove(markers.remove(id)) }
        val sizePx = (40 * map.resources.displayMetrics.density).toInt()
        members.forEach { m ->
            val marker = markers.getOrPut(m.id) {
                Marker(map).also {
                    it.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    map.overlays.add(it)
                }
            }
            val stale = now - m.fix.time > STALE_MS
            marker.position = GeoPoint(m.fix.lat, m.fix.lon)
            marker.icon = BitmapDrawable(map.resources, avatarBitmap(m.id, m.name, sizePx, stale))
            marker.title = m.name
            marker.snippet = describe(map.resources, m, now)
        }
        map.invalidate()
        if (!centered && members.isNotEmpty()) {
            centered = true
            map.post { fitAll(members, null) }
        }
    }

    fun centerOn(point: GeoPoint?, zoom: Double = 17.0) {
        point ?: return
        map?.controller?.animateTo(point, zoom, 600L)
    }

    fun centerOnMe(ownFix: Fix?) =
        centerOn(myLocation?.myLocation ?: ownFix?.let { GeoPoint(it.lat, it.lon) })

    fun fitAll(members: List<Member>, ownFix: Fix?) {
        val map = map ?: return
        val points = members.map { GeoPoint(it.fix.lat, it.fix.lon) } +
            listOfNotNull(myLocation?.myLocation ?: ownFix?.let { GeoPoint(it.lat, it.lon) })
        when {
            points.isEmpty() -> return
            points.size == 1 -> centerOn(points[0], 16.0)
            else -> {
                val box = BoundingBox.fromGeoPoints(points)
                if (box.latitudeSpan < 0.002 && box.longitudeSpanWithDateLine < 0.002) {
                    centerOn(box.centerWithDateLine, 17.0)
                } else {
                    map.zoomToBoundingBox(box.increaseByScale(1.3f), true, 80)
                }
            }
        }
    }
}

private fun describe(res: android.content.res.Resources, m: Member, now: Long): String =
    listOf(
        formatAge(res, m.fix.time, now),
        res.getString(R.string.accuracy, m.fix.accuracy.toInt()),
        m.circles.joinToString(", "),
    ).joinToString(" · ")

@Composable
fun MapScreen(repo: LocationRepository, onToggleSharing: () -> Unit, onGoToCircles: () -> Unit) {
    val context = LocalContext.current
    val members by repo.members.collectAsStateWithLifecycle()
    val circles by repo.circles.collectAsStateWithLifecycle()
    val sharing by repo.sharingActive.collectAsStateWithLifecycle()
    val ownFix by repo.ownFix.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }

    val holder = remember { MapHolder() }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> holder.map?.onResume()
                Lifecycle.Event.ON_PAUSE -> holder.map?.onPause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            holder.myLocation?.disableMyLocation()
            holder.map?.onDetach()
        }
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { holder.create(it) },
                update = { holder.sync(members, now) },
            )
            Column(
                Modifier.align(Alignment.TopEnd).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SmallFloatingActionButton(onClick = { holder.centerOnMe(ownFix) }) {
                    Icon(painterResource(R.drawable.ic_my_location), stringResource(R.string.center_on_me))
                }
                SmallFloatingActionButton(onClick = { holder.fitAll(members, ownFix) }) {
                    Icon(painterResource(R.drawable.ic_fit_all), stringResource(R.string.fit_all))
                }
            }
            if (circles.isEmpty()) {
                Card(Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 12.dp, end = 72.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.no_circle_hint))
                        TextButton(onClick = onGoToCircles) { Text(stringResource(R.string.go_to_circles)) }
                    }
                }
            }
            ExtendedFloatingActionButton(
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                onClick = onToggleSharing,
                icon = { Icon(Icons.Filled.LocationOn, null) },
                text = { Text(stringResource(if (sharing) R.string.stop_sharing else R.string.start_sharing)) },
                containerColor = if (sharing) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
            )
        }
        if (members.isNotEmpty()) {
            HorizontalDivider()
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
                items(members, key = { it.id }) { m ->
                    val avatar = remember(m.id, m.name) { avatarBitmap(m.id, m.name, 96, false).asImageBitmap() }
                    ListItem(
                        modifier = Modifier.clickable { holder.centerOn(GeoPoint(m.fix.lat, m.fix.lon)) },
                        leadingContent = { Image(avatar, null, Modifier.size(40.dp)) },
                        headlineContent = { Text(m.name) },
                        supportingContent = { Text(describe(context.resources, m, now)) },
                    )
                }
            }
        }
    }
}
