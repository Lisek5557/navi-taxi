package pl.taxinav

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
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
import org.maplibre.android.module.http.HttpRequestUtil
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        // Styl zapasowy, gdy serwer TaxiNav jest niedostępny (kafle OpenStreetMap, bez klucza).
        const val FALLBACK_STYLE = "https://tiles.openfreemap.org/styles/liberty"
        const val REROUTE_MIN_INTERVAL_MS = 8_000L
        const val TRAFFIC_REFRESH_MS = 180_000L
    }

    private lateinit var mapView: MapView
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private lateinit var voice: Voice

    private lateinit var searchPanel: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var results: ListView
    private lateinit var navBanner: LinearLayout
    private lateinit var arrow: TextView
    private lateinit var distToNext: TextView
    private lateinit var instruction: TextView
    private lateinit var bottomPanel: LinearLayout
    private lateinit var summary: TextView
    private lateinit var summaryDetail: TextView
    private lateinit var startButton: MaterialButton
    private lateinit var cancelButton: MaterialButton
    private lateinit var trafficButton: MaterialButton
    private lateinit var muteButton: MaterialButton
    private lateinit var themeButton: MaterialButton
    private var loadedStyleUrl: String? = null
    private var firstStyle = true
    private var lastThemeCheck = 0L
    private lateinit var progress: ProgressBar

    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var lastLocation: Location? = null
    private var destination: LatLon? = null
    private var routeResp: RouteResponse? = null
    private var engine: NavigationEngine? = null
    private var lastNavState: NavState? = null
    private var navigating = false
    private var rerouting = false
    private var lastRerouteAt = 0L
    private var refreshJob: Job? = null
    private var placeResults: List<Place> = emptyList()

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true || granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            startLocation()
        } else toast(getString(R.string.need_location))
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { onLocation(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        installMapHttpClient()
        setContentView(R.layout.activity_main)
        bindViews()
        voice = Voice(this)

        mapView = findViewById(R.id.mapView)
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { m ->
            map = m
            m.uiSettings.isCompassEnabled = true
            m.uiSettings.isAttributionEnabled = true
            m.cameraPosition = CameraPosition.Builder().target(LatLng(52.2297, 21.0122)).zoom(11.0).build()
            // Styl TaxiNav z serwera; gdy się nie wczyta — styl zapasowy.
            mapView.addOnDidFailLoadingMapListener {
                if (loadedStyleUrl != FALLBACK_STYLE) loadStyle(FALLBACK_STYLE)
            }
            loadStyle(desiredStyleUrl())
            m.addOnMapLongClickListener { p ->
                if (!navigating) previewTo(LatLon(p.latitude, p.longitude))
                true
            }
        }

        if (Prefs.server(this).isBlank()) {
            startActivity(Intent(this, SettingsActivity::class.java))
        } else toast(getString(R.string.long_press_hint))
    }

    /** MapLibre pobiera kafle korków z naszego serwera — dokładamy nagłówek z kluczem. */
    private fun installMapHttpClient() {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            val host = runCatching { Uri.parse(Prefs.server(this)).host }.getOrNull()
            if (host != null && req.url.host == host) {
                chain.proceed(req.newBuilder().header("X-Api-Key", Prefs.apiKey(this)).build())
            } else chain.proceed(req)
        }.build()
        HttpRequestUtil.setOkHttpClient(client)
    }

    private fun bindViews() {
        searchPanel = findViewById(R.id.searchPanel)
        searchInput = findViewById(R.id.searchInput)
        results = findViewById(R.id.results)
        navBanner = findViewById(R.id.navBanner)
        arrow = findViewById(R.id.arrow)
        distToNext = findViewById(R.id.distToNext)
        instruction = findViewById(R.id.instruction)
        bottomPanel = findViewById(R.id.bottomPanel)
        summary = findViewById(R.id.summary)
        summaryDetail = findViewById(R.id.summaryDetail)
        startButton = findViewById(R.id.startButton)
        cancelButton = findViewById(R.id.cancelButton)
        trafficButton = findViewById(R.id.trafficButton)
        muteButton = findViewById(R.id.muteButton)
        themeButton = findViewById(R.id.themeButton)
        updateThemeButton()
        themeButton.setOnClickListener {
            val next = when (Prefs.mapTheme(this)) { "auto" -> "day"; "day" -> "night"; else -> "auto" }
            Prefs.setMapTheme(this, next)
            updateThemeButton()
            toast(when (next) { "day" -> "Mapa: dzień"; "night" -> "Mapa: noc"; else -> "Mapa: automatycznie (noc 19–7)" })
            reloadStyleIfNeeded()
        }
        progress = findViewById(R.id.progress)

        findViewById<ImageButton>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { search(searchInput.text.toString()); true } else false
        }
        results.setOnItemClickListener { _, _, pos, _ ->
            val p = placeResults[pos]
            results.visibility = View.GONE
            hideKeyboard()
            searchInput.setText(p.label)
            previewTo(LatLon(p.lat, p.lon))
        }
        startButton.setOnClickListener { if (navigating) stopNavigation() else startNavigation() }
        cancelButton.setOnClickListener { clearRoute() }
        trafficButton.setOnClickListener {
            Prefs.setTrafficLayer(this, !Prefs.trafficLayer(this))
            style?.let { updateTrafficLayer(it) }
        }
        muteButton.setOnClickListener {
            voice.muted = !voice.muted
            if (voice.muted) voice.stop()
            muteButton.text = if (voice.muted) "🔇" else "🔊"
        }
        findViewById<MaterialButton>(R.id.recenterButton).setOnClickListener { recenter() }
    }

    // ---------- Mapa ----------

    private fun setupLayers(s: Style) {
        s.addSource(GeoJsonSource("alt-src"))
        s.addSource(GeoJsonSource("route-src"))
        // Trasa pod napisami (nazwy ulic i numery domów zostają widoczne).
        val below = s.layers.firstOrNull { it is SymbolLayer }?.id
        val add = { layer: Layer -> if (below != null) s.addLayerBelow(layer, below) else s.addLayer(layer) }
        add(LineLayer("alt-line", "alt-src").withProperties(
            PropertyFactory.lineColor("#8A93A3"),
            PropertyFactory.lineWidth(6f),
            PropertyFactory.lineOpacity(0.7f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ))
        add(LineLayer("route-casing", "route-src").withProperties(
            PropertyFactory.lineColor("#FFFFFF"),
            PropertyFactory.lineWidth(11f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ))
        add(LineLayer("route-line", "route-src").withProperties(
            PropertyFactory.lineColor(
                Expression.match(
                    Expression.get("level"), Expression.color(color(R.color.route_unknown)),
                    Expression.stop(0, Expression.color(color(R.color.traffic_free))),
                    Expression.stop(1, Expression.color(color(R.color.traffic_slow))),
                    Expression.stop(2, Expression.color(color(R.color.traffic_jam))),
                    Expression.stop(3, Expression.color(color(R.color.traffic_stop))),
                )
            ),
            PropertyFactory.lineWidth(7f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ))
        updateTrafficLayer(s)
    }

    private fun updateTrafficLayer(s: Style) {
        s.getLayer("traffic-layer")?.let { s.removeLayer(it) }
        s.getSource("traffic-src")?.let { s.removeSource(it) }
        val server = Prefs.server(this)
        val on = Prefs.trafficLayer(this) && server.isNotBlank()
        trafficButton.alpha = if (on) 1f else 0.45f
        if (!on) return
        val tiles = TileSet("2.1.0", "$server/traffic/{z}/{x}/{y}.png")
        tiles.minZoom = 6f
        tiles.maxZoom = 18f
        s.addSource(RasterSource("traffic-src", tiles, 256))
        s.addLayerBelow(RasterLayer("traffic-layer", "traffic-src").withProperties(PropertyFactory.rasterOpacity(0.75f)), "alt-line")
    }

    private fun color(id: Int) = ContextCompat.getColor(this, id)

    /** Trasa jako odcinki pokolorowane wg korków. */
    private fun drawRoute(resp: RouteResponse?, fromIndex: Int = 0) {
        val s = style ?: return
        val routeSrc = s.getSourceAs<GeoJsonSource>("route-src")
        val altSrc = s.getSourceAs<GeoJsonSource>("alt-src")
        if (resp == null) {
            routeSrc?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            altSrc?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            return
        }
        val r = resp.chosen
        val features = ArrayList<Feature>()
        val ranges = if (r.traffic.isEmpty()) listOf(TrafficRange(0, r.points.size - 1, -1)) else r.traffic
        for (tr in ranges) {
            val from = maxOf(tr.from, fromIndex)
            if (tr.to - from < 1) continue
            val line = LineString.fromLngLats(r.points.subList(from, tr.to + 1).map { Point.fromLngLat(it.lon, it.lat) })
            features += Feature.fromGeometry(line).also { it.addNumberProperty("level", tr.level) }
        }
        routeSrc?.setGeoJson(FeatureCollection.fromFeatures(features))
        val alts = if (navigating) emptyList() else resp.alternatives.map { a ->
            Feature.fromGeometry(LineString.fromLngLats(a.points.map { Point.fromLngLat(it.lon, it.lat) }))
        }
        altSrc?.setGeoJson(FeatureCollection.fromFeatures(alts))
    }

    private fun fitRoute(r: Route) {
        val m = map ?: return
        if (r.points.size < 2) return
        val b = LatLngBounds.Builder()
        r.points.forEach { b.include(LatLng(it.lat, it.lon)) }
        m.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 80, 260, 80, 320), 800)
    }

    // ---------- Lokalizacja ----------

    @SuppressLint("MissingPermission")
    private fun enableLocationComponent(s: Style) {
        if (!hasLocationPermission()) return
        val lc = map?.locationComponent ?: return
        if (!lc.isLocationComponentActivated) {
            lc.activateLocationComponent(
                LocationComponentActivationOptions.builder(this, s).useDefaultLocationEngine(false).build()
            )
        }
        lc.isLocationComponentEnabled = true
        lc.renderMode = RenderMode.COMPASS
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun ensureLocationPermission() {
        if (hasLocationPermission()) startLocation()
        else permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        if (!hasLocationPermission()) return
        style?.let { enableLocationComponent(it) }
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .build()
        fused.requestLocationUpdates(req, locationCallback, Looper.getMainLooper())
        fused.lastLocation.addOnSuccessListener { loc ->
            if (loc != null && lastLocation == null) {
                onLocation(loc)
                map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(loc.latitude, loc.longitude), 14.0), 600)
            }
        }
    }

    private fun onLocation(loc: Location) {
        lastLocation = loc
        if (SystemClock.elapsedRealtime() - lastThemeCheck > 60_000) {
            lastThemeCheck = SystemClock.elapsedRealtime()
            reloadStyleIfNeeded() // automatyczne przełączenie dzień/noc
        }
        map?.locationComponent?.takeIf { it.isLocationComponentActivated }?.forceLocationUpdate(loc)
        val e = engine ?: return
        if (!navigating) return
        val st = e.update(LatLon(loc.latitude, loc.longitude), loc.speed.toDouble(), loc.accuracy.toDouble())
        lastNavState = st
        renderNavState(st)
        if (st.arrived) {
            stopNavigation(arrived = true)
            return
        }
        if (st.offRoute && !rerouting && SystemClock.elapsedRealtime() - lastRerouteAt > REROUTE_MIN_INTERVAL_MS) {
            reroute(silent = false)
        }
    }

    // ---------- Wyszukiwanie i trasy ----------

    private fun search(q: String) {
        if (q.isBlank()) return
        hideKeyboard()
        lifecycleScope.launch {
            progress.visibility = View.VISIBLE
            try {
                val near = lastLocation?.let { LatLon(it.latitude, it.longitude) }
                placeResults = Api.from(this@MainActivity).geocode(q, near)
                if (placeResults.isEmpty()) toast("Nie znaleziono: $q")
                results.adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_1, placeResults)
                results.layoutParams = results.layoutParams.apply {
                    height = if (placeResults.size > 5) (resources.displayMetrics.density * 320).toInt()
                    else LinearLayout.LayoutParams.WRAP_CONTENT
                }
                results.visibility = if (placeResults.isEmpty()) View.GONE else View.VISIBLE
            } catch (e: ApiException) {
                toast(e.message ?: "Błąd")
            } finally {
                progress.visibility = View.GONE
            }
        }
    }

    private fun previewTo(dest: LatLon) {
        val loc = lastLocation ?: run { toast("Czekam na GPS…"); return }
        destination = dest
        lifecycleScope.launch {
            progress.visibility = View.VISIBLE
            try {
                val resp = Api.from(this@MainActivity).route(LatLon(loc.latitude, loc.longitude), dest, loc.bearingOrNull())
                routeResp = resp
                drawRoute(resp)
                fitRoute(resp.chosen)
                showPreview(resp)
            } catch (e: ApiException) {
                toast(e.message ?: "Błąd")
            } finally {
                progress.visibility = View.GONE
            }
        }
    }

    private fun showPreview(resp: RouteResponse) {
        val r = resp.chosen
        bottomPanel.visibility = View.VISIBLE
        cancelButton.visibility = View.VISIBLE
        startButton.text = getString(R.string.start)
        summary.text = "${minutes(r.trafficTimeS)} · ${Speech.short(r.distanceM.toDouble())}"
        val delay = r.trafficTimeS - r.baseTimeS
        val why = when {
            "shortest" in r.kinds && "fastest" in r.kinds -> "najkrótsza i najszybsza"
            "shortest" in r.kinds -> "najkrótsza rozsądna"
            "fastest" in r.kinds -> "najszybsza"
            else -> "alternatywna"
        }
        summaryDetail.text = buildString {
            append("Trasa: $why")
            if (r.hasTraffic) append(if (delay >= 60) " · korki +${delay / 60} min" else " · bez korków")
            else append(" · brak danych o korkach")
            if (resp.alternatives.isNotEmpty()) append(" · ${resp.alternatives.size} inne (szare)")
        }
    }

    private fun clearRoute() {
        routeResp = null; destination = null; engine = null
        drawRoute(null)
        bottomPanel.visibility = View.GONE
        searchInput.setText("")
    }

    @SuppressLint("MissingPermission")
    private fun startNavigation() {
        val resp = routeResp ?: return
        navigating = true
        engine = NavigationEngine(resp.chosen, voice::say)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        searchPanel.visibility = View.GONE
        results.visibility = View.GONE
        navBanner.visibility = View.VISIBLE
        cancelButton.visibility = View.GONE
        startButton.text = getString(R.string.stop)
        drawRoute(resp)
        followCamera()
        lastLocation?.let { onLocation(it) }
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            while (isActive) {
                delay(TRAFFIC_REFRESH_MS)
                if (navigating && !rerouting) reroute(silent = true)
            }
        }
    }

    private fun stopNavigation(arrived: Boolean = false) {
        navigating = false
        refreshJob?.cancel()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        navBanner.visibility = View.GONE
        searchPanel.visibility = View.VISIBLE
        map?.locationComponent?.takeIf { it.isLocationComponentActivated }?.apply {
            cameraMode = CameraMode.NONE
            renderMode = RenderMode.COMPASS
        }
        map?.animateCamera(CameraUpdateFactory.tiltTo(0.0), 500)
        if (arrived) {
            toast("Dotarłeś do celu")
            clearRoute()
        } else {
            voice.stop()
            routeResp?.let { showPreview(it); drawRoute(it) }
        }
    }

    /**
     * silent=false: zjechałeś z trasy -> nowa trasa + komunikat.
     * silent=true: odświeżenie korków; zmiana trasy tylko jeśli nowa jest wyraźnie szybsza.
     */
    private fun reroute(silent: Boolean) {
        val loc = lastLocation ?: return
        val dest = destination ?: return
        rerouting = true
        lastRerouteAt = SystemClock.elapsedRealtime()
        if (!silent) voice.say("Przeliczam trasę.")
        lifecycleScope.launch {
            try {
                val resp = Api.from(this@MainActivity).route(LatLon(loc.latitude, loc.longitude), dest, loc.bearingOrNull())
                if (!navigating) return@launch
                val currentRemainingS = lastNavState?.remainingS ?: Double.MAX_VALUE
                val saves = currentRemainingS - resp.chosen.trafficTimeS
                if (silent && saves >= 120) voice.say("Znalazłem szybszą trasę, oszczędzasz ${(saves / 60).toInt()} minut.")
                routeResp = resp
                engine = NavigationEngine(resp.chosen, voice::say, announceStart = !silent)
                lastNavState = null
                drawRoute(resp)
            } catch (e: ApiException) {
                if (!silent) toast(e.message ?: "Błąd przeliczania")
            } finally {
                rerouting = false
            }
        }
    }

    private fun renderNavState(st: NavState) {
        val next = st.nextManeuver
        arrow.text = Maneuvers.arrow(next?.type ?: 8)
        distToNext.text = Speech.short(st.distanceToNextM)
        instruction.text = next?.instruction ?: ""
        val eta = SimpleDateFormat("HH:mm", Locale.forLanguageTag("pl-PL")).format(Date(System.currentTimeMillis() + (st.remainingS * 1000).toLong()))
        summary.text = "${minutes(st.remainingS.toInt())} · ${Speech.short(st.remainingM)}"
        summaryDetail.text = "Przyjazd $eta" + if (st.offRoute) " · poza trasą" else ""
    }

    @SuppressLint("MissingPermission")
    private fun followCamera() {
        val lc = map?.locationComponent ?: return
        if (!lc.isLocationComponentActivated) return
        lc.cameraMode = CameraMode.TRACKING_GPS
        lc.renderMode = RenderMode.GPS
        lc.zoomWhileTracking(17.0)
        lc.tiltWhileTracking(50.0)
    }

    private fun recenter() {
        if (navigating) { followCamera(); return }
        val loc = lastLocation ?: return
        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(loc.latitude, loc.longitude), 15.0), 500)
    }

    // ---------- Pomocnicze ----------

    private fun Location.bearingOrNull(): Float? = if (hasBearing() && speed > 2f) bearing else null

    private fun minutes(s: Int): String {
        val m = (s + 30) / 60
        return if (m < 60) "$m min" else "${m / 60} h ${m % 60} min"
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(searchInput.windowToken, 0)
        searchInput.clearFocus()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    // ---------- Cykl życia MapView ----------

    override fun onStart() { super.onStart(); mapView.onStart() }
    override fun onResume() {
        super.onResume(); mapView.onResume()
        style?.let { updateTrafficLayer(it) } // po zmianie ustawień
        reloadStyleIfNeeded()
    }

    // ---------- Styl mapy (dzień / noc) ----------

    private fun currentTheme(): String = when (val t = Prefs.mapTheme(this)) {
        "day", "night" -> t
        else -> {
            val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            if (h >= 19 || h < 7) "night" else "day"
        }
    }

    private fun desiredStyleUrl(): String {
        val server = Prefs.server(this)
        return if (server.isBlank()) FALLBACK_STYLE else "$server/style/${currentTheme()}.json"
    }

    private fun updateThemeButton() {
        themeButton.text = when (Prefs.mapTheme(this)) { "day" -> "☀"; "night" -> "☾"; else -> "◐" }
    }

    private fun reloadStyleIfNeeded() {
        val want = desiredStyleUrl()
        if (map != null && loadedStyleUrl != null && want != loadedStyleUrl) loadStyle(want)
    }

    private fun loadStyle(url: String) {
        val m = map ?: return
        loadedStyleUrl = url
        m.setStyle(Style.Builder().fromUri(url)) { s ->
            style = s
            setupLayers(s)
            enableLocationComponent(s)
            if (firstStyle) { firstStyle = false; ensureLocationPermission() }
            routeResp?.let { drawRoute(it) }
        }
    }
    override fun onPause() { super.onPause(); mapView.onPause() }
    override fun onStop() { super.onStop(); mapView.onStop() }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); mapView.onSaveInstanceState(outState) }
    override fun onDestroy() {
        fused.removeLocationUpdates(locationCallback)
        voice.shutdown()
        mapView.onDestroy()
        super.onDestroy()
    }
}
