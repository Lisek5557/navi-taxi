package pl.taxinav

import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        val server = findViewById<EditText>(R.id.serverInput)
        val key = findViewById<EditText>(R.id.keyInput)
        val result = findViewById<TextView>(R.id.testResult)
        server.setText(Prefs.server(this))
        key.setText(Prefs.apiKey(this))

        fun normalizedServer(): String {
            val s = server.text.toString().trim().trimEnd('/')
            return if (s.isNotEmpty() && !s.startsWith("http")) "https://$s" else s
        }

        findViewById<MaterialButton>(R.id.testButton).setOnClickListener {
            result.text = "Sprawdzam…"
            lifecycleScope.launch {
                result.text = try {
                    val api = Api(normalizedServer(), key.text.toString().trim())
                    val h = api.health()
                    // /health nie wymaga klucza — klucz sprawdzamy krótkim wyszukiwaniem.
                    api.geocode("Warszawa", null)
                    buildString {
                        append("✅ Połączono.\n")
                        append(if (h.optBoolean("valhalla")) "✅ Silnik tras gotowy\n" else "⏳ Silnik tras jeszcze buduje mapę Polski\n")
                        append(if (h.optBoolean("traffic")) "✅ Korki TomTom włączone" else "⚠️ Korki wyłączone (brak TOMTOM_API_KEY na serwerze)")
                    }
                } catch (e: ApiException) {
                    "❌ ${e.message}"
                }
            }
        }

        findViewById<MaterialButton>(R.id.saveButton).setOnClickListener {
            Prefs.save(this, normalizedServer(), key.text.toString())
            finish()
        }
    }
}
