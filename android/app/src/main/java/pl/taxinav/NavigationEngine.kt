package pl.taxinav

import kotlin.math.max
import kotlin.math.roundToInt

data class NavState(
    val distanceFromRouteM: Double,
    val offRoute: Boolean,
    val arrived: Boolean,
    val nextManeuver: Maneuver?,
    val distanceToNextM: Double,
    val remainingM: Double,
    val remainingS: Double,
    val snapped: LatLon,
    val traveledIndex: Int,
    /** Obowiązujące ograniczenie prędkości (km/h) albo null. */
    val speedLimit: Int? = null,
    /** Pasy przed najbliższym skrzyżowaniem (w zasięgu 350 m) i odległość do niego. */
    val lanes: LaneInfo? = null,
    val distanceToLanesM: Double = 0.0,
    val alongM: Double = 0.0,
)

/**
 * Silnik nawigacji zakręt po zakręcie (bez zależności od Androida — testowalny).
 * Valhalla: manewr k opisuje akcję w punkcie `begin` i jazdę do początku manewru k+1.
 */
class NavigationEngine(
    val route: Route,
    private val speak: (String) -> Unit,
    announceStart: Boolean = true,
) {
    private val pts = route.points
    private val cum = Geo.cumulative(pts)
    private val total = max(1.0, cum.last())
    private val manStart = route.maneuvers.map { cum[it.begin.coerceIn(0, pts.size - 1)] }
    private var lastSeg = 0
    private var offCount = 0
    private val saidFar = HashSet<Int>()
    private val saidNear = HashSet<Int>()
    private var saidArrived = false
    private var startPending = announceStart

    fun update(pos: LatLon, speedMps: Double = 0.0, accuracyM: Double = 10.0): NavState {
        // 1. Najbliższy odcinek — najpierw w oknie wokół poprzedniej pozycji, potem globalnie.
        var best = nearest(pos, max(0, lastSeg - 5), minOf(pts.size - 2, lastSeg + 80))
        if (best.dist > 60) {
            val global = nearest(pos, 0, pts.size - 2)
            if (global.dist < best.dist) best = global
        }
        lastSeg = best.seg
        val along = cum[best.seg] + best.t * (cum[best.seg + 1] - cum[best.seg])

        // 2. Zjechanie z trasy: 3 kolejne odczyty dalej niż próg.
        val threshold = max(40.0, accuracyM * 1.5)
        offCount = if (best.dist > threshold) offCount + 1 else 0
        val offRoute = offCount >= 3

        // 3. Aktualny i następny manewr.
        var cur = 0
        for (k in manStart.indices) if (manStart[k] <= along + 5) cur = k
        val nextIdx = cur + 1
        val next = route.maneuvers.getOrNull(nextIdx)
        val distToNext = if (next != null) max(0.0, manStart[nextIdx] - along) else 0.0
        val remaining = max(0.0, total - along)
        val remainingS = route.trafficTimeS * remaining / total
        val arrived = remaining < 30 || (next == null && remaining < 60)

        // 4. Komunikaty głosowe.
        if (startPending) {
            startPending = false
            route.maneuvers.firstOrNull()?.let { speak(it.verbalPre) }
        }
        if (arrived) {
            if (!saidArrived) {
                saidArrived = true
                speak(route.maneuvers.lastOrNull()?.verbalPre ?: "Dotarłeś do celu.")
            }
        } else if (next != null && !offRoute) {
            val farDist = if (speedMps > 22) 1500.0 else if (speedMps > 14) 800.0 else 400.0
            val nearDist = max(60.0, speedMps * 7)
            val isDestination = next.type in 4..6 && next.stop == null
            if (distToNext <= nearDist && nextIdx !in saidNear) {
                saidNear += nextIdx; saidFar += nextIdx
                if (!isDestination) speak(next.verbalPre)
            } else if (distToNext <= farDist && distToNext > nearDist + 100 && nextIdx !in saidFar) {
                saidFar += nextIdx
                speak("${Speech.inDistance(distToNext)}, ${Speech.lowerFirst(next.verbalAlert ?: next.verbalPre)}")
            }
        }

        val limit = route.limits.firstOrNull { best.seg >= it.from && best.seg <= it.to }?.limit
        val lane = route.lanes.firstOrNull { cum[it.index.coerceIn(0, pts.size - 1)] >= along - 5 }
        val laneDist = lane?.let { cum[it.index.coerceIn(0, pts.size - 1)] - along } ?: 0.0
        val laneShown = if (lane != null && laneDist <= 350) lane else null

        val snapped = LatLon(
            pts[best.seg].lat + best.t * (pts[best.seg + 1].lat - pts[best.seg].lat),
            pts[best.seg].lon + best.t * (pts[best.seg + 1].lon - pts[best.seg].lon),
        )
        return NavState(best.dist, offRoute, arrived, next, distToNext, remaining, remainingS, snapped, best.seg,
            limit, laneShown, laneDist, along)
    }

    /** Punkt na trasie w odległości `along` m od początku (do kierowania kamery „przed siebie”). */
    fun pointAt(along: Double): LatLon {
        val a = along.coerceIn(0.0, total)
        var i = 0
        while (i < cum.size - 2 && cum[i + 1] < a) i++
        val seg = cum[i + 1] - cum[i]
        val t = if (seg <= 0) 0.0 else (a - cum[i]) / seg
        return LatLon(pts[i].lat + t * (pts[i + 1].lat - pts[i].lat), pts[i].lon + t * (pts[i + 1].lon - pts[i].lon))
    }

    /** Dystans od początku trasy do punktu kształtu o danym indeksie. */
    fun alongAt(index: Int): Double = cum[index.coerceIn(0, cum.size - 1)]

    /** Pozostała część trasy (do podglądu z góry). */
    fun remainingPoints(fromIndex: Int): List<LatLon> = pts.subList(fromIndex.coerceIn(0, pts.size - 1), pts.size)

    /** Rzut dowolnego punktu na trasę: (dystans wzdłuż trasy w m, odległość od trasy w m). */
    fun project(p: LatLon): Pair<Double, Double> {
        val h = nearest(p, 0, pts.size - 2)
        if (pts.size < 2) return Pair(0.0, h.dist)
        return Pair(cum[h.seg] + h.t * (cum[h.seg + 1] - cum[h.seg]), h.dist)
    }

    private data class Hit(val seg: Int, val dist: Double, val t: Double)

    private fun nearest(p: LatLon, from: Int, to: Int): Hit {
        if (pts.size < 2) return Hit(0, if (pts.isEmpty()) 0.0 else Geo.distance(p, pts[0]), 0.0)
        var best = Hit(from, Double.MAX_VALUE, 0.0)
        for (i in from..to) {
            val (d, t) = Geo.projectOnSegment(p, pts[i], pts[i + 1])
            if (d < best.dist) best = Hit(i, d, t)
        }
        return best
    }
}

