package pl.taxinav

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Admin: zatwierdzanie zgłoszonych zmian ograniczeń prędkości. */
class AdminActivity : AppCompatActivity() {
    private lateinit var list: LinearLayout
    private lateinit var empty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin)
        list = findViewById(R.id.pendingList)
        empty = findViewById(R.id.emptyText)
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            try {
                val arr = Api.from(this@AdminActivity).pendingLimits()
                list.removeAllViews()
                empty.visibility = if (arr.length() == 0) TextView.VISIBLE else TextView.GONE
                val fmt = SimpleDateFormat("dd.MM HH:mm", Locale.forLanguageTag("pl-PL"))
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val id = o.getInt("id")
                    val lat = o.getDouble("lat"); val lon = o.getDouble("lon")
                    val row = LinearLayout(this@AdminActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setBackgroundResource(R.drawable.panel)
                        setPadding(32, 24, 32, 24)
                    }
                    row.addView(TextView(this@AdminActivity).apply {
                        text = "${o.getInt("value")} km/h"
                        textSize = 24f
                        setTextColor(ContextCompat.getColor(context, R.color.accent))
                    })
                    row.addView(TextView(this@AdminActivity).apply {
                        val note = o.optString("note")
                        text = "${o.optString("userName")} · ${fmt.format(Date(o.getLong("created")))}" +
                            (if (o.isNull("heading")) "" else " · kierunek ${o.getInt("heading")}°") +
                            (if (note.isNotBlank()) "\n„$note”" else "")
                        setTextColor(ContextCompat.getColor(context, R.color.text))
                        textSize = 15f
                    })
                    val buttons = LinearLayout(this@AdminActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
                    buttons.addView(MaterialButton(this@AdminActivity, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                        text = "Mapa"
                        setOnClickListener {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.openstreetmap.org/?mlat=$lat&mlon=$lon#map=18/$lat/$lon")))
                        }
                    })
                    buttons.addView(MaterialButton(this@AdminActivity).apply {
                        text = "Odrzuć"
                        setOnClickListener { decide(id, false) }
                    })
                    buttons.addView(MaterialButton(this@AdminActivity).apply {
                        text = "Zatwierdź"
                        setOnClickListener { decide(id, true) }
                    })
                    row.addView(buttons)
                    list.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 24 })
                }
            } catch (e: ApiException) {
                Toast.makeText(this@AdminActivity, e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun decide(id: Int, approve: Boolean) {
        lifecycleScope.launch {
            try {
                Api.from(this@AdminActivity).decideLimit(id, approve)
                Toast.makeText(this@AdminActivity, if (approve) "Zatwierdzono" else "Odrzucono", Toast.LENGTH_SHORT).show()
                load()
            } catch (e: ApiException) {
                Toast.makeText(this@AdminActivity, e.message, Toast.LENGTH_LONG).show()
            }
        }
    }
}
