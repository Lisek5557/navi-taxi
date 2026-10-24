package pl.taxinav

import java.net.URLDecoder

/** Cel otrzymany z innej aplikacji: współrzędne albo tekst do wyszukania. */
sealed class ExternalTarget {
    data class Point(val pos: LatLon, val label: String?) : ExternalTarget()
    data class Query(val text: String) : ExternalTarget()
}

/**
 * Rozpoznaje linki „otwórz mapę / nawiguj” z innych aplikacji:
 *  geo:53.1,18.0 · geo:0,0?q=53.1,18.0(Dworzec) · geo:0,0?q=Gdańska 12 Bydgoszcz
 *  google.navigation:q=53.1,18.0 · google.navigation:q=adres
 *  https://www.google.com/maps/dir/?api=1&destination=… · /maps/search/?api=1&query=…
 *  https://maps.google.com/?q=… / ?daddr=… · /maps/place/Nazwa/@53.1,18.0,17z
 * Krótkich linków maps.app.goo.gl nie da się rozpoznać bez pobrania — zwraca null.
 */
object ExternalLinks {
    private val COORD = Regex("""^\s*(-?\d{1,2}(?:\.\d+)?)\s*,\s*(-?\d{1,3}(?:\.\d+)?)\s*(?:\((.*)\))?\s*$""")
    private val AT = Regex("""@(-?\d{1,2}\.\d+),(-?\d{1,3}\.\d+)""")
    private val BANG = Regex("""!3d(-?\d{1,2}\.\d+)!4d(-?\d{1,3}\.\d+)""")

    fun parse(raw: String?): ExternalTarget? {
        if (raw.isNullOrBlank()) return null
        val s = raw.trim()
        val lower = s.lowercase()
        return when {
            lower.startsWith("geo:") -> parseGeo(s.substring(4))
            lower.startsWith("google.navigation:") -> fromText(param(s.substringAfter(':'), "q") ?: s.substringAfter(':'))
            lower.startsWith("http://") || lower.startsWith("https://") -> parseHttp(s)
            else -> fromText(s)
        }
    }

    private fun parseGeo(rest: String): ExternalTarget? {
        val path = rest.substringBefore('?')
        val q = if ('?' in rest) param(rest.substringAfter('?'), "q") else null
        if (!q.isNullOrBlank()) return fromText(q)
        val m = COORD.find(path.substringBefore(';')) ?: return null
        val lat = m.groupValues[1].toDouble(); val lon = m.groupValues[2].toDouble()
        if (lat == 0.0 && lon == 0.0) return null
        return ExternalTarget.Point(LatLon(lat, lon), null)
    }

    private fun parseHttp(url: String): ExternalTarget? {
        val query = url.substringAfter('?', "")
        for (key in listOf("destination", "daddr", "query", "q", "ll")) {
            param(query, key)?.takeIf { it.isNotBlank() }?.let { v -> return fromText(v) }
        }
        BANG.find(url)?.let { return ExternalTarget.Point(LatLon(it.groupValues[1].toDouble(), it.groupValues[2].toDouble()), placeName(url)) }
        AT.find(url)?.let { m ->
            val name = placeName(url)
            val p = LatLon(m.groupValues[1].toDouble(), m.groupValues[2].toDouble())
            return ExternalTarget.Point(p, name)
        }
        placeName(url)?.let { return ExternalTarget.Query(it) }
        return null
    }

    private fun placeName(url: String): String? {
        val seg = Regex("""/maps/(?:place|search)/([^/@?]+)""").find(url)?.groupValues?.get(1) ?: return null
        return decode(seg.replace('+', ' ')).trim().takeIf { it.isNotEmpty() && COORD.find(it) == null }
    }

    private fun fromText(t: String): ExternalTarget? {
        val text = decode(t).trim()
        if (text.isEmpty()) return null
        COORD.find(text)?.let { m ->
            return ExternalTarget.Point(LatLon(m.groupValues[1].toDouble(), m.groupValues[2].toDouble()), m.groupValues.getOrNull(3)?.takeIf { it.isNotBlank() })
        }
        return ExternalTarget.Query(text)
    }

    private fun param(query: String, key: String): String? =
        query.split('&').firstOrNull { it.substringBefore('=').equals(key, true) && '=' in it }?.substringAfter('=')

    private fun decode(s: String) = try { URLDecoder.decode(s, "UTF-8") } catch (_: Exception) { s }
}