object Speech {
    /** "Za 300 metrów", "Za 1 kilometr", "Za półtora kilometra", "Za 2 kilometry", "Za 5 kilometrów". */
    fun inDistance(m: Double): String {
        if (m < 1000) {
            val step = if (m > 300) 100 else 50
            val r = max(step, ((m / step).roundToInt() * step))
            return if (r >= 1000) "Za 1 kilometr" else "Za $r metrów"
        }
        val halfKm = (m / 500).roundToInt() / 2.0
        if (halfKm == 1.5) return "Za półtora kilometra"
        if (halfKm % 1.0 != 0.0) return "Za ${halfKm.toString().replace('.', ',')} kilometra"
        val n = halfKm.toInt()
        return "Za $n " + when {
            n == 1 -> "kilometr"
            n % 10 in 2..4 && n % 100 !in 12..14 -> "kilometry"
            else -> "kilometrów"
        }
    }

    fun lowerFirst(s: String) = if (s.isEmpty()) s else s[0].lowercaseChar() + s.substring(1)

    /** Na ekran: "350 m", "1,2 km". */
    fun short(m: Double): String = when {
        m < 1000 -> "${(m / 10).roundToInt() * 10} m"
        m < 10000 -> String.format(java.util.Locale.forLanguageTag("pl-PL"), "%.1f km", m / 1000)
        else -> "${(m / 1000).roundToInt()} km"
    }
}

/** Parametry kamery nawigacji: zoom wg prędkości i bliskości manewru, kierunek wg trasy. */
object NavCamera {
    /** Docelowy zoom: ok. 17,5 przy postoju, 16,4 przy 50 km/h, 15,6 przy 90, 14,9 przy 130 km/h; bliżej przed skrętem. */
    fun zoomFor(speedKmh: Double, distToManeuverM: Double): Double {
        val v = speedKmh.coerceIn(0.0, 140.0)
        val stops = listOf(0.0 to 17.5, 20.0 to 17.2, 50.0 to 16.4, 90.0 to 15.6, 130.0 to 14.9, 140.0 to 14.8)
        var z = stops.last().second
        for (k in 0 until stops.size - 1) {
            val (v0, z0) = stops[k]; val (v1, z1) = stops[k + 1]
            if (v <= v1) { z = z0 + (z1 - z0) * (v - v0) / (v1 - v0); break }
        }
        val near = when {
            distToManeuverM < 60 -> 0.8
            distToManeuverM < 200 -> 0.8 * (200 - distToManeuverM) / 140
            else -> 0.0
        }
        return minOf(18.0, z + near)
    }

    /** Ile metrów przed autem patrzy kamera (dalej przy większej prędkości). */
    fun lookAheadM(speedKmh: Double) = (25.0 + speedKmh * 0.9).coerceIn(25.0, 140.0)

    fun bearing(a: LatLon, b: LatLon): Double {
        val la1 = Math.toRadians(a.lat); val la2 = Math.toRadians(b.lat); val dl = Math.toRadians(b.lon - a.lon)
        val y = Math.sin(dl) * Math.cos(la2)
        val x = Math.cos(la1) * Math.sin(la2) - Math.sin(la1) * Math.cos(la2) * Math.cos(dl)
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360
    }

    /** Płynne przejście kąta (najkrótszą drogą przez 0/360). */
    fun smoothAngle(from: Double, to: Double, k: Double): Double {
        val d = ((to - from + 540) % 360) - 180
        return (from + d * k + 360) % 360
    }
}
