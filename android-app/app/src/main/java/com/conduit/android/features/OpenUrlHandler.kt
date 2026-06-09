package com.conduit.android.features

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.conduit.android.connection.Envelope
import com.conduit.android.connection.FeatureHandler
import com.conduit.android.connection.PeerSession
import com.conduit.android.connection.decode
import com.conduit.android.state.ConduitState
import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicInteger

@Serializable
data class OpenUrlPayload(val url: String)

/**
 * Handles `phone.openUrl` from the Mac.
 *
 * Android 10+ forbids starting activities from the background, so we post a
 * heads-up notification instead. The user taps it and the URL opens in the
 * default browser — no permissions needed beyond POST_NOTIFICATIONS (already
 * declared) and no background-activity-launch restrictions apply.
 */
class OpenUrlHandler(private val context: Context) : FeatureHandler {

    override fun handles(type: String) = type == "phone.openUrl"

    override suspend fun onText(session: PeerSession, env: Envelope) {
        val url = env.decode<OpenUrlPayload>().url.trim()
        if (url.isBlank()) return
        try {
            showNotification(url)
            ConduitState.logEvent("URL ready to open on phone")
        } catch (e: Exception) {
            Log.w("OpenUrlHandler", "Could not post URL notification", e)
        }
    }

    private fun showNotification(url: String) {
        val nm = context.getSystemService(NotificationManager::class.java)

        // Dedicated high-importance channel so the notification pops up as a heads-up alert.
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Open on phone", NotificationManager.IMPORTANCE_HIGH)
            )
        }

        val openIntent = PendingIntent.getActivity(
            context,
            notifIdCounter.incrementAndGet(),
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        // Show just the domain as the title so long URLs don't overflow.
        val displayHost = runCatching { Uri.parse(url).host ?: url }.getOrDefault(url)

        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle("Open link from Mac")
            .setContentText(displayHost)
            .setSubText(url)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .build()

        nm.notify(notifIdCounter.get(), notification)
    }

    companion object {
        private const val CHANNEL = "tether_open_url"
        private val notifIdCounter = AtomicInteger(1000)
    }
}
