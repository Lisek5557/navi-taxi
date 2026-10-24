package pl.taxinav

import kotlin.math.*

data class LatLon(val lat: Double, val lon: Double)

object Geo {
    private const val R = 6371000.0

    fun distance(a: LatLon, b: LatLon): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val s = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
        return 2 * R * asin(min(1.0, sqrt(s)))
    }

    /** Rzut punktu p na odcinek a-b. Zwraca (odległość od odcinka w m, t w [0,1]). */
    fun projectOnSegment(p: LatLon, a: LatLon, b: LatLon): Pair<Double, Double> {
        val kx = cos(Math.toRadians(p.lat)) * 111320.0
        val ky = 110540.0
        val ax = (a.lon - p.lon) * kx; val ay = (a.lat - p.lat) * ky
        val bx = (b.lon - p.lon) * kx; val by = (b.lat - p.lat) * ky
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else ((-ax * dx - ay * dy) / len2).coerceIn(0.0, 1.0)
        val cx = ax + t * dx; val cy = ay + t * dy
        return Pair(sqrt(cx * cx + cy * cy), t)
    }

    fun cumulative(points: List<LatLon>): DoubleArray {
        val c = DoubleArray(points.size)
        for (i in 1 until points.size) c[i] = c[i - 1] + distance(points[i - 1], points[i])
        return c
    }
}

object Polyline {
    /** Dekoduje polyline z precyzją 6 (format Valhalli). */
    fun decode(str: String, precision: Int = 6): List<LatLon> {
        val factor = 10.0.pow(precision)
        val out = ArrayList<LatLon>()
        var index = 0; var lat = 0L; var lon = 0L
        while (index < str.length) {
            var result = 0L; var shift = 0; var b: Int
            do {
                b = str[index++].code - 63
                result = result or ((b and 0x1f).toLong() shl shift)
                shift += 5
            } while (b >= 0x20)
            lat += if (result and 1L != 0L) (result shr 1).inv() else (result shr 1)
            result = 0L; shift = 0
            do {
                b = str[index++].code - 63
                result = result or ((b and 0x1f).toLong() shl shift)
                shift += 5
            } while (b >= 0x20)
            lon += if (result and 1L != 0L) (result shr 1).inv() else (result shr 1)
            out.add(LatLon(lat / factor, lon / factor))
        }
        return out
    }
}
