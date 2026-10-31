package io.github.whereareyou.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.PointF
import android.view.Gravity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.whereareyou.R
import io.github.whereareyou.data.Fix
import io.github.whereareyou.data.MarkerColor
import io.github.whereareyou.data.LocationRepository
import io.github.whereareyou.data.Member
import kotlinx.coroutines.delay
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maplibre.turf.TurfConstants
import org.maplibre.turf.TurfTransformation

/** Faded marker when no news for longer than announced (+ margin), or 25 min if unknown. */
private const val DEFAULT_STALE_MS = 25 * 60_000L

private fun Fix.isStale(now: Long) =
    now - time > if (maxGapMs > 0) maxGapMs * 3 / 2 + 2 * 60_000L else DEFAULT_STALE_MS

/** Free vector map styles from OpenFreeMap (OpenStreetMap data, no key, no account). */
private const val STYLE_LIGHT = "https://tiles.openfreemap.org/styles/liberty"
private const val STYLE_DARK = "https://tiles.openfreemap.org/styles/dark"

private const val SRC_MEMBERS = "members"
private const val SRC_ACCURACY = "accuracy"
private const val LAYER_MEMBERS = "members-layer"
private const val LAYER_ACCURACY = "accuracy-layer"

private fun Fix.latLng() = LatLng(lat, lon)

