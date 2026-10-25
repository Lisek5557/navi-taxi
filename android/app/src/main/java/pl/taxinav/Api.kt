package pl.taxinav

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

class ApiException(msg: String, val code: Int = 0) : Exception(msg)

/** Ulubiony cel kierowcy (każdy telefon ma swoje). */
data class Favorite(val name: String, val label: String, val lat: Double, val lon: Double)

object Prefs {
    private const val FILE = "taxinav"
    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, 0)

    fun server(ctx: Context) = sp(ctx).getString("server", "")!!.trimEnd('/')
    fun apiKey(ctx: Context) = sp(ctx).getString("key", "")!!
    fun trafficLayer(ctx: Context) = sp(ctx).getBoolean("trafficLayer", true)
    fun save(ctx: Context, server: String, key: String) =
        sp(ctx).edit().putString("server", server.trim().trimEnd('/')).putString("key", key.trim()).apply()
    fun routeMode(ctx: Context) = sp(ctx).getString("routeMode", "optimal")!!
    fun setRouteMode(ctx: Context, m: String) = sp(ctx).edit().putString("routeMode", m).apply()
    fun mapTheme(ctx: Context) = sp(ctx).getString("mapTheme", "auto")!!
    fun setMapTheme(ctx: Context, t: String) = sp(ctx).edit().putString("mapTheme", t).apply()
    fun setTrafficLayer(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean("trafficLayer", on).apply()

    // ---- Konto kierowcy ----
    fun userToken(ctx: Context) = sp(ctx).getString("userToken", "")!!
    fun userName(ctx: Context) = sp(ctx).getString("userName", "")!!
    fun userRole(ctx: Context) = sp(ctx).getString("userRole", "")!!
    fun isAdmin(ctx: Context) = userRole(ctx) == "admin"
    fun setUser(ctx: Context, token: String, name: String, role: String) =
        sp(ctx).edit().putString("userToken", token).putString("userName", name).putString("userRole", role).apply()

    /** Losowy, anonimowy identyfikator telefonu (do próbek prędkości). */
    fun deviceId(ctx: Context): String {
        val cur = sp(ctx).getString("devId", "")!!
        if (cur.isNotEmpty()) return cur
        val id = UUID.randomUUID().toString().take(12)
        sp(ctx).edit().putString("devId", id).apply()
        return id
    }

    // ---- Ostatnie cele ----
    fun recent(ctx: Context): List<Place> = runCatching {
        val arr = JSONArray(sp(ctx).getString("recent", "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Place(o.getString("label"), o.optString("detail"), o.getDouble("lat"), o.getDouble("lon"))
        }
    }.getOrDefault(emptyList())

    fun addRecent(ctx: Context, p: Place) {
        val list = listOf(p) + recent(ctx).filter { it.label != p.label || it.detail != p.detail }
        val arr = JSONArray()
        list.take(10).forEach {
            arr.put(JSONObject().put("label", it.label).put("detail", it.detail).put("lat", it.lat).put("lon", it.lon))
        }
        sp(ctx).edit().putString("recent", arr.toString()).apply()
    }

    // ---- Szybkie cele ----
    fun favorites(ctx: Context): List<Favorite> = runCatching {
        val arr = JSONArray(sp(ctx).getString("favorites", "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Favorite(o.getString("name"), o.optString("label"), o.getDouble("lat"), o.getDouble("lon"))
        }
    }.getOrDefault(emptyList())

    fun saveFavorites(ctx: Context, list: List<Favorite>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("name", it.name).put("label", it.label).put("lat", it.lat).put("lon", it.lon)) }
        sp(ctx).edit().putString("favorites", arr.toString()).apply()
    }
}

class Api(private val server: String, private val key: String, private val userToken: String = "") {

    suspend fun health(): JSONObject = withContext(Dispatchers.IO) { JSONObject(request("GET", "/health", null)) }

