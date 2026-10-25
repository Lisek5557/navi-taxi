package pl.taxinav.car

import android.app.Presentation
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.graphics.Rect
import androidx.car.app.AppManager
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Distance
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import androidx.car.app.model.DateTimeWithZone
import androidx.car.app.validation.HostValidator
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import pl.taxinav.LatLon
import pl.taxinav.MainActivity
import pl.taxinav.NavCamera
import pl.taxinav.Place
import pl.taxinav.Prefs
import pl.taxinav.R
import pl.taxinav.Speech
import java.util.TimeZone

/** Android Auto: nawigacja TaxiNav na ekranie samochodu. */
class TaxiCarAppService : CarAppService() {
    // Wersja testowa (instalacja spoza sklepu) — akceptuje każdy host Android Auto.
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    override fun onCreateSession(): Session = TaxiSession()
}

class TaxiSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        MapLibre.getInstance(carContext)
        return NavScreen(carContext)
    }
}

/** Ekran główny: mapa + manewr + czas przyjazdu, przyciski „Cele” i „Stop”. */
class NavScreen(ctx: CarContext) : Screen(ctx), DefaultLifecycleObserver {
    private val renderer = CarMapRenderer(ctx)
    private val navManager: NavigationManager = ctx.getCarService(NavigationManager::class.java)
    private var navStarted = false
    private var lastInvalidate = 0L
    private val listener: () -> Unit = {
        renderer.update()
        syncNavigation()
        val now = System.currentTimeMillis()
        if (now - lastInvalidate > 900) { lastInvalidate = now; invalidate() }
    }

    init {
        lifecycle.addObserver(this)
        navManager.setNavigationManagerCallback(object : NavigationManagerCallback {
            override fun onStopNavigation() { NavBus.requestStop?.invoke() }
            override fun onAutoDriveEnabled() {}
        })
    }

    override fun onCreate(owner: LifecycleOwner) {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(renderer)
        NavBus.addListener(listener)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        NavBus.removeListener(listener)
        if (navStarted) runCatching { navManager.navigationEnded() }
        renderer.release()
    }

    private fun syncNavigation() {
        if (NavBus.navigating && !navStarted) { navStarted = true; runCatching { navManager.navigationStarted() } }
        if (!NavBus.navigating && navStarted) { navStarted = false; runCatching { navManager.navigationEnded() } }
    }

    override fun onGetTemplate(): Template {
        val actions = ActionStrip.Builder()
            .addAction(Action.Builder().setTitle("Cele").setOnClickListener {
                screenManager.push(DestinationsScreen(carContext))
            }.build())
        if (NavBus.navigating) actions.addAction(Action.Builder().setTitle("Stop").setOnClickListener {
            NavBus.requestStop?.invoke()
        }.build())
        val b = NavigationTemplate.Builder().setActionStrip(actions.build())
        val st = NavBus.state
        if (NavBus.navigating && st != null) {
            val next = st.nextManeuver
            val step = Step.Builder(next?.instruction ?: "Jedź prosto")
                .setManeuver(Maneuver.Builder(carManeuver(next?.type ?: 8)).build())
                .build()
            b.setNavigationInfo(RoutingInfo.Builder()
                .setCurrentStep(step, Distance.create(st.distanceToNextM, Distance.UNIT_METERS))
                .build())
            val arrive = DateTimeWithZone.create(System.currentTimeMillis() + (st.remainingS * 1000).toLong(), TimeZone.getDefault())
            b.setDestinationTravelEstimate(TravelEstimate.Builder(
                Distance.create(st.remainingM / 1000.0, Distance.UNIT_KILOMETERS), arrive)
                .setRemainingTimeSeconds(st.remainingS.toLong()).build())
        } else {
            val msg = if (NavBus.phoneReady) "Wybierz cel: przycisk „Cele” albo na telefonie"
            else "Otwórz TaxiNav na telefonie"
            b.setNavigationInfo(MessageInfo.Builder("TaxiNav").setText(msg).build())
        }
        return b.build()
    }

    /** Typ manewru Valhalli -> typ manewru Android Auto. */
    private fun carManeuver(t: Int): Int = when (t) {
        1, 2, 3 -> Maneuver.TYPE_DEPART
        4, 5, 6 -> Maneuver.TYPE_DESTINATION
        9 -> Maneuver.TYPE_TURN_SLIGHT_RIGHT
        10 -> Maneuver.TYPE_TURN_NORMAL_RIGHT
        11 -> Maneuver.TYPE_TURN_SHARP_RIGHT
        12 -> Maneuver.TYPE_U_TURN_RIGHT
        13 -> Maneuver.TYPE_U_TURN_LEFT
        14 -> Maneuver.TYPE_TURN_SHARP_LEFT
        15 -> Maneuver.TYPE_TURN_NORMAL_LEFT
        16 -> Maneuver.TYPE_TURN_SLIGHT_LEFT
        18 -> Maneuver.TYPE_ON_RAMP_NORMAL_RIGHT
        19 -> Maneuver.TYPE_ON_RAMP_NORMAL_LEFT
        20 -> Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT
        21 -> Maneuver.TYPE_OFF_RAMP_NORMAL_LEFT
        23 -> Maneuver.TYPE_KEEP_RIGHT
        24 -> Maneuver.TYPE_KEEP_LEFT
        25 -> Maneuver.TYPE_MERGE_SIDE_UNSPECIFIED
        26 -> Maneuver.TYPE_ROUNDABOUT_ENTER_CCW
        27 -> Maneuver.TYPE_ROUNDABOUT_EXIT_CCW
        else -> Maneuver.TYPE_STRAIGHT
    }
}

