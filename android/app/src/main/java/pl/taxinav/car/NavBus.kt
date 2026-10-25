package pl.taxinav.car

import android.location.Location
import android.os.Handler
import android.os.Looper
import pl.taxinav.NavState
import pl.taxinav.Place
import pl.taxinav.Route

/**
 * Wspólny stan nawigacji telefonu i ekranu Android Auto.
 * Telefon (MainActivity) publikuje trasę, pozycję i stan; samochód je wyświetla
 * i może poprosić o nowy cel albo zatrzymanie nawigacji.
 */
object NavBus {
    @Volatile var route: Route? = null
    @Volatile var state: NavState? = null
    @Volatile var location: Location? = null
    @Volatile var navigating: Boolean = false
    @Volatile var destinationLabel: String? = null
    @Volatile var etaMillis: Long = 0
    @Volatile var styleUrl: String? = null

    /** Ustawiane przez telefon: wybór celu z samochodu i zatrzymanie nawigacji. */
    var requestDestination: ((Place) -> Unit)? = null
    var requestStop: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<() -> Unit>()

    fun addListener(l: () -> Unit) { synchronized(listeners) { listeners += l } }
    fun removeListener(l: () -> Unit) { synchronized(listeners) { listeners -= l } }

    fun publish() {
        val copy = synchronized(listeners) { listeners.toList() }
        main.post { copy.forEach { runCatching { it() } } }
    }

    val phoneReady: Boolean get() = requestDestination != null
}