    suspend fun route(from: LatLon, to: LatLon, heading: Float?, via: List<LatLon> = emptyList()): RouteResponse = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("from", JSONObject().put("lat", from.lat).put("lon", from.lon))
            .put("to", JSONObject().put("lat", to.lat).put("lon", to.lon))
        if (heading != null) body.put("heading", heading.toDouble())
        if (via.isNotEmpty()) body.put("via", JSONArray().apply { via.forEach { put(JSONObject().put("lat", it.lat).put("lon", it.lon)) } })
        val j = JSONObject(request("POST", "/route", body.toString()))
        val alts = j.optJSONArray("alternatives") ?: JSONArray()
        val chosen = parseRoute(j.getJSONObject("chosen"))
        val alternatives = (0 until alts.length()).map { parseRoute(alts.getJSONObject(it)) }
        val arr = j.optJSONArray("routes")
        val pickObj = j.optJSONObject("pick")
        if (arr != null && pickObj != null && arr.length() > 0) {
            val routes = (0 until arr.length()).map { parseRoute(arr.getJSONObject(it)) }
            val pick = RouteModes.ALL.associateWith { pickObj.optInt(it, 0).coerceIn(0, routes.size - 1) }
            val c = routes[pick.getValue("optimal")]
            RouteResponse(c, routes.filter { it !== c }, routes, pick)
        } else RouteResponse(chosen, alternatives)
    }

    suspend fun geocode(q: String, near: LatLon?, fast: Boolean = false): List<Place> = withContext(Dispatchers.IO) {
        var path = "/geocode?q=" + URLEncoder.encode(q, "UTF-8") + (if (fast) "&fast=1" else "")
        if (near != null) path += "&lat=${near.lat}&lon=${near.lon}"
        val arr = JSONObject(request("GET", path, null)).getJSONArray("results")
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Place(o.getString("label"), o.optString("detail"), o.getDouble("lat"), o.getDouble("lon"))
        }
    }

    // ---- Konto ----
    suspend fun register(name: String): JSONObject = withContext(Dispatchers.IO) {
        JSONObject(request("POST", "/register", JSONObject().put("name", name).toString()))
    }
    suspend fun me(): JSONObject = withContext(Dispatchers.IO) { JSONObject(request("GET", "/me", null)).getJSONObject("user") }

    // ---- Zgłoszenia ----
    suspend fun reports(near: LatLon?): List<Report> = withContext(Dispatchers.IO) {
        val path = "/reports" + (near?.let { "?lat=${it.lat}&lon=${it.lon}" } ?: "")
        val arr = JSONObject(request("GET", path, null)).getJSONArray("reports")
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Report(o.getInt("id"), o.getString("type"), o.getString("label"), o.getDouble("lat"), o.getDouble("lon"),
                o.optInt("up"), o.optInt("down"), o.optInt("ageMin"))
        }
    }
    suspend fun addReport(type: String, pos: LatLon, heading: Float?) = withContext(Dispatchers.IO) {
        val b = JSONObject().put("type", type).put("lat", pos.lat).put("lon", pos.lon)
        if (heading != null) b.put("heading", heading.toDouble())
        request("POST", "/reports", b.toString()); Unit
    }
    suspend fun voteReport(id: Int, stillThere: Boolean) = withContext(Dispatchers.IO) {
        request("POST", "/reports/$id/vote", JSONObject().put("stillThere", stillThere).toString()); Unit
    }

    // ---- Ograniczenia prędkości ----
    suspend fun reportLimit(pos: LatLon, heading: Float?, value: Int, note: String): String = withContext(Dispatchers.IO) {
        val b = JSONObject().put("lat", pos.lat).put("lon", pos.lon).put("value", value).put("note", note)
        if (heading != null) b.put("heading", heading.toDouble())
        JSONObject(request("POST", "/limits/report", b.toString())).getString("status")
    }
    suspend fun pendingLimits(): JSONArray = withContext(Dispatchers.IO) {
        JSONObject(request("GET", "/limits/pending", null)).getJSONArray("pending")
    }
    suspend fun decideLimit(id: Int, approve: Boolean) = withContext(Dispatchers.IO) {
        request("POST", "/limits/$id/decide", JSONObject().put("approve", approve).toString()); Unit
    }
    /** Ograniczenie w miejscu, gdzie jedziesz (bez wyznaczonej trasy) — z ostatnich punktów GPS. */
    suspend fun limitAt(points: List<LatLon>): Int? = withContext(Dispatchers.IO) {
        val arr = JSONArray().apply { points.forEach { put(JSONArray().put(it.lat).put(it.lon)) } }
        val j = JSONObject(request("POST", "/limit/at", JSONObject().put("points", arr).toString()))
        if (j.isNull("limit")) null else j.getInt("limit")
    }

    /** Fotoradary w promieniu ~30 km (ostrzeżenia bez wyznaczonej trasy). */
    suspend fun camerasNear(p: LatLon): List<Camera> = withContext(Dispatchers.IO) {
        val arr = JSONObject(request("GET", "/cameras?lat=${p.lat}&lon=${p.lon}&r=30", null)).optJSONArray("cameras") ?: JSONArray()
        (0 until arr.length()).map { i -> arr.getJSONObject(i).let { c ->
            Camera(-1, c.optString("type", "fixed"), if (c.isNull("maxspeed")) null else c.optInt("maxspeed"), c.getDouble("lat"), c.getDouble("lon")) } }
    }

    /** Najbliższy adres (do historii tras: „skąd”). */
    suspend fun reverse(p: LatLon): String? = withContext(Dispatchers.IO) {
        val j = JSONObject(request("GET", "/reverse?lat=${p.lat}&lon=${p.lon}", null))
        val a = j.optJSONObject("address")
        a?.optString("label")?.takeIf { it.isNotBlank() } ?: j.optString("city").takeIf { it.isNotBlank() && it != "null" }
    }

    /** Strefy rabatowe (GeoJSON) do pokazania na mapie. */
    suspend fun discountZones(): String = withContext(Dispatchers.IO) { request("GET", "/fare/zones", null) }

    // ---- Korki od kierowców ----
    suspend fun sendSpeed(dev: String, samples: JSONArray) = withContext(Dispatchers.IO) {
        request("POST", "/speed", JSONObject().put("dev", dev).put("samples", samples).toString()); Unit
    }

    private fun request(method: String, path: String, body: String?): String {
        if (server.isBlank()) throw ApiException("Ustaw adres serwera w Ustawieniach")
        val conn = try {
            URL(server + path).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw ApiException("Zły adres serwera: $server")
        }
        try {
            conn.requestMethod = method
            conn.connectTimeout = 8000
            conn.readTimeout = 25000
            conn.setRequestProperty("X-Api-Key", key)
            if (userToken.isNotEmpty()) conn.setRequestProperty("X-User-Token", userToken)
            conn.setRequestProperty("Accept", "application/json")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(text).getString("error") }.getOrNull()
                throw ApiException(msg ?: if (code == 401) "Zły klucz API (Ustawienia)" else "Błąd serwera ($code)", code)
            }
            return text
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            throw ApiException("Brak połączenia z serwerem: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        fun from(ctx: Context) = Api(Prefs.server(ctx), Prefs.apiKey(ctx), Prefs.userToken(ctx))

        fun parseRoute(o: JSONObject): Route {
            val mans = o.getJSONArray("maneuvers")
            val tr = o.optJSONArray("traffic") ?: JSONArray()
            val kinds = o.optJSONArray("kinds") ?: JSONArray()
            val lim = o.optJSONArray("limits") ?: JSONArray()
            val ln = o.optJSONArray("lanes") ?: JSONArray()
            return Route(
                points = Polyline.decode(o.getString("shape")),
                maneuvers = (0 until mans.length()).map {
                    val m = mans.getJSONObject(it)
                    val streets = m.optJSONArray("streets") ?: JSONArray()
                    Maneuver(
                        type = m.optInt("type"),
                        instruction = m.optString("instruction"),
                        verbalAlert = m.optString("verbalAlert").takeIf { s -> s.isNotBlank() && s != "null" },
                        verbalPre = m.optString("verbalPre", m.optString("instruction")),
                        begin = m.getInt("begin"),
                        lengthM = m.optInt("lengthM"),
                        streets = (0 until streets.length()).map { i -> streets.getString(i) },
                        stop = if (m.has("stop") && !m.isNull("stop")) m.getInt("stop") else null,
                    )
                },
                distanceM = o.getInt("distance"),
                baseTimeS = o.getInt("baseTime"),
                trafficTimeS = o.optInt("trafficTime", o.getInt("baseTime")),
                hasTraffic = o.optBoolean("hasTraffic"),
                traffic = (0 until tr.length()).map {
                    val t = tr.getJSONObject(it)
                    TrafficRange(t.getInt("from"), t.getInt("to"), t.getInt("level"))
                },
                kinds = (0 until kinds.length()).map { kinds.getString(it) },
                limits = (0 until lim.length()).map {
                    val l = lim.getJSONObject(it)
                    LimitRange(l.getInt("from"), l.getInt("to"), l.getInt("limit"), l.optBoolean("override"))
                },
                lanes = (0 until ln.length()).map {
                    val l = ln.getJSONObject(it)
                    val la = l.getJSONArray("lanes")
                    LaneInfo(l.getInt("idx"), (0 until la.length()).map { k ->
                        val x = la.getJSONObject(k)
                        val d = x.optJSONArray("d") ?: JSONArray()
                        Lane((0 until d.length()).map { i -> d.getString(i) }, x.optBoolean("ok"))
                    })
                },
                stops = o.optInt("stops"),
                cameras = (o.optJSONArray("cameras") ?: JSONArray()).let { arr ->
                    (0 until arr.length()).map { i -> arr.getJSONObject(i).let { c ->
                        Camera(c.getInt("idx"), c.optString("type", "fixed"), if (c.isNull("maxspeed")) null else c.optInt("maxspeed")) } }
                },
                sections = (o.optJSONArray("sections") ?: JSONArray()).let { arr ->
                    (0 until arr.length()).map { i -> arr.getJSONObject(i).let { c ->
                        SpeedSection(c.getInt("fromIdx"), c.getInt("toIdx"), c.optInt("lengthM"),
                            if (c.isNull("maxspeed")) null else c.optInt("maxspeed"), c.optString("name").takeIf { it.isNotBlank() && it != "null" }) } }
                },
                fare = o.optJSONObject("fare")?.let { f ->
                    val flat = f.optJSONObject("flat")
                    Fare(
                        total = f.optDouble("total"), before = f.optDouble("before"), startFee = f.optDouble("startFee"),
                        cityKm = f.optDouble("cityKm"), outKm = f.optDouble("outKm"), cityCost = f.optDouble("cityCost"), outCost = f.optDouble("outCost"),
                        trafficMin = f.optInt("trafficMin"), trafficCost = f.optDouble("trafficCost"),
                        night = f.optBoolean("night"), tariffCity = f.optInt("tariffCity", 1), tariffOut = f.optInt("tariffOut", 3),
                        discountPct = f.optInt("discountPct"), discountReason = f.optString("discountReason").takeIf { it.isNotBlank() && it != "null" },
                        flatCity = flat?.optString("city"), flatValue = flat?.optDouble("value"),
                        districtA = f.optString("districtA").takeIf { it.isNotBlank() && it != "null" },
                        districtB = f.optString("districtB").takeIf { it.isNotBlank() && it != "null" },
                        tariffName = f.optString("tariffName"),
                    )
                },
            )
        }
    }
}
