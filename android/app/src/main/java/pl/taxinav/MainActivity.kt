package pl.taxinav

import android.Manifest
import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.location.Location
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.Rational
import android.view.Gravity
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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
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
import org.maplibre.android.style.layers.FillLayer
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
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    companion object {
        // Styl zapasowy, gdy serwer TaxiNav jest niedostępny (kafle OpenStreetMap, bez klucza).
        const val FALLBACK_STYLE = "https://tiles.openfreemap.org/styles/liberty"
        const val REROUTE_MIN_INTERVAL_MS = 8_000L
        const val TRAFFIC_REFRESH_MS = 180_000L
        val REPORT_ICONS = mapOf(
            "police" to Pair("🚓", 0xFF2F7DE1.toInt()),
            "accident" to Pair("💥", 0xFFE5482F.toInt()),
            "hazard" to Pair("⚠", 0xFFF5A623.toInt()),
            "closed" to Pair("⛔", 0xFF8E1B1B.toInt()),
        )
        val REPORT_LABELS = listOf("police" to "🚓  Kontrola", "accident" to "💥  Wypadek", "hazard" to "⚠  Utrudnienie", "closed" to "⛔  Zamknięta ulica")
        val LIMIT_VALUES = listOf(10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120, 140)
    }

    private lateinit var mapView: MapView
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private lateinit var voice: Voice

    private lateinit var searchPanel: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var results: ListView
    private lateinit var favRow: LinearLayout
    private lateinit var navBanner: LinearLayout
    private lateinit var arrow: TextView
    private lateinit var distToNext: TextView
    private lateinit var instruction: TextView
    private lateinit var lanesRow: LinearLayout
    private lateinit var bottomPanel: LinearLayout
    private lateinit var summary: TextView
    private lateinit var summaryDetail: TextView
    private lateinit var startButton: MaterialButton
    private lateinit var cancelButton: MaterialButton
    private lateinit var trafficButton: MaterialButton
    private lateinit var muteButton: MaterialButton
    private lateinit var themeButton: MaterialButton
    private lateinit var gmapsButton: MaterialButton
    private lateinit var routeOptions: LinearLayout
    private lateinit var stopsRow: LinearLayout
    private lateinit var stopsInfo: TextView
    private lateinit var editStopsButton: MaterialButton
    private lateinit var addStopButton: MaterialButton
    private lateinit var sideButtons: LinearLayout
    private lateinit var speedBox: LinearLayout
    private lateinit var speedLimitView: TextView
    private lateinit var currentSpeedView: TextView
    private lateinit var voteBar: LinearLayout
    private lateinit var voteText: TextView
    private lateinit var progress: ProgressBar
    private val optViews = HashMap<String, TextView>()

    private var loadedStyleUrl: String? = null
    private var firstStyle = true
    private var lastThemeCheck = 0L

    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var lastLocation: Location? = null

    // Cel, przystanki, trasa
    private var destination: LatLon? = null
    private var destPlace: Place? = null
    private val stops = ArrayList<Place>()
    private var addingStop = false
    private var pendingPlace: Place? = null
    private var routeResp: RouteResponse? = null
    private var routeMode = "optimal"

    // Nawigacja
    private var engine: NavigationEngine? = null
    private var lastNavState: NavState? = null
    private var navigating = false
    private var rerouting = false
    private var lastRerouteAt = 0L
    private var refreshJob: Job? = null
    private var lastNotifAt = 0L

    // Wyszukiwanie
    private var placeResults: List<Place> = emptyList()
    private var suggestJob: Job? = null
    private var suppressWatcher = false

    // Zgłoszenia
    private var reports: List<Report> = emptyList()
    private val alertedReports = HashSet<Int>()
    private val votedReports = HashSet<Int>()
    private var voteFor: Report? = null

    // Ograniczenia prędkości
    private var freeLimit: Int? = null
    private var lastLimitPoll = 0L
    private val recentPts = ArrayDeque<LatLon>()
    private var overSince = 0L
    private var lastOverWarn = 0L
    private val tone by lazy { runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80) }.getOrNull() }

    // Próbki prędkości (korki od kierowców)
    private var samples = JSONArray()
    private var lastSampleAt = 0L
    private var lastFlushAt = 0L

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true || granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            startLocation()
        } else toast(getString(R.string.need_location))
    }
    private val notifLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

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
            m.cameraPosition = CameraPosition.Builder().target(LatLng(53.1235, 18.0084)).zoom(11.0).build()
            // Styl TaxiNav z serwera; gdy się nie wczyta — styl zapasowy.
            mapView.addOnDidFailLoadingMapListener {
                if (loadedStyleUrl != FALLBACK_STYLE) loadStyle(FALLBACK_STYLE)
            }
            loadStyle(desiredStyleUrl())
            m.addOnMapClickListener {
                if (results.visibility == View.VISIBLE) { results.visibility = View.GONE; hideKeyboard() }
                false
            }
            m.addOnMapLongClickListener { p ->
                if (!navigating) pickPlace(Place("Punkt na mapie", "", p.latitude, p.longitude))
                true
            }
        }

        if (Prefs.server(this).isBlank()) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        handleExternalIntent(intent)
        startReportsLoop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleExternalIntent(intent)
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
        favRow = findViewById(R.id.favRow)
        navBanner = findViewById(R.id.navBanner)
        arrow = findViewById(R.id.arrow)
        distToNext = findViewById(R.id.distToNext)
        instruction = findViewById(R.id.instruction)
        lanesRow = findViewById(R.id.lanesRow)
        bottomPanel = findViewById(R.id.bottomPanel)
        summary = findViewById(R.id.summary)
        summaryDetail = findViewById(R.id.summaryDetail)
        startButton = findViewById(R.id.startButton)
        cancelButton = findViewById(R.id.cancelButton)
        trafficButton = findViewById(R.id.trafficButton)
        muteButton = findViewById(R.id.muteButton)
        themeButton = findViewById(R.id.themeButton)
        gmapsButton = findViewById(R.id.gmapsButton)
        routeOptions = findViewById(R.id.routeOptions)
        stopsRow = findViewById(R.id.stopsRow)
        stopsInfo = findViewById(R.id.stopsInfo)
        editStopsButton = findViewById(R.id.editStopsButton)
        addStopButton = findViewById(R.id.addStopButton)
        sideButtons = findViewById(R.id.sideButtons)
        speedBox = findViewById(R.id.speedBox)
        speedLimitView = findViewById(R.id.speedLimit)
        currentSpeedView = findViewById(R.id.currentSpeed)
        voteBar = findViewById(R.id.voteBar)
        voteText = findViewById(R.id.voteText)
        progress = findViewById(R.id.progress)
        optViews["optimal"] = findViewById(R.id.optOptimal)
        optViews["fastest"] = findViewById(R.id.optFastest)
        optViews["shortest"] = findViewById(R.id.optShortest)
        routeMode = Prefs.routeMode(this)
        for ((mode, v) in optViews) v.setOnClickListener { selectRouteMode(mode) }

        updateThemeButton()
        themeButton.setOnClickListener {
            val next = when (Prefs.mapTheme(this)) { "auto" -> "day"; "day" -> "night"; else -> "auto" }
            Prefs.setMapTheme(this, next)
            updateThemeButton()
            toast(when (next) { "day" -> "Mapa: dzień"; "night" -> "Mapa: noc"; else -> "Mapa: automatycznie (noc 19–7)" })
            reloadStyleIfNeeded()
        }
        gmapsButton.setOnClickListener { openInGoogleMaps() }
        addStopButton.setOnClickListener {
            addingStop = true
            toast("Wyszukaj albo przytrzymaj palec na mapie — dodam przystanek przed celem")
            searchInput.requestFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(searchInput, 0)
        }
        editStopsButton.setOnClickListener { editStops() }
        findViewById<MaterialButton>(R.id.reportButton).setOnClickListener { reportDialog() }
        speedLimitView.setOnClickListener { reportLimitDialog() }
        currentSpeedView.setOnClickListener { reportLimitDialog() }
        findViewById<MaterialButton>(R.id.voteYes).setOnClickListener { vote(true) }
        findViewById<MaterialButton>(R.id.voteNo).setOnClickListener { vote(false) }

        // Podpowiedzi podczas pisania (z opóźnieniem 300 ms) i ostatnie wyszukiwania przy pustym polu.
        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: android.text.Editable?) {
                if (suppressWatcher || !searchInput.hasFocus()) return
                val q = e?.toString()?.trim().orEmpty()
                suggestJob?.cancel()
                if (q.length < 3) { showRecent(); return }
                suggestJob = lifecycleScope.launch {
                    delay(300)
                    suggest(q)
                }
            }
        })
        searchInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && searchInput.text.isNullOrBlank()) showRecent()
        }
        findViewById<ImageButton>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { search(searchInput.text.toString()); true } else false
        }
        results.setOnItemClickListener { _, _, pos, _ ->
            val p = placeResults.getOrNull(pos) ?: return@setOnItemClickListener
            suggestJob?.cancel()
            results.visibility = View.GONE
            hideKeyboard()
            setSearchText(if (addingStop) "" else p.label)
            Prefs.addRecent(this, p)
            pickPlace(p)
        }
        startButton.setOnClickListener { if (navigating) stopNavigation() else startNavigation() }
        summary.setOnClickListener { if (!navigating) showFareDetails() }
        summaryDetail.setOnClickListener { if (!navigating) showFareDetails() }
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
        renderFavorites()
    }

    // ---------- Mapa ----------

    private fun setupLayers(s: Style) {
        s.addSource(GeoJsonSource("alt-src"))
        s.addSource(GeoJsonSource("route-src"))
        s.addSource(GeoJsonSource("stops-src"))
        s.addSource(GeoJsonSource("reports-src"))
        s.addSource(GeoJsonSource("discount-src"))
        // Trasa pod napisami (nazwy ulic i numery domów zostają widoczne).
        val below = s.layers.firstOrNull { it is SymbolLayer }?.id
        val add = { layer: Layer -> if (below != null) s.addLayerBelow(layer, below) else s.addLayer(layer) }
        // Strefy rabatowe (ustawia admin) — delikatnie zielone.
        add(FillLayer("discount-fill", "discount-src").withProperties(
            PropertyFactory.fillColor("#2E9E5B"), PropertyFactory.fillOpacity(0.10f)))
        add(LineLayer("discount-line", "discount-src").withProperties(
            PropertyFactory.lineColor("#2E9E5B"), PropertyFactory.lineWidth(2f), PropertyFactory.lineOpacity(0.6f),
            PropertyFactory.lineDasharray(arrayOf(2f, 2f))))
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
        // Ikony zgłoszeń i przystanków (rysowane z emoji, bo czcionki mapy ich nie mają).
        for ((type, v) in REPORT_ICONS) s.addImage("rep-$type", iconBitmap(v.first, v.second))
        for (i in 1..9) s.addImage("stop-$i", iconBitmap("$i", 0xFF1B6B3A.toInt()))
        s.addImage("stop-dest", iconBitmap("🏁", 0xFF1B6B3A.toInt()))
        s.addLayer(SymbolLayer("stops-layer", "stops-src").withProperties(
            PropertyFactory.iconImage(Expression.get("icon")),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
        ))
        s.addLayer(SymbolLayer("reports-layer", "reports-src").withProperties(
            PropertyFactory.iconImage(Expression.get("icon")),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconSize(Expression.interpolate(Expression.linear(), Expression.zoom(),
                Expression.stop(10, 0.6f), Expression.stop(16, 1.0f))),
        ))
        updateTrafficLayer(s)
        drawReports()
        drawStops()
        loadDiscountZones()
    }

    private var discountJson: String? = null
    private fun loadDiscountZones() {
        discountJson?.let { style?.getSourceAs<GeoJsonSource>("discount-src")?.setGeoJson(it) }
        if (Prefs.server(this).isBlank()) return
        lifecycleScope.launch {
            runCatching { Api.from(this@MainActivity).discountZones() }.getOrNull()?.let {
                discountJson = it
                style?.getSourceAs<GeoJsonSource>("discount-src")?.setGeoJson(it)
            }
        }
    }

    private fun iconBitmap(text: String, bg: Int): Bitmap {
        val d = resources.displayMetrics.density
        val size = (40 * d).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0xFFFFFFFF.toInt()
        c.drawCircle(size / 2f, size / 2f, size / 2f, p)
        p.color = bg
        c.drawCircle(size / 2f, size / 2f, size / 2f - 3 * d, p)
        p.color = 0xFFFFFFFF.toInt()
        p.textSize = 20 * d
        p.textAlign = Paint.Align.CENTER
        p.isFakeBoldText = true
        val y = size / 2f - (p.descent() + p.ascent()) / 2
        c.drawText(text, size / 2f, y, p)
        return bmp
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
            drawStops()
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
        drawStops()
    }

    private fun drawStops() {
        val src = style?.getSourceAs<GeoJsonSource>("stops-src") ?: return
        val f = ArrayList<Feature>()
        stops.forEachIndexed { i, p ->
            f += Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat)).also { it.addStringProperty("icon", "stop-${minOf(i + 1, 9)}") }
        }
        destination?.let { d -> f += Feature.fromGeometry(Point.fromLngLat(d.lon, d.lat)).also { it.addStringProperty("icon", "stop-dest") } }
        src.setGeoJson(FeatureCollection.fromFeatures(f))
    }

    private fun drawReports() {
        val src = style?.getSourceAs<GeoJsonSource>("reports-src") ?: return
        src.setGeoJson(FeatureCollection.fromFeatures(reports.map { r ->
            Feature.fromGeometry(Point.fromLngLat(r.lon, r.lat)).also { it.addStringProperty("icon", "rep-${r.type}") }
        }))
    }

    private fun fitRoute(r: Route) {
        val m = map ?: return
        if (r.points.size < 2) return
        val b = LatLngBounds.Builder()
        r.points.forEach { b.include(LatLng(it.lat, it.lon)) }
        m.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 80, 260, 80, 420), 800)
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
        val first = lastLocation == null
        lastLocation = loc
        val pos = LatLon(loc.latitude, loc.longitude)
        if (SystemClock.elapsedRealtime() - lastThemeCheck > 60_000) {
            lastThemeCheck = SystemClock.elapsedRealtime()
            reloadStyleIfNeeded() // automatyczne przełączenie dzień/noc
        }
        map?.locationComponent?.takeIf { it.isLocationComponentActivated }?.forceLocationUpdate(loc)
        if (first) pendingPlace?.let { pendingPlace = null; pickPlace(it) }
        collectSpeedSample(loc)
        recentPts.addLast(pos)
        while (recentPts.size > 8) recentPts.removeFirst()

        val e = engine
        if (navigating && e != null) {
            val st = e.update(pos, loc.speed.toDouble(), loc.accuracy.toDouble())
            lastNavState = st
            renderNavState(st)
            checkReportsOnRoute(e, st)
            updateSpeed(loc, st.speedLimit)
            if (st.arrived) {
                stopNavigation(arrived = true)
                return
            }
            if (st.offRoute && !rerouting && SystemClock.elapsedRealtime() - lastRerouteAt > REROUTE_MIN_INTERVAL_MS) {
                reroute(silent = false)
            }
        } else {
            pollFreeLimit(loc)
            checkReportsAhead(loc)
            updateSpeed(loc, freeLimit)
        }
    }

    // ---------- Prędkość i ograniczenia ----------

    private fun updateSpeed(loc: Location, limit: Int?) {
        val kmh = if (loc.hasSpeed()) (loc.speed * 3.6).toInt() else -1
        currentSpeedView.visibility = if (kmh >= 0) View.VISIBLE else View.GONE
        currentSpeedView.text = "$kmh km/h"
        if (limit == null) {
            speedLimitView.visibility = View.GONE
            speedLimitView.isActivated = false
            return
        }
        speedLimitView.visibility = View.VISIBLE
        speedLimitView.text = "$limit"
        val now = SystemClock.elapsedRealtime()
        if (kmh > limit + 5) {
            if (overSince == 0L) overSince = now
            if (now - overSince > 3000) {
                speedLimitView.isActivated = true
                if (now - lastOverWarn > 60_000) {
                    lastOverWarn = now
                    tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 300)
                    voice.say("Ograniczenie do $limit.")
                }
            }
        } else {
            overSince = 0L
            speedLimitView.isActivated = false
        }
    }

    /** Bez trasy: co 15 s pytamy serwer o limit na ulicy, którą jedziemy. */
    private fun pollFreeLimit(loc: Location) {
        val now = SystemClock.elapsedRealtime()
        if (loc.speed < 3f || recentPts.size < 3 || now - lastLimitPoll < 15_000 || Prefs.server(this).isBlank()) return
        lastLimitPoll = now
        val pts = recentPts.toList()
        lifecycleScope.launch {
            freeLimit = runCatching { Api.from(this@MainActivity).limitAt(pts) }.getOrNull()
        }
    }

    private fun reportLimitDialog() {
        if (!requireAccount()) return
        val loc = lastLocation ?: run { toast("Czekam na GPS…"); return }
        val labels = LIMIT_VALUES.map { "$it km/h" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Jakie jest tu ograniczenie?")
            .setItems(labels) { _, i ->
                val v = LIMIT_VALUES[i]
                lifecycleScope.launch {
                    try {
                        val status = Api.from(this@MainActivity).reportLimit(LatLon(loc.latitude, loc.longitude), loc.bearingOrNull(), v, "")
                        toast(if (status == "approved") "Zapisano: $v km/h" else "Wysłano do zatwierdzenia przez admina: $v km/h")
                    } catch (e: ApiException) { toast(e.message ?: "Błąd") }
                }
            }
            .setNegativeButton("Anuluj", null)
            .show()
    }

    // ---------- Zgłoszenia (jak Yanosik) ----------

    private fun requireAccount(): Boolean {
        if (Prefs.userToken(this).isNotEmpty()) return true
        AlertDialog.Builder(this)
            .setTitle("Potrzebne konto")
            .setMessage("Żeby zgłaszać zdarzenia i ograniczenia, załóż konto w Ustawieniach (wystarczy imię lub kod).")
            .setPositiveButton("Ustawienia") { _, _ -> startActivity(Intent(this, SettingsActivity::class.java)) }
            .setNegativeButton("Anuluj", null)
            .show()
        return false
    }

    private fun reportDialog() {
        if (!requireAccount()) return
        val loc = lastLocation ?: run { toast("Czekam na GPS…"); return }
        AlertDialog.Builder(this)
            .setTitle("Zgłoś na mapie")
            .setItems(REPORT_LABELS.map { it.second }.toTypedArray()) { _, i ->
                val type = REPORT_LABELS[i].first
                lifecycleScope.launch {
                    try {
                        Api.from(this@MainActivity).addReport(type, LatLon(loc.latitude, loc.longitude), loc.bearingOrNull())
                        toast("Dzięki! Zgłoszenie widzą inni kierowcy")
                        refreshReports()
                    } catch (e: ApiException) { toast(e.message ?: "Błąd") }
                }
            }
            .setNegativeButton("Anuluj", null)
            .show()
    }

    private fun startReportsLoop() {
        lifecycleScope.launch {
            while (isActive) {
                refreshReports()
                delay(60_000)
            }
        }
    }

    private suspend fun refreshReports() {
        if (Prefs.server(this).isBlank()) return
        val near = lastLocation?.let { LatLon(it.latitude, it.longitude) }
        runCatching { Api.from(this).reports(near) }.getOrNull()?.let {
            reports = it
            drawReports()
        }
    }

    /** Na trasie: ostrzeż przed zgłoszeniem leżącym na trasie przed nami; przy mijaniu zapytaj „nadal jest?”. */
    private fun checkReportsOnRoute(e: NavigationEngine, st: NavState) {
        for (r in reports) {
            val (along, dist) = e.project(LatLon(r.lat, r.lon))
            if (dist > 40) continue
            val ahead = along - st.alongM
            if (ahead in 0.0..700.0 && r.id !in alertedReports) {
                alertedReports += r.id
                voice.say("Uwaga, ${r.label.lowercase()}, ${Speech.inDistance(ahead).replaceFirstChar { it.lowercase() }}.")
            }
            if (ahead in -40.0..25.0) askVote(r)
        }
    }

    /** Bez trasy: zgłoszenie w promieniu 400 m mniej więcej na wprost. */
    private fun checkReportsAhead(loc: Location) {
        if (!loc.hasBearing() || loc.speed < 4f) return
        val me = LatLon(loc.latitude, loc.longitude)
        for (r in reports) {
            val p = LatLon(r.lat, r.lon)
            val d = Geo.distance(me, p)
            val brg = bearingTo(me, p)
            val diff = abs(((brg - loc.bearing + 540) % 360) - 180)
            if (d < 400 && diff < 30 && r.id !in alertedReports) {
                alertedReports += r.id
                voice.say("Uwaga, ${r.label.lowercase()}, ${Speech.inDistance(d).replaceFirstChar { it.lowercase() }}.")
            }
            if (d < 30) askVote(r)
        }
    }

    private fun bearingTo(a: LatLon, b: LatLon): Double {
        val la1 = Math.toRadians(a.lat); val la2 = Math.toRadians(b.lat); val dl = Math.toRadians(b.lon - a.lon)
        val y = Math.sin(dl) * Math.cos(la2)
        val x = Math.cos(la1) * Math.sin(la2) - Math.sin(la1) * Math.cos(la2) * Math.cos(dl)
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360
    }

    private fun askVote(r: Report) {
        if (r.id in votedReports || voteFor != null || Prefs.userToken(this).isEmpty()) return
        voteFor = r
        votedReports += r.id
        voteText.text = "${r.label} — nadal jest?"
        voteBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            delay(15_000)
            if (voteFor?.id == r.id) { voteFor = null; voteBar.visibility = View.GONE }
        }
    }

    private fun vote(stillThere: Boolean) {
        val r = voteFor ?: return
        voteFor = null
        voteBar.visibility = View.GONE
        lifecycleScope.launch {
            runCatching { Api.from(this@MainActivity).voteReport(r.id, stillThere) }
            refreshReports()
        }
    }

    // ---------- Próbki prędkości (anonimowe korki od kierowców) ----------

    private fun collectSpeedSample(loc: Location) {
        val now = System.currentTimeMillis()
        if (loc.hasSpeed() && loc.hasBearing() && loc.accuracy < 30 && now - lastSampleAt >= 5000) {
            lastSampleAt = now
            samples.put(JSONObject().put("lat", loc.latitude).put("lon", loc.longitude)
                .put("speed", loc.speed.toDouble()).put("heading", loc.bearing.toDouble()).put("t", now))
        }
        if (samples.length() > 0 && now - lastFlushAt > 60_000 && Prefs.server(this).isNotBlank()) {
            lastFlushAt = now
            val batch = samples
            samples = JSONArray()
            lifecycleScope.launch { runCatching { Api.from(this@MainActivity).sendSpeed(Prefs.deviceId(this@MainActivity), batch) } }
        }
    }

    // ---------- Wyszukiwanie, cele, przystanki ----------

    private fun setSearchText(t: String) {
        suppressWatcher = true
        searchInput.setText(t)
        suppressWatcher = false
    }

    private fun search(q: String) {
        if (q.isBlank()) return
        hideKeyboard()
        lifecycleScope.launch {
            progress.visibility = View.VISIBLE
            try {
                val near = lastLocation?.let { LatLon(it.latitude, it.longitude) }
                val found = Api.from(this@MainActivity).geocode(q, near)
                if (found.isEmpty()) toast("Nie znaleziono: $q")
                showResults(found, recent = false)
            } catch (e: ApiException) {
                toast(e.message ?: "Błąd")
            } finally {
                progress.visibility = View.GONE
            }
        }
    }

    private fun suggest(q: String) {
        lifecycleScope.launch {
            try {
                val near = lastLocation?.let { LatLon(it.latitude, it.longitude) }
                val found = Api.from(this@MainActivity).geocode(q, near, fast = true)
                if (searchInput.text.toString().trim() == q && searchInput.hasFocus()) showResults(found, recent = false)
            } catch (_: ApiException) { /* podpowiedzi są opcjonalne */ }
        }
    }

    private fun showRecent() {
        val r = Prefs.recent(this)
        if (r.isEmpty() || navigating) { results.visibility = View.GONE; return }
        showResults(r, recent = true)
    }

    private fun showResults(list: List<Place>, recent: Boolean) {
        placeResults = list
        val rows = list.map { (if (recent) "🕘  " else "") + it.toString() }
        results.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, rows)
        results.layoutParams = results.layoutParams.apply {
            height = if (list.size > 5) (resources.displayMetrics.density * 320).toInt()
            else LinearLayout.LayoutParams.WRAP_CONTENT
        }
        results.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
    }

    /** Wybrane miejsce: cel albo (w trybie „+ Przystanek”) przystanek przed celem. */
    private fun pickPlace(p: Place) {
        if (lastLocation == null) {
            pendingPlace = p
            toast("Czekam na GPS…")
            return
        }
        if (addingStop && destination != null) {
            addingStop = false
            if (stops.size >= 8) { toast("Maksymalnie 8 przystanków"); return }
            stops += p
        } else {
            addingStop = false
            if (routeResp == null) stops.clear()
            destPlace = p
            destination = LatLon(p.lat, p.lon)
        }
        requestRoute(fit = true)
    }

    private fun requestRoute(fit: Boolean) {
        val loc = lastLocation ?: return
        val dest = destination ?: return
        lifecycleScope.launch {
            progress.visibility = View.VISIBLE
            try {
                val resp = Api.from(this@MainActivity)
                    .route(LatLon(loc.latitude, loc.longitude), dest, loc.bearingOrNull(), stops.map { LatLon(it.lat, it.lon) })
                    .withMode(routeMode)
                routeResp = resp
                drawRoute(resp)
                if (fit) fitRoute(resp.chosen)
                showPreview(resp)
            } catch (e: ApiException) {
                toast(e.message ?: "Błąd")
            } finally {
                progress.visibility = View.GONE
            }
        }
    }

    private fun editStops() {
        val all = ArrayList(stops + listOfNotNull(destPlace))
        if (all.size < 2) return
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 16, 24, 0) }
        lateinit var dialog: AlertDialog
        fun render() {
            box.removeAllViews()
            all.forEachIndexed { i, p ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                row.addView(TextView(this).apply {
                    text = (if (i == all.size - 1) "🏁 " else "${i + 1}. ") + p.label
                    textSize = 16f
                    setTextColor(color(R.color.text))
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                fun btn(t: String, enabled: Boolean, f: () -> Unit) = row.addView(MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                    text = t; isEnabled = enabled; minWidth = 0; setOnClickListener { f(); render() }
                })
                btn("↑", i > 0) { java.util.Collections.swap(all, i, i - 1) }
                btn("↓", i < all.size - 1) { java.util.Collections.swap(all, i, i + 1) }
                btn("✕", all.size > 1) { all.removeAt(i) }
                box.addView(row)
            }
        }
        render()
        dialog = AlertDialog.Builder(this)
            .setTitle("Kolejność przystanków")
            .setView(box)
            .setPositiveButton("Zapisz") { _, _ ->
                stops.clear()
                stops.addAll(all.dropLast(1))
                destPlace = all.last()
                destination = LatLon(destPlace!!.lat, destPlace!!.lon)
                requestRoute(fit = true)
            }
            .setNegativeButton("Anuluj", null)
            .show()
    }

    private fun openInGoogleMaps() {
        val d = destination ?: return
        var url = "https://www.google.com/maps/dir/?api=1&destination=${d.lat},${d.lon}&travelmode=driving"
        if (stops.isNotEmpty()) url += "&waypoints=" + Uri.encode(stops.joinToString("|") { "${it.lat},${it.lon}" })
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage("com.google.android.apps.maps"))
        } catch (_: Exception) {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.onFailure { toast("Nie można otworzyć Google Maps") }
        }
    }

    private fun selectRouteMode(mode: String) {
        routeMode = mode
        Prefs.setRouteMode(this, mode)
        val resp = routeResp?.withMode(mode) ?: return
        routeResp = resp
        drawRoute(resp)
        showPreview(resp)
    }

    private fun showPreview(resp: RouteResponse) {
        val r = resp.chosen
        bottomPanel.visibility = View.VISIBLE
        cancelButton.visibility = View.VISIBLE
        gmapsButton.visibility = View.VISIBLE
        routeOptions.visibility = View.VISIBLE
        stopsRow.visibility = View.VISIBLE
        startButton.text = getString(R.string.start)
        for (mode in RouteModes.ALL) {
            val v = optViews[mode] ?: continue
            val m = resp.routeFor(mode)
            v.text = "${RouteModes.label(mode)}\n${minutes(m.trafficTimeS)} · ${Speech.short(m.distanceM.toDouble())}" +
                (m.fare?.let { "\n≈ ${Fare.zlShort(it.shown)}" } ?: "")
            v.isSelected = mode == routeMode
        }
        stopsInfo.text = if (stops.isEmpty()) "Cel: ${destPlace?.label ?: ""}"
        else stops.mapIndexed { i, p -> "${i + 1}. ${p.label}" }.joinToString(" → ") + " → 🏁 ${destPlace?.label ?: ""}"
        editStopsButton.visibility = if (stops.isNotEmpty()) View.VISIBLE else View.GONE
        summary.text = "${minutes(r.trafficTimeS)} · ${Speech.short(r.distanceM.toDouble())}" +
            (r.fare?.let { f -> "  ·  ≈ ${Fare.zl(f.shown)}" + if (f.flatValue != null) " (ryczałt)" else "" } ?: "")
        val delayS = r.trafficTimeS - r.baseTimeS
        summaryDetail.text = buildString {
            append(when (routeMode) {
                "fastest" -> "Najszybsza (z korkami)"
                "shortest" -> "Najkrótsza (najmniej km)"
                else -> "Optymalna: krótka, bez straty czasu"
            })
            if (r.hasTraffic) append(if (delayS >= 60) " · korki +${delayS / 60} min" else " · bez korków")
            else append(" · brak danych o korkach")
            r.fare?.let { f ->
                append("\nCena szacunkowa · taryfa ${f.tariffCity}${if (f.outKm > 0.05) "/${f.tariffOut}" else ""}")
                if (f.discountPct > 0) append(" · rabat ${f.discountPct}%")
                append(" — dotknij, aby zobaczyć wyliczenie")
            }
        }
    }

    /** Rozbicie szacowanej ceny jak w kalkulatorze taryf. */
    private fun showFareDetails() {
        val r = routeResp?.chosen ?: return
        val f = r.fare ?: return
        fun z(v: Double) = Fare.zl(v)
        val txt = buildString {
            append("Opłata początkowa: ${z(f.startFee)}\n")
            append("Taryfa ${f.tariffCity} · miejska (${"%.2f".format(f.cityKm)} km): ${z(f.cityCost)}\n")
            if (f.outKm > 0.01) append("Taryfa ${f.tariffOut} · zamiejska (${"%.2f".format(f.outKm)} km): ${z(f.outCost)}\n")
            if (f.trafficMin > 0) append("Korki / postój (~${f.trafficMin} min): ${z(f.trafficCost)}\n")
            if (f.discountPct > 0) append("Rabat −${f.discountPct}% (${f.discountReason ?: ""}): −${z(f.before - f.total)}\n")
            append("\nRAZEM (taksometr): ${z(f.total)}\n")
            if (f.flatValue != null) append("Ryczałt ${f.flatCity?.replaceFirstChar { it.uppercase() }}: ${z(f.flatValue)}\n")
            append("\n${if (f.night) "Taryfa nocna / niedziela / święto" else "Taryfa dzienna"} · ${f.tariffName}")
            if (f.districtA != null || f.districtB != null) append("\nStart: ${f.districtA ?: "—"}\nCel: ${f.districtB ?: "—"}")
            append("\n\nTo szacunek — kwota na taksometrze zależy od faktycznej trasy i postojów.")
        }
        AlertDialog.Builder(this).setTitle("Szacowana cena").setMessage(txt).setPositiveButton("OK", null).show()
    }

    private fun clearRoute() {
        routeResp = null; destination = null; destPlace = null; engine = null
        stops.clear(); addingStop = false
        drawRoute(null)
        bottomPanel.visibility = View.GONE
        setSearchText("")
        renderFavorites()
    }

    // ---------- Szybkie cele (ulubione) ----------

    private fun renderFavorites() {
        favRow.removeAllViews()
        val d = resources.displayMetrics.density
        fun chip(text: String, onClick: () -> Unit, onLong: (() -> Unit)? = null) {
            val tv = TextView(this).apply {
                this.text = text
                textSize = 15f
                setTextColor(color(R.color.text))
                setBackgroundResource(R.drawable.chip)
                setPadding((14 * d).toInt(), (9 * d).toInt(), (14 * d).toInt(), (9 * d).toInt())
                setOnClickListener { onClick() }
                if (onLong != null) setOnLongClickListener { onLong(); true }
            }
            favRow.addView(tv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = (8 * d).toInt() })
        }
        for (f in Prefs.favorites(this)) {
            chip("⭐ ${f.name}", { pickPlace(Place(f.name, f.label, f.lat, f.lon)) }) {
                AlertDialog.Builder(this).setTitle("Usunąć „${f.name}”?")
                    .setPositiveButton("Usuń") { _, _ -> Prefs.saveFavorites(this, Prefs.favorites(this).filter { it != f }); renderFavorites() }
                    .setNegativeButton("Anuluj", null).show()
            }
        }
        chip("+ Dodaj ulubione", { addFavorite() })
    }

    private fun addFavorite() {
        val p = destPlace
        if (p == null) {
            toast("Najpierw wybierz cel (wyszukaj albo przytrzymaj palec na mapie), potem dodaj go do ulubionych")
            return
        }
        val d = resources.displayMetrics.density
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), 0) }
        val input = EditText(this).apply { setText(if (p.label == "Punkt na mapie") "" else p.label); hint = "Nazwa, np. Baza" }
        box.addView(input)
        val presets = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (name in listOf("Baza", "Dworzec", "Lotnisko", "Szpital", "Dom")) {
            presets.addView(MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                text = name; minWidth = 0; setPadding(8, 0, 8, 0); setOnClickListener { input.setText(name) }
            })
        }
        box.addView(android.widget.HorizontalScrollView(this).apply { addView(presets) })
        AlertDialog.Builder(this)
            .setTitle("Dodaj do szybkich celów")
            .setMessage(p.toString())
            .setView(box)
            .setPositiveButton("Zapisz") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { p.label }
                Prefs.saveFavorites(this, Prefs.favorites(this).filter { it.name != name } + Favorite(name, p.toString(), p.lat, p.lon))
                renderFavorites()
                toast("Dodano „$name”")
            }
            .setNegativeButton("Anuluj", null)
            .show()
    }

    // ---------- Linki z innych aplikacji ----------

    private fun handleExternalIntent(intent: Intent?) {
        if (intent == null) return
        val raw = when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let { t ->
                Regex("""https?://\S+""").find(t)?.value ?: t
            }
            else -> null
        } ?: return
        intent.action = null // nie obsługuj drugi raz po obrocie ekranu
        lifecycleScope.launch {
            val url = if (raw.contains("goo.gl/")) resolveShortLink(raw) else raw
            when (val t = ExternalLinks.parse(url)) {
                is ExternalTarget.Point -> pickPlace(Place(t.label ?: "Cel z innej aplikacji", "", t.pos.lat, t.pos.lon))
                is ExternalTarget.Query -> { setSearchText(t.text); search(t.text) }
                null -> toast("Nie rozpoznano adresu z linku")
            }
        }
    }

    /** Krótkie linki maps.app.goo.gl — śledzimy przekierowania do pełnego adresu Google Maps. */
    private suspend fun resolveShortLink(url: String): String = withContext(Dispatchers.IO) {
        var cur = url
        repeat(5) {
            val c = runCatching { URL(cur).openConnection() as HttpURLConnection }.getOrNull() ?: return@withContext cur
            try {
                c.instanceFollowRedirects = false
                c.connectTimeout = 5000; c.readTimeout = 5000
                c.requestMethod = "GET"
                val loc = c.getHeaderField("Location")
                if (c.responseCode in 300..399 && loc != null) cur = URL(URL(cur), loc).toString() else return@withContext cur
            } catch (_: Exception) {
                return@withContext cur
            } finally { c.disconnect() }
        }
        cur
    }

    // ---------- Nawigacja ----------

    @SuppressLint("MissingPermission")
    private fun startNavigation() {
        val resp = routeResp ?: return
        navigating = true
        alertedReports.clear()
        engine = NavigationEngine(resp.chosen, voice::say)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        searchPanel.visibility = View.GONE
        results.visibility = View.GONE
        navBanner.visibility = View.VISIBLE
        cancelButton.visibility = View.GONE
        gmapsButton.visibility = View.GONE
        routeOptions.visibility = View.GONE
        stopsRow.visibility = View.GONE
        startButton.text = getString(R.string.stop)
        drawRoute(resp)
        followCamera()
        // Nawigacja w tle (wygaszony ekran, rozmowa) + powiadomienie z manewrem.
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        runCatching { NavService.start(this) }
        updatePipParams()
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
        runCatching { NavService.stop(this) }
        updatePipParams()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        navBanner.visibility = View.GONE
        lanesRow.visibility = View.GONE
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
        // Przystanki, które już minęliśmy, wypadają z trasy.
        val passed = lastNavState?.let { st -> engine?.route?.maneuvers?.count { it.stop != null && it.begin <= st.traveledIndex } } ?: 0
        repeat(minOf(passed, stops.size)) { stops.removeAt(0) }
        lifecycleScope.launch {
            try {
                val resp = Api.from(this@MainActivity)
                    .route(LatLon(loc.latitude, loc.longitude), dest, loc.bearingOrNull(), stops.map { LatLon(it.lat, it.lon) })
                    .withMode(routeMode)
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
        renderLanes(st.lanes)
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotifAt > 2000) {
            lastNotifAt = now
            NavService.update(this, "${arrow.text} ${distToNext.text} · ${next?.instruction ?: ""}",
                "Przyjazd $eta · zostało ${Speech.short(st.remainingM)}")
        }
    }

    private fun renderLanes(info: LaneInfo?) {
        if (info == null || isInPip()) { lanesRow.visibility = View.GONE; return }
        lanesRow.removeAllViews()
        val d = resources.displayMetrics.density
        for (l in info.lanes) {
            lanesRow.addView(TextView(this).apply {
                text = Lanes.label(l)
                textSize = 26f
                setTextColor(if (l.ok) 0xFFFFFFFF.toInt() else 0x66FFFFFF)
                setTypeface(typeface, if (l.ok) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                setPadding((8 * d).toInt(), (2 * d).toInt(), (8 * d).toInt(), (2 * d).toInt())
            })
        }
        lanesRow.visibility = View.VISIBLE
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

    // ---------- Małe okienko (obraz w obrazie) jak w Google Maps ----------

    private fun isInPip() = Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode

    private fun pipParams(): PictureInPictureParams? {
        if (Build.VERSION.SDK_INT < 26) return null
        val b = PictureInPictureParams.Builder().setAspectRatio(Rational(3, 4))
        if (Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(navigating).setSeamlessResizeEnabled(true)
        return b.build()
    }

    private fun updatePipParams() {
        if (Build.VERSION.SDK_INT >= 26) runCatching { pipParams()?.let { setPictureInPictureParams(it) } }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (navigating && Build.VERSION.SDK_INT in 26..30) runCatching { pipParams()?.let { enterPictureInPictureMode(it) } }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        val vis = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
        sideButtons.visibility = vis
        bottomPanel.visibility = if (isInPictureInPictureMode || routeResp == null) View.GONE else View.VISIBLE
        instruction.visibility = vis
        speedBox.visibility = vis
        voteBar.visibility = View.GONE
        if (isInPictureInPictureMode) lanesRow.visibility = View.GONE
        if (!navigating) searchPanel.visibility = vis
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

    // ---------- Cykl życia ----------

    override fun onStart() { super.onStart(); mapView.onStart() }
    override fun onResume() {
        super.onResume(); mapView.onResume()
        style?.let { updateTrafficLayer(it) } // po zmianie ustawień
        reloadStyleIfNeeded()
        renderFavorites()
    }
    override fun onPause() { super.onPause(); mapView.onPause() }
    override fun onStop() { super.onStop(); mapView.onStop() }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); mapView.onSaveInstanceState(outState) }
    override fun onDestroy() {
        fused.removeLocationUpdates(locationCallback)
        if (navigating) runCatching { NavService.stop(this) }
        voice.shutdown()
        tone?.release()
        mapView.onDestroy()
        super.onDestroy()
    }
}