/** Lista celów w samochodzie: ulubione + ostatnie wyszukiwania. */
class DestinationsScreen(ctx: CarContext) : Screen(ctx) {
    override fun onGetTemplate(): Template {
        val items = ItemList.Builder()
        val places = Prefs.favorites(carContext).map { Place(it.name, it.label, it.lat, it.lon) } +
            Prefs.recent(carContext).filter { r -> Prefs.favorites(carContext).none { it.lat == r.lat && it.lon == r.lon } }
        if (places.isEmpty()) items.setNoItemsMessage("Dodaj ulubione cele w aplikacji na telefonie")
        places.take(12).forEachIndexed { i, p ->
            items.addItem(Row.Builder()
                .setTitle((if (i < Prefs.favorites(carContext).size) "⭐ " else "") + p.label)
                .apply { if (p.detail.isNotBlank()) addText(p.detail.replace('\n', ' ')) }
                .setOnClickListener {
                    val req = NavBus.requestDestination
                    if (req == null) CarToast.makeText(carContext, "Otwórz TaxiNav na telefonie", CarToast.LENGTH_LONG).show()
                    else { req(p); screenManager.pop() }
                }.build())
        }
        return ListTemplate.Builder().setTitle("Dokąd jedziemy?").setHeaderAction(Action.BACK).setSingleList(items.build()).build()
    }
}

/** Mapa MapLibre narysowana na powierzchni ekranu samochodu (VirtualDisplay + Presentation). */
class CarMapRenderer(private val ctx: CarContext) : SurfaceCallback {
    private var display: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var mapView: MapView? = null
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var visible = Rect()
    private var camBearing = -1.0
    private var camZoom = 16.0

    override fun onSurfaceAvailable(container: SurfaceContainer) {
        val surface = container.surface ?: return
        release()
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val vd = dm.createVirtualDisplay("TaxiNavCar", container.width, container.height, container.dpi, surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY)
        display = vd
        val p = Presentation(ctx, vd.display, R.style.Theme_TaxiNav)
        val mv = MapView(p.context)
        p.setContentView(mv)
        p.show()
        presentation = p
        mapView = mv
        mv.onCreate(null); mv.onStart(); mv.onResume()
        mv.getMapAsync { m ->
            map = m
            m.uiSettings.isCompassEnabled = false
            m.uiSettings.isLogoEnabled = false
            m.uiSettings.isAttributionEnabled = false
            val url = NavBus.styleUrl ?: MainActivity.FALLBACK_STYLE
            m.setStyle(Style.Builder().fromUri(url)) { s ->
                style = s
                s.addSource(GeoJsonSource("car-route"))
                s.addSource(GeoJsonSource("car-me"))
                s.addLayer(LineLayer("car-route-casing", "car-route").withProperties(
                    PropertyFactory.lineColor("#FFFFFF"), PropertyFactory.lineWidth(12f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)))
                s.addLayer(LineLayer("car-route-line", "car-route").withProperties(
                    PropertyFactory.lineColor("#3478F6"), PropertyFactory.lineWidth(8f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)))
                s.addLayer(CircleLayer("car-me-dot", "car-me").withProperties(
                    PropertyFactory.circleRadius(11f), PropertyFactory.circleColor("#FFC629"),
                    PropertyFactory.circleStrokeColor("#000000"), PropertyFactory.circleStrokeWidth(3f)))
                update()
            }
        }
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) { visible = visibleArea; update() }
    override fun onStableAreaChanged(stableArea: Rect) {}
    override fun onSurfaceDestroyed(container: SurfaceContainer) = release()

    fun update() {
        val m = map ?: return
        val s = style ?: return
        val route = NavBus.route
        s.getSourceAs<GeoJsonSource>("car-route")?.setGeoJson(
            if (route == null || !NavBus.navigating) FeatureCollection.fromFeatures(emptyList())
            else FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(route.points.map { Point.fromLngLat(it.lon, it.lat) }))))
        val loc = NavBus.location ?: return
        val st = NavBus.state
        val pos = if (st != null && NavBus.navigating && !st.offRoute) LatLon(st.snapped.lat, st.snapped.lon) else LatLon(loc.latitude, loc.longitude)
        s.getSourceAs<GeoJsonSource>("car-me")?.setGeoJson(Point.fromLngLat(pos.lon, pos.lat))
        val kmh = if (loc.hasSpeed()) loc.speed * 3.6 else 0.0
        camZoom += (NavCamera.zoomFor(kmh, st?.distanceToNextM ?: 1000.0) - camZoom) * 0.35
        val brg = if (loc.hasBearing() && kmh > 4) loc.bearing.toDouble() else camBearing.coerceAtLeast(0.0)
        camBearing = if (camBearing < 0) brg else NavCamera.smoothAngle(camBearing, brg, 0.5)
        val h = (presentation?.window?.decorView?.height ?: 0).toDouble()
        val top = if (visible.height() > 0) visible.top + visible.height() * 0.45 else h * 0.45
        m.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder()
            .target(LatLng(pos.lat, pos.lon)).zoom(camZoom).bearing(if (NavBus.navigating) camBearing else 0.0)
            .tilt(if (NavBus.navigating) 45.0 else 0.0)
            .padding(visible.left.toDouble(), top, 0.0, 0.0).build()), 900)
    }

    fun release() {
        runCatching { mapView?.onPause(); mapView?.onStop(); mapView?.onDestroy() }
        runCatching { presentation?.dismiss() }
        runCatching { display?.release() }
        mapView = null; presentation = null; display = null; map = null; style = null
    }
}
