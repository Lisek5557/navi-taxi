package pl.taxinav

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Usługa pierwszoplanowa na czas nawigacji: utrzymuje GPS i lektora przy wygaszonym ekranie
 * i w trakcie rozmowy, pokazuje najbliższy manewr w powiadomieniu.
 */
class NavService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val n = build(this, intent?.getStringExtra(EXTRA_TITLE) ?: "Nawigacja TaxiNav", intent?.getStringExtra(EXTRA_TEXT) ?: "")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else startForeground(NOTIF_ID, n)
        return START_STICKY
    }

    companion object {
        private const val CHANNEL = "nav"
        private const val NOTIF_ID = 42
        private const val ACTION_STOP = "pl.taxinav.STOP"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_TEXT = "text"

        fun start(ctx: Context) {
            ensureChannel(ctx)
            ContextCompat.startForegroundService(ctx, Intent(ctx, NavService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, NavService::class.java).setAction(ACTION_STOP))
        }

        /** Aktualizacja treści powiadomienia (manewr, odległość, przyjazd). */
        fun update(ctx: Context, title: String, text: String) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            runCatching { nm.notify(NOTIF_ID, build(ctx, title, text)) }
        }

        private fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = ctx.getSystemService(NotificationManager::class.java)
                if (nm.getNotificationChannel(CHANNEL) == null) {
                    nm.createNotificationChannel(NotificationChannel(CHANNEL, "Nawigacja", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "Najbliższy manewr podczas nawigacji"
                        setShowBadge(false)
                    })
                }
            }
        }

        private fun build(ctx: Context, title: String, text: String): Notification {
            ensureChannel(ctx)
            val open = PendingIntent.getActivity(ctx, 0,
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            return NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_nav_small)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(open)
                .build()
        }
    }
}
