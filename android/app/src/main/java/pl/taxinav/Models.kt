package pl.taxinav

data class Maneuver(
    val type: Int,
    val instruction: String,
    val verbalAlert: String?,
    val verbalPre: String,
    val begin: Int,
    val lengthM: Int,
    val streets: List<String>,
    /** Numer przystanku pośredniego (null = zwykły manewr lub cel końcowy). */
    val stop: Int? = null,
)

/** Ograniczenie prędkości na zakresie punktów kształtu. */
data class LimitRange(val from: Int, val to: Int, val limit: Int, val override: Boolean = false)

/** Pas ruchu: kierunki ze znaków poziomych i czy prowadzi zgodnie z trasą. */
data class Lane(val directions: List<String>, val ok: Boolean)
data class LaneInfo(val index: Int, val lanes: List<Lane>)

/** Zgłoszenie kierowców (jak w Yanosiku). */
data class Report(val id: Int, val type: String, val label: String, val lat: Double, val lon: Double, val up: Int, val down: Int, val ageMin: Int)

/** Zakres punktów kształtu trasy z poziomem korka: -1 brak danych, 0 płynnie ... 3 stoi. */
data class TrafficRange(val from: Int, val to: Int, val level: Int)

data class Route(
    val points: List<LatLon>,
    val maneuvers: List<Maneuver>,
    val distanceM: Int,
    val baseTimeS: Int,
    val trafficTimeS: Int,
    val hasTraffic: Boolean,
    val traffic: List<TrafficRange>,
    val kinds: List<String>,
    val limits: List<LimitRange> = emptyList(),
    val lanes: List<LaneInfo> = emptyList(),
    val stops: Int = 0,
)

data class RouteResponse(
    val chosen: Route,
    val alternatives: List<Route>,
    val routes: List<Route> = listOf(chosen) + alternatives,
    /** Indeksy w [routes] dla trybów: "fastest", "shortest", "optimal". */
    val pick: Map<String, Int> = emptyMap(),
) {
    fun routeFor(mode: String): Route = routes.getOrNull(pick[mode] ?: -1) ?: chosen

    /** Ta sama odpowiedź, ale z trasą wybranego trybu jako główną. */
    fun withMode(mode: String): RouteResponse {
        val c = routeFor(mode)
        return copy(chosen = c, alternatives = routes.filter { it !== c })
    }
}

object RouteModes {
    val ALL = listOf("optimal", "fastest", "shortest")
    fun label(mode: String) = when (mode) {
        "fastest" -> "Najszybsza"
        "shortest" -> "Najkrótsza"
        else -> "Optymalna"
    }
}

data class Place(val label: String, val detail: String, val lat: Double, val lon: Double) {
    override fun toString() = if (detail.isBlank()) label else "$label\n$detail"
}

object Lanes {
    /** Strzałka dla kierunku pasa (OSRM: left, slight left, straight, …). */
    fun arrow(dir: String): String = when (dir) {
        "left" -> "←"; "slight left" -> "↖"; "sharp left" -> "↙"
        "right" -> "→"; "slight right" -> "↗"; "sharp right" -> "↘"
        "uturn" -> "↶"; "straight" -> "↑"; "merge" -> "⇡"
        else -> "↑"
    }
    fun label(l: Lane): String = l.directions.ifEmpty { listOf("straight") }.joinToString("") { arrow(it) }
}

object Maneuvers {
    /** Strzałka dla typu manewru Valhalli. */
    fun arrow(type: Int): String = when (type) {
        1, 2, 3, 8, 17, 22, 25 -> "↑"
        4, 5, 6 -> "🏁"
        9, 20, 23 -> "↗"
        10, 18 -> "→"
        11 -> "↘"
        12, 13 -> "↶"
        14 -> "↙"
        15, 19 -> "←"
        16, 21, 24 -> "↖"
        26, 27 -> "⟳"
        else -> "↑"
    }
}
