package pl.taxinav

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class ApiException(msg: String) : Exception(msg)

object Prefs {
    private const val FILE = "taxinav"
    fun server(ctx: Context) = ctx.getSharedPreferences(FILE, 0).getString("server", "")!!.trimEnd('/')
    fun apiKey(ctx: Context) = ctx.getSharedPreferences(FILE, 0).getString("key", "")!!
    fun trafficLayer(ctx: Context) = ctx.getSharedPreferences(FILE, 0).getBoolean("trafficLayer", true)
    fun save(ctx: Context, server: String, key: String) =
        ctx.getSharedPreferences(FILE, 0).edit().putString("server", server.trim().trimEnd('/')).putString("key", key.trim()).apply()
    fun routeMode(ctx: Context) = ctx.getSharedPreferences(FILE, 0).getString("routeMode", "optimal")!!
    fun setRouteMode(ctx: Context, m: String) = ctx.getSharedPreferences(FILE, 0).edit().putString("routeMode", m).apply()

    /** Ostatnie cele (najnowsze pierwsze, maks. 10). */
    fun recent(ctx: Context): List<Place> = runCatching {
        val arr = JSONArray(ctx.getSharedPreferences(FILE, 0).getString("recent", "[]"))
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
        ctx.getSharedPreferences(FILE, 0).edit().putString("recent", arr.toString()).apply()
    }

    fun mapTheme(ctx: Context) = ctx.getSharedPreferences(FILE, 0).getString("mapTheme", "auto")!!
    fun setMapTheme(ctx: Context, t: String) = ctx.getSharedPreferences(FILE, 0).edit().putString("mapTheme", t).apply()
    fun setTrafficLayer(ctx: Context, on: Boolean) =
        ctx.getSharedPreferences(FILE, 0).edit().putBoolean("trafficLayer", on).apply()
}

class Api(private val server: String, private val key: String) {

    suspend fun health(): JSONObject = withContext(Dispatchers.IO) { JSONObject(request("GET", "/health", null)) }

    suspend fun route(from: LatLon, to: LatLon, heading: Float?): RouteResponse = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("from", JSONObject().put("lat", from.lat).put("lon", from.lon))
            .put("to", JSONObject().put("lat", to.lat).put("lon", to.lon))
        if (heading != null) body.put("heading", heading.toDouble())
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
                throw ApiException(
                    when (code) {
                        401 -> "Zły klucz API (Ustawienia)"
                        else -> msg ?: "Błąd serwera ($code)"
                    }
                )
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
        fun from(ctx: Context) = Api(Prefs.server(ctx), Prefs.apiKey(ctx))

        fun parseRoute(o: JSONObject): Route {
            val mans = o.getJSONArray("maneuvers")
            val tr = o.optJSONArray("traffic") ?: JSONArray()
            val kinds = o.optJSONArray("kinds") ?: JSONArray()
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
            )
        }
    }
}
