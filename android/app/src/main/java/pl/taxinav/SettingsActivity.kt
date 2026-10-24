package pl.taxinav

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {
    private lateinit var server: EditText
    private lateinit var key: EditText
    private lateinit var result: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        server = findViewById(R.id.serverInput)
        key = findViewById(R.id.keyInput)
        result = findViewById(R.id.testResult)
        server.setText(Prefs.server(this))
        key.setText(Prefs.apiKey(this))

        findViewById<MaterialButton>(R.id.scanButton).setOnClickListener { scan() }
        findViewById<MaterialButton>(R.id.testButton).setOnClickListener { runTest() }
        findViewById<MaterialButton>(R.id.saveButton).setOnClickListener { saveAndClose() }

        setupAccount()
        setupOffline()
        handleIntent(intent)
    }

    // ---------- Konto kierowcy ----------
    private fun setupAccount() {
        val info = findViewById<TextView>(R.id.accountInfo)
        val row = findViewById<android.view.View>(R.id.registerRow)
        val admin = findViewById<MaterialButton>(R.id.adminButton)
        fun refresh() {
            val name = Prefs.userName(this)
            if (name.isEmpty()) {
                info.text = "Nie masz jeszcze konta — wpisz imię lub kod i załóż je (bez akceptacji)."
                row.visibility = android.view.View.VISIBLE
                admin.visibility = android.view.View.GONE
            } else {
                info.text = "Zalogowany jako: $name" + if (Prefs.isAdmin(this)) " (admin)" else ""
                row.visibility = android.view.View.GONE
                admin.visibility = if (Prefs.isAdmin(this)) android.view.View.VISIBLE else android.view.View.GONE
            }
        }
        refresh()
        // Odśwież rolę z serwera (admin mógł ją zmienić w panelu).
        if (Prefs.userToken(this).isNotEmpty()) lifecycleScope.launch {
            runCatching { Api.from(this@SettingsActivity).me() }.getOrNull()?.let {
                Prefs.setUser(this@SettingsActivity, Prefs.userToken(this@SettingsActivity), it.getString("name"), it.getString("role"))
                refresh()
            }
        }
        findViewById<MaterialButton>(R.id.registerButton).setOnClickListener {
            val name = findViewById<EditText>(R.id.nameInput).text.toString().trim()
            Prefs.save(this, normalizedServer(), key.text.toString())
            lifecycleScope.launch {
                try {
                    val j = Api.from(this@SettingsActivity).register(name)
                    val u = j.getJSONObject("user")
                    Prefs.setUser(this@SettingsActivity, j.getString("token"), u.getString("name"), u.getString("role"))
                    toast(if (u.getString("role") == "admin") "Konto założone — jesteś adminem" else "Konto założone")
                    refresh()
                } catch (e: ApiException) {
                    toast(e.message ?: "Błąd")
                }
            }
        }
        admin.setOnClickListener { startActivity(Intent(this, AdminActivity::class.java)) }
    }

    // ---------- Mapa offline ----------
    private fun setupOffline() {
        val info = findViewById<TextView>(R.id.offlineInfo)
        val btn = findViewById<MaterialButton>(R.id.offlineButton)
        OfflineMaps.status(this) { mb, complete ->
            runOnUiThread {
                if (mb > 0) info.text = if (complete) "✅ Pobrana mapa kujawsko-pomorskiego ($mb MB)" else "Pobrano częściowo ($mb MB) — wznów pobieranie"
            }
        }
        btn.setOnClickListener {
            btn.isEnabled = false
            info.text = "Rozpoczynam pobieranie…"
            OfflineMaps.download(this, Prefs.server(this), object : OfflineMaps.Listener {
                override fun onProgress(percent: Int, mb: Long) = runOnUiThread { info.text = "Pobieranie: $percent% ($mb MB)" }
                override fun onDone(mb: Long) = runOnUiThread { info.text = "✅ Mapa offline gotowa ($mb MB)"; btn.isEnabled = true }
                override fun onError(msg: String) = runOnUiThread { info.text = "❌ $msg"; btn.isEnabled = true }
            })
        }
        findViewById<MaterialButton>(R.id.offlineDelete).setOnClickListener {
            OfflineMaps.delete(this) { runOnUiThread { info.text = "Usunięto mapę offline"; btn.isEnabled = true } }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Link taxinav://setup?s=...&k=... (kod QR zeskanowany aparatem telefonu). */
    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (!applySetup(data.toString())) toast("Nieprawidłowy kod konfiguracji")
    }

    private fun scan() {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { code ->
                if (!applySetup(code.rawValue ?: "")) toast("To nie jest kod TaxiNav")
            }
            .addOnFailureListener { e ->
                toast("Skaner niedostępny: ${e.message ?: "spróbuj zwykłym aparatem telefonu"}")
            }
    }

    /** Wypełnia pola z kodu, zapisuje i od razu sprawdza połączenie. */
    private fun applySetup(raw: String): Boolean {
        val uri = runCatching { Uri.parse(raw.trim()) }.getOrNull() ?: return false
        if (uri.scheme != "taxinav" || uri.host != "setup") return false
        val s = uri.getQueryParameter("s")?.trim().orEmpty()
        val k = uri.getQueryParameter("k")?.trim().orEmpty()
        if (!s.startsWith("http") || k.isEmpty()) return false
        server.setText(s)
        key.setText(k)
        Prefs.save(this, s, k)
        toast("Zapisano ustawienia z kodu QR")
        runTest()
        return true
    }

    private fun normalizedServer(): String {
        val s = server.text.toString().trim().trimEnd('/')
        return if (s.isNotEmpty() && !s.startsWith("http")) "https://$s" else s
    }

    private fun runTest() {
        result.text = "Sprawdzam…"
        lifecycleScope.launch {
            result.text = try {
                val api = Api(normalizedServer(), key.text.toString().trim())
                val h = api.health()
                // /health nie wymaga klucza — klucz sprawdzamy krótkim wyszukiwaniem.
                api.geocode("Bydgoszcz", null)
                buildString {
                    append("✅ Połączono.\n")
                    val addr = h.optInt("addresses")
                    if (addr > 0) append("✅ Baza adresów: ${addr / 1000} tys.\n")
                    append(if (h.optBoolean("valhalla")) "✅ Silnik tras gotowy\n" else "⏳ Silnik tras jeszcze buduje mapę Polski\n")
                    append(if (h.optBoolean("traffic")) "✅ Korki TomTom włączone" else "⚠️ Korki wyłączone (brak TOMTOM_API_KEY na serwerze)")
                }
            } catch (e: ApiException) {
                "❌ ${e.message}"
            }
        }
    }

    private fun saveAndClose() {
        Prefs.save(this, normalizedServer(), key.text.toString())
        // Otwarte z linku/aparatu — po zapisie przejdź do mapy.
        if (isTaskRoot) startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