private fun hasLocationPermission(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

/** Holds the MapLibre MapView (classic View, wrapped in Compose) and draws members on it. */
private class MapHolder(private val context: Context, private val styleUrl: String) {
    var view: MapView? = null
    var map: MapLibreMap? = null
    private var style: Style? = null
    private var members: List<Member> = emptyList()
    private var selectedId: String? = null
    private var now = 0L
    private var fitted = false
    private var started = false
    private var resumed = false

    /** Incremented on every camera move, so the info bubble follows its marker. */
    val cameraTick = mutableIntStateOf(0)
    /** Map rotation in degrees (0 = north up). */
    val bearing = mutableFloatStateOf(0f)
    var onMemberTap: (String?) -> Unit = {}
    var onReady: () -> Unit = {}

    fun create(): MapView = MapView(context).also { v ->
        view = v
        v.onCreate(null)
        v.getMapAsync { m ->
            map = m
            m.cameraPosition = CameraPosition.Builder().target(LatLng(46.6, 2.4)).zoom(4.5).build()
            m.uiSettings.apply {
                isLogoEnabled = false
                isCompassEnabled = false // replaced by our own button, styled like the others
                attributionGravity = Gravity.BOTTOM or Gravity.START
            }
            applyAttributionMargins() // the insets may have arrived before the map was ready
            m.addOnCameraMoveListener {
                cameraTick.intValue++
                bearing.floatValue = m.cameraPosition.bearing.toFloat()
            }
            m.addOnMapClickListener { latLng ->
                val p = m.projection.toScreenLocation(latLng)
                val hit = m.queryRenderedFeatures(p, LAYER_MEMBERS).firstOrNull()
                onMemberTap(hit?.getStringProperty("id"))
                true
            }
            m.setStyle(Style.Builder().fromUri(styleUrl)) { s ->
                s.addSource(GeoJsonSource(SRC_ACCURACY))
                s.addLayer(
                    FillLayer(LAYER_ACCURACY, SRC_ACCURACY).withProperties(
                        PropertyFactory.fillColor(Expression.get("color")),
                        PropertyFactory.fillOpacity(0.18f),
                    )
                )
                s.addSource(GeoJsonSource(SRC_MEMBERS))
                s.addLayer(
                    SymbolLayer(LAYER_MEMBERS, SRC_MEMBERS).withProperties(
                        PropertyFactory.iconImage(Expression.get("icon")),
                        PropertyFactory.iconAllowOverlap(true),
                        PropertyFactory.iconIgnorePlacement(true),
                        // The selected member is drawn above the others.
                        PropertyFactory.symbolSortKey(Expression.get("z")),
                    )
                )
                style = s
                ensureMyLocation()
                render()
                onReady()
            }
        }
    }

    /** Keeps the "i" (credits) button above the transparent navigation bar. */
    private var bottomInsetPx = 0

    private fun applyAttributionMargins() {
        map?.uiSettings?.setAttributionMargins(dp(8), 0, 0, bottomInsetPx + dp(8))
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    fun update(members: List<Member>, selectedId: String?, now: Long, bottomInsetPx: Int) {
        this.bottomInsetPx = bottomInsetPx
        applyAttributionMargins()
        this.members = members
        this.selectedId = selectedId
        this.now = now
        ensureMyLocation()
        render()
    }

    /** Blue dot for our own position (needs the location permission, which may be granted later). */
    @SuppressLint("MissingPermission")
    private fun ensureMyLocation() {
        val m = map ?: return
        val s = style ?: return
        if (m.locationComponent.isLocationComponentActivated || !hasLocationPermission(context)) return
        m.locationComponent.apply {
            activateLocationComponent(LocationComponentActivationOptions.builder(context, s).build())
            isLocationComponentEnabled = true
            renderMode = RenderMode.NORMAL
            // Until we know where the others are, follow our own position.
            if (members.isEmpty() && !fitted) setCameraMode(CameraMode.TRACKING, 800L, 15.0, null, null, null)
        }
    }

    private fun render() {
        val s = style ?: return
        val size = dp(40)
        val features = members.map { m ->
            val stale = m.fix.isStale(now)
            val icon = "m-${m.id}-${m.name.hashCode()}-${m.emoji.hashCode()}-${m.color}-$stale"
            if (s.getImage(icon) == null) s.addImage(icon, avatarBitmap(m.id, m.name, size, stale, m.emoji, m.color))
            Feature.fromGeometry(Point.fromLngLat(m.fix.lon, m.fix.lat)).apply {
                addStringProperty("id", m.id)
                addStringProperty("icon", icon)
                addNumberProperty("z", if (m.id == selectedId) 1 else 0)
            }
        }
        s.getSourceAs<GeoJsonSource>(SRC_MEMBERS)?.setGeoJson(FeatureCollection.fromFeatures(features))

        val selected = members.firstOrNull { it.id == selectedId }
        val accuracy = selected?.takeIf { it.fix.accuracy > 0 }?.let { m ->
            val circle = TurfTransformation.circle(
                Point.fromLngLat(m.fix.lon, m.fix.lat), m.fix.accuracy.toDouble(), 64, TurfConstants.UNIT_METERS
            )
            Feature.fromGeometry(circle).apply {
                addStringProperty("color", MarkerColor.format(m.color ?: memberColor(m.id)))
            }
        }
        s.getSourceAs<GeoJsonSource>(SRC_ACCURACY)?.setGeoJson(FeatureCollection.fromFeatures(listOfNotNull(accuracy)))

        if (!fitted && members.isNotEmpty()) {
            fitted = true
            fitAll()
        }
    }

    fun screenPosition(m: Member): PointF? = map?.projection?.toScreenLocation(m.fix.latLng())

    private fun myPosition(): LatLng? = map?.locationComponent
        ?.takeIf { it.isLocationComponentActivated }
        ?.lastKnownLocation?.let { LatLng(it.latitude, it.longitude) }

    private fun stopTracking() {
        map?.locationComponent?.takeIf { it.isLocationComponentActivated }?.cameraMode = CameraMode.NONE
    }

    fun resetNorth() {
        map?.animateCamera(CameraUpdateFactory.bearingTo(0.0), 300)
    }

    fun centerOn(point: LatLng, zoom: Double = 16.0) {
        stopTracking()
        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(point, zoom), 600)
    }

    fun centerOnMe(ownFix: Fix?) {
        val lc = map?.locationComponent
        if (lc != null && lc.isLocationComponentActivated && lc.lastKnownLocation != null) {
            lc.setCameraMode(CameraMode.TRACKING, 600L, 16.0, null, null, null)
        } else {
            ownFix?.let { centerOn(it.latLng()) }
        }
    }

    fun fitAll(ownFix: Fix? = null) {
        val m = map ?: return
        val points = members.map { it.fix.latLng() } + listOfNotNull(myPosition() ?: ownFix?.latLng())
        when {
            points.isEmpty() -> return
            points.size == 1 -> centerOn(points[0])
            else -> {
                val bounds = LatLngBounds.Builder().includes(points).build()
                if (bounds.latitudeSpan < 0.002 && bounds.longitudeSpan < 0.002) {
                    centerOn(bounds.center, 17.0)
                } else {
                    stopTracking()
                    m.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, dp(72)), 600)
                }
            }
        }
    }

    fun onLifecycle(event: Lifecycle.Event) {
        val v = view ?: return
        when (event) {
            Lifecycle.Event.ON_START -> if (!started) { started = true; v.onStart() }
            Lifecycle.Event.ON_RESUME -> if (!resumed) { resumed = true; v.onResume() }
            Lifecycle.Event.ON_PAUSE -> if (resumed) { resumed = false; v.onPause() }
            Lifecycle.Event.ON_STOP -> if (started) { started = false; v.onStop() }
            else -> {}
        }
    }

    fun destroy() {
        onLifecycle(Lifecycle.Event.ON_PAUSE)
        onLifecycle(Lifecycle.Event.ON_STOP)
        view?.onDestroy()
        view = null
        map = null
        style = null
    }
}

