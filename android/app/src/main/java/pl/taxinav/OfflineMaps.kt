package pl.taxinav

import android.content.Context
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition

/**
 * Mapa offline województwa kujawsko-pomorskiego (kafle OpenFreeMap mają maks. zoom 14,
 * dalej MapLibre powiększa je sam). Pobierane są oba style: dzień i noc.
 */
object OfflineMaps {
    private val BOUNDS: LatLngBounds = LatLngBounds.from(53.79, 19.77, 52.33, 17.24)
    private const val NAME = "kujawsko-pomorskie"

    interface Listener {
        fun onProgress(percent: Int, mb: Long)
        fun onDone(mb: Long)
        fun onError(msg: String)
    }

    fun download(ctx: Context, server: String, listener: Listener) {
        val mgr = OfflineManager.getInstance(ctx)
        mgr.setOfflineMapboxTileCountLimit(200_000)
        val density = ctx.resources.displayMetrics.density
        val styles = if (server.isBlank()) listOf(MainActivity.FALLBACK_STYLE)
        else listOf("$server/style/day.json", "$server/style/night.json")
        // Styl nocny korzysta z tych samych kafli — wystarczy mały zakres, by zapisać sam styl.
        styles.forEachIndexed { i, url ->
            val def = OfflineTilePyramidRegionDefinition(url, BOUNDS, 6.0, if (i == 0) 14.0 else 7.0, density)
            mgr.createOfflineRegion(def, "$NAME|$i".toByteArray(), object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(offlineRegion: OfflineRegion) {
                    offlineRegion.setObserver(object : OfflineRegion.OfflineRegionObserver {
                        override fun onStatusChanged(status: OfflineRegionStatus) {
                            if (i != 0) return
                            val mb = status.completedResourceSize / 1_000_000
                            if (status.isComplete) {
                                offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                                listener.onDone(mb)
                            } else if (status.requiredResourceCount > 0) {
                                listener.onProgress((100 * status.completedResourceCount / status.requiredResourceCount).toInt(), mb)
                            }
                        }
                        override fun onError(error: OfflineRegionError) {
                            if (i == 0) listener.onError(error.message)
                        }
                        override fun mapboxTileCountLimitExceeded(limit: Long) {
                            if (i == 0) listener.onError("Za dużo kafli (limit $limit)")
                        }
                    })
                    offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
                }
                override fun onError(error: String) { if (i == 0) listener.onError(error) }
            })
        }
    }

    /** Ile MB zajmuje pobrana mapa (0 = brak). */
    fun status(ctx: Context, cb: (mb: Long, complete: Boolean) -> Unit) {
        OfflineManager.getInstance(ctx).listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                val main = offlineRegions?.firstOrNull { String(it.metadata).startsWith("$NAME|0") }
                if (main == null) { cb(0, false); return }
                main.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                    override fun onStatus(status: OfflineRegionStatus?) {
                        cb((status?.completedResourceSize ?: 0) / 1_000_000, status?.isComplete == true)
                    }
                    override fun onError(error: String?) { cb(0, false) }
                })
            }
            override fun onError(error: String) { cb(0, false) }
        })
    }

    fun delete(ctx: Context, done: () -> Unit) {
        OfflineManager.getInstance(ctx).listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                val list = offlineRegions?.filter { String(it.metadata).startsWith(NAME) }.orEmpty()
                if (list.isEmpty()) { done(); return }
                var left = list.size
                list.forEach { r ->
                    r.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                        override fun onDelete() { if (--left == 0) done() }
                        override fun onError(error: String) { if (--left == 0) done() }
                    })
                }
            }
            override fun onError(error: String) = done()
        })
    }
}
