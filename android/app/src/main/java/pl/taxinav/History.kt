package pl.taxinav

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Przejechana trasa: skąd, przez co, dokąd, kiedy, ile km/min, szacowana cena. */
data class Trip(
    val from: Place, val to: Place, val stops: List<Place>,
    val startedAt: Long, val endedAt: Long, val km: Double, val fare: Double?, val arrived: Boolean,
) {
    val minutes: Int get() = ((endedAt - startedAt) / 60000).toInt()
}

object TripHistory {
    private const val KEY = "trips"
    private const val MAX = 200

    private fun placeJson(p: Place) = JSONObject().put("label", p.label).put("detail", p.detail).put("lat", p.lat).put("lon", p.lon)
    private fun place(o: JSONObject) = Place(o.optString("label"), o.optString("detail"), o.optDouble("lat"), o.optDouble("lon"))

    fun all(ctx: Context): List<Trip> = runCatching {
        val arr = JSONArray(ctx.getSharedPreferences("taxinav", 0).getString(KEY, "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            val st = o.optJSONArray("stops") ?: JSONArray()
            Trip(place(o.getJSONObject("from")), place(o.getJSONObject("to")), (0 until st.length()).map { i -> place(st.getJSONObject(i)) },
                o.getLong("startedAt"), o.getLong("endedAt"), o.optDouble("km"), if (o.has("fare")) o.optDouble("fare") else null, o.optBoolean("arrived"))
        }
    }.getOrDefault(emptyList())

    fun add(ctx: Context, t: Trip) {
        val arr = JSONArray()
        (listOf(t) + all(ctx)).take(MAX).forEach { x ->
            arr.put(JSONObject().put("from", placeJson(x.from)).put("to", placeJson(x.to))
                .put("stops", JSONArray().apply { x.stops.forEach { put(placeJson(it)) } })
                .put("startedAt", x.startedAt).put("endedAt", x.endedAt).put("km", x.km).put("arrived", x.arrived)
                .apply { if (x.fare != null) put("fare", x.fare) })
        }
        ctx.getSharedPreferences("taxinav", 0).edit().putString(KEY, arr.toString()).apply()
    }

    fun clear(ctx: Context) = ctx.getSharedPreferences("taxinav", 0).edit().remove(KEY).apply()
}

/** Ekran historii: lista kursów, „Jedź ponownie” i „Trasa powrotna”. */
class HistoryActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_LAT = "lat"; const val EXTRA_LON = "lon"; const val EXTRA_LABEL = "label"; const val EXTRA_DETAIL = "detail"
        const val EXTRA_STOPS = "stops"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)
        render()
        findViewById<MaterialButton>(R.id.clearHistory).setOnClickListener {
            AlertDialog.Builder(this).setTitle("Wyczyścić historię tras?")
                .setPositiveButton("Wyczyść") { _, _ -> TripHistory.clear(this); render() }
                .setNegativeButton("Anuluj", null).show()
        }
    }

    private fun render() {
        val trips = TripHistory.all(this)
        val pl = Locale.forLanguageTag("pl-PL")
        val day = SimpleDateFormat("EEE d.MM", pl); val time = SimpleDateFormat("HH:mm", pl)
        val today = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0) }.timeInMillis
        val todayTrips = trips.filter { it.startedAt >= today }
        findViewById<TextView>(R.id.historySummary).text = if (trips.isEmpty()) "Tu pojawią się przejechane trasy."
        else "Dziś: ${todayTrips.size} tras · ${"%.1f".format(pl, todayTrips.sumOf { it.km })} km" +
            (todayTrips.mapNotNull { it.fare }.takeIf { it.isNotEmpty() }?.let { " · ≈ ${Fare.zlShort(it.sum())}" } ?: "")
        val rows = trips.map { t ->
            val via = if (t.stops.isEmpty()) "" else " → " + t.stops.joinToString(" → ") { it.label }
            val whenTxt = (if (t.startedAt >= today) "dziś" else day.format(Date(t.startedAt))) + " " + time.format(Date(t.startedAt))
            "${t.from.label}$via → ${t.to.label}\n$whenTxt · ${"%.1f".format(pl, t.km)} km · ${t.minutes} min" +
                (t.fare?.let { " · ≈ ${Fare.zlShort(it)}" } ?: "") + if (!t.arrived) " · przerwana" else ""
        }
        val list = findViewById<ListView>(R.id.historyList)
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, rows)
        list.setOnItemClickListener { _, _, pos, _ ->
            val t = trips[pos]
            AlertDialog.Builder(this)
                .setTitle("${t.from.label} → ${t.to.label}")
                .setItems(arrayOf("🔁  Jedź ponownie do: ${t.to.label}", "↩  Trasa powrotna do: ${t.from.label}")) { _, which ->
                    val dest = if (which == 0) t.to else t.from
                    val stops = if (which == 0) t.stops else t.stops.reversed()
                    setResult(Activity.RESULT_OK, Intent()
                        .putExtra(EXTRA_LAT, dest.lat).putExtra(EXTRA_LON, dest.lon)
                        .putExtra(EXTRA_LABEL, dest.label).putExtra(EXTRA_DETAIL, dest.detail)
                        .putExtra(EXTRA_STOPS, JSONArray().apply { stops.forEach { put(JSONObject().put("label", it.label).put("lat", it.lat).put("lon", it.lon)) } }.toString()))
                    finish()
                }
                .setNegativeButton("Anuluj", null).show()
        }
    }
}
