package com.conduit.android.features

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.conduit.android.connection.Envelope
import com.conduit.android.connection.FeatureHandler
import com.conduit.android.connection.MediaControlPayload
import com.conduit.android.connection.MediaInfoPayload
import com.conduit.android.connection.PeerSession
import com.conduit.android.connection.ConnectionService
import com.conduit.android.connection.decode
import com.conduit.android.connection.envelope
import kotlinx.serialization.Serializable

/**
 * Exposes the phone's current media session to the Mac (media.info) and handles remote
 * transport commands from the Mac (media.control: play_pause / next / previous).
 *
 * Uses MediaSessionManager.getActiveSessions(), which requires a live NotificationListenerService
 * (ConduitNotificationListener). If notification access hasn't been granted the tracker
 * catches the SecurityException and stays idle — no crash.
 */
class MediaHandler(private val context: Context) : FeatureHandler {

    override fun handles(type: String) = type.startsWith("media.")

    override suspend fun onText(session: PeerSession, env: Envelope) {
        if (env.type != "media.control") return
        val p = env.decode<MediaControlPayload>()
        val c = activeController ?: return
        val state = c.playbackState?.state
        when (p.action) {
            "play_pause" -> if (state == PlaybackState.STATE_PLAYING) c.transportControls.pause()
                            else c.transportControls.play()
            "next"       -> c.transportControls.skipToNext()
            "previous"   -> c.transportControls.skipToPrevious()
        }
    }

    // ---- session tracking --------------------------------------------------

    private val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeController: MediaController? = null

    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { _ ->
        updateActiveSession()
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = broadcastInfo()
        override fun onPlaybackStateChanged(state: PlaybackState?) = broadcastInfo()
        override fun onSessionDestroyed() { activeController = null; broadcastEmpty() }
    }

    fun startTracking() {
        mainHandler.post {
            try {
                val cn = ComponentName(context, ConduitNotificationListener::class.java)
                msm.addOnActiveSessionsChangedListener(sessionListener, cn, mainHandler)
                updateActiveSession()
            } catch (e: SecurityException) {
                Log.d("MediaHandler", "Notification listener not granted — media tracking unavailable")
            }
        }
    }

    fun stopTracking() {
        mainHandler.post {
            runCatching { msm.removeOnActiveSessionsChangedListener(sessionListener) }
            activeController?.unregisterCallback(controllerCallback)
            activeController = null
        }
    }

    /** Call when a new Mac connects — push the current state immediately. */
    fun pushCurrentState() = mainHandler.post { broadcastInfo() }

    private fun updateActiveSession() {
        try {
            val cn = ComponentName(context, ConduitNotificationListener::class.java)
            val sessions = msm.getActiveSessions(cn)
            val newSession = sessions.firstOrNull()
            if (newSession?.sessionToken != activeController?.sessionToken) {
                activeController?.unregisterCallback(controllerCallback)
                activeController = newSession
                newSession?.registerCallback(controllerCallback, mainHandler)
            }
            broadcastInfo()
        } catch (e: SecurityException) { /* no notification access */ }
    }

    private fun broadcastInfo() {
        val c = activeController
        val meta = c?.metadata
        val state = c?.playbackState
        val isPlaying = state?.state == PlaybackState.STATE_PLAYING
        val appName = c?.packageName?.let { pkg ->
            runCatching {
                context.packageManager.getApplicationLabel(
                    context.packageManager.getApplicationInfo(pkg, 0)).toString()
            }.getOrDefault(pkg)
        } ?: ""
        val payload = MediaInfoPayload(
            title    = meta?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
            artist   = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                            ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty(),
            album    = meta?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
            appName  = appName,
            isPlaying = isPlaying,
            duration = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
            position = state?.position ?: 0L,
            lastUpdateTime = SystemClock.elapsedRealtime(),
        )
        ConnectionService.broadcast(envelope("media.info", payload))
    }

    private fun broadcastEmpty() = ConnectionService.broadcast(
        envelope("media.info", MediaInfoPayload("", "", "", "", false, 0, 0,
            SystemClock.elapsedRealtime()))
    )
}
