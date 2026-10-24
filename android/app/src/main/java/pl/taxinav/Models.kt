package pl.taxinav

data class Maneuver(
    val type: Int,
    val instruction: String,
    val verbalAlert: String?,
    val verbalPre: String,
    val begin: Int,
    val lengthM: Int,
    val streets: List<String>,
)

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
)

data class RouteResponse(val chosen: Route, val alternatives: List<Route>)

data class Place(val label: String, val detail: String, val lat: Double, val lon: Double) {
    override fun toString() = if (detail.isBlank()) label else "$label\n$detail"
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