fun describeAge(res: android.content.res.Resources, m: Member, now: Long): String =
    formatAge(res, m.fix.time, now) + " · " + res.getString(R.string.accuracy, m.fix.accuracy.toInt())

@Composable
fun MapScreen(
    repo: LocationRepository,
    onGoToCircles: () -> Unit,
    focusMemberId: String?,
    onFocusHandled: () -> Unit,
    onOpenMenu: () -> Unit,
) {
    val context = LocalContext.current
    val members by repo.members.collectAsStateWithLifecycle()
    val circles by repo.circles.collectAsStateWithLifecycle()
    val ownFix by repo.ownFix.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }

    val dark = isSystemInDarkTheme()
    val holder = remember(dark) { MapHolder(context, if (dark) STYLE_DARK else STYLE_LIGHT) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var ready by remember { mutableStateOf(false) }
    holder.onMemberTap = { selectedId = it }
    holder.onReady = { ready = true }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, holder) {
        val observer = LifecycleEventObserver { _, event -> holder.onLifecycle(event) }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            holder.destroy()
        }
    }

    // Opened from the circles screen: select and center on that member.
    LaunchedEffect(focusMemberId, ready, members) {
        val target = members.firstOrNull { it.id == focusMemberId } ?: return@LaunchedEffect
        if (!ready) return@LaunchedEffect
        selectedId = target.id
        holder.centerOn(target.fix.latLng())
        onFocusHandled()
    }

    // Forget the selection when that member disappears.
    if (selectedId != null && members.none { it.id == selectedId }) selectedId = null

    val bottomInset = WindowInsets.navigationBars.getBottom(LocalDensity.current)

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { holder.create() },
            update = { holder.update(members, selectedId, now, bottomInset) },
        )

        members.firstOrNull { it.id == selectedId }?.let { m ->
            holder.cameraTick.intValue // re-position when the camera moves
            holder.screenPosition(m)?.let { p -> MemberBubble(m, now, p) }
        }

        SmallFloatingActionButton(
            onClick = onOpenMenu,
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(12.dp),
        ) {
            Icon(Icons.Filled.Menu, stringResource(R.string.menu))
        }
        Column(
            Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SmallFloatingActionButton(onClick = { holder.centerOnMe(ownFix) }) {
                Icon(painterResource(R.drawable.ic_my_location), stringResource(R.string.center_on_me))
            }
            SmallFloatingActionButton(onClick = { holder.fitAll(ownFix) }) {
                Icon(painterResource(R.drawable.ic_fit_all), stringResource(R.string.fit_all))
            }
            // Only when the map is rotated: the needle points north, a tap puts north back up.
            val bearing = holder.bearing.floatValue
            if (bearing in 0.5f..359.5f) {
                SmallFloatingActionButton(onClick = { holder.resetNorth() }) {
                    Icon(
                        painterResource(R.drawable.ic_compass),
                        stringResource(R.string.reset_north),
                        modifier = Modifier.rotate(-bearing),
                        tint = Color.Unspecified,
                    )
                }
            }
        }
        if (circles.isEmpty()) {
            Card(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 12.dp, top = 64.dp, end = 72.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.no_circle_hint))
                    TextButton(onClick = onGoToCircles) { Text(stringResource(R.string.go_to_circles)) }
                }
            }
        }
    }
}

/** Info bubble drawn above a member's marker, at screen position [p] (pixels). */
@Composable
private fun MemberBubble(m: Member, now: Long, p: PointF) {
    val res = LocalContext.current.resources
    val color = MaterialTheme.colorScheme.surfaceContainerHigh
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
            layout(placeable.width, placeable.height) {
                // Bottom tip of the bubble just above the 40 dp marker.
                placeable.place(
                    (p.x - placeable.width / 2f).toInt(),
                    (p.y - placeable.height - 22.dp.toPx()).toInt()
                )
            }
        }
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = color),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            modifier = Modifier.widthIn(max = 260.dp),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(m.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(describeAge(res, m, now), style = MaterialTheme.typography.bodyMedium)
                Text(
                    m.circles.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Canvas(Modifier.size(width = 16.dp, height = 8.dp)) {
            val path = Path().apply {
                moveTo(0f, 0f)
                lineTo(size.width, 0f)
                lineTo(size.width / 2, size.height)
                close()
            }
            drawPath(path, color)
        }
    }
}
