package com.conduit.android.connection

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.conduit.android.features.CameraCapture
import com.conduit.android.features.CameraHandler
import com.conduit.android.features.ClipboardHandler
import com.conduit.android.features.FileBrowseHandler
import com.conduit.android.features.FileTransferHandler
import com.conduit.android.features.PhotosHandler
import com.conduit.android.features.InputHandler
import com.conduit.android.features.NotificationReplyHandler
import com.conduit.android.features.ScreenFeatureHandler
import com.conduit.android.features.SmsHandler
import com.conduit.android.features.MediaHandler
import com.conduit.android.features.OpenUrlHandler
import com.conduit.android.features.RingHandler
import com.conduit.android.features.StatusHandler
import com.conduit.android.ui.MainActivity
import com.conduit.android.ui.theme.ThemeStore
import com.conduit.android.state.ConduitState
import com.conduit.android.state.PairingInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

const val APP_VERSION = "0.1.0"

/**
 * Long-lived foreground service that hosts the WebSocket [ConduitServer] and the NSD advertiser
 * for the lifetime of the link. UI-initiated actions (begin pairing, send a file) come in via the
 * static helpers backed by [instance].
 */
class ConnectionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var pairing: PairingManager
    private lateinit var advertiser: DeviceAdvertiser
    private lateinit var fileTransfer: FileTransferHandler
    private lateinit var fileHttpServer: FileHttpServer
    private lateinit var screenServer: ScreenServer
    private lateinit var screenAudioServer: ScreenAudioServer
    private lateinit var screenCapture: ScreenCaptureManager
    private lateinit var cameraServer: CameraServer
    private lateinit var cameraCapture: CameraCapture
    private lateinit var micServer: MicServer
    private lateinit var micCapture: MicCapture
    private lateinit var clipboard: ClipboardHandler
    private lateinit var media: MediaHandler
    private var server: ConduitServer? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var disconnectRunnable: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        pairing = PairingManager(this)
        advertiser = DeviceAdvertiser(this)
        fileHttpServer = FileHttpServer().also { runCatching { it.start() } }
        fileTransfer = FileTransferHandler(this, fileHttpServer)
        screenServer = ScreenServer().also { runCatching { it.start() } }
        screenAudioServer = ScreenAudioServer().also { runCatching { it.start() } }
        screenCapture = ScreenCaptureManager(this, screenServer, ScreenAudioCapture(screenAudioServer))
        screenServer.onViewerGone = { /* viewer closed; capture keeps running until screen.stop */ }
        screenCapture.onStopped = { ConduitState.logEvent("Screen mirroring stopped") }
        cameraServer = CameraServer().also { runCatching { it.start() } }
        cameraCapture = CameraCapture(this, cameraServer)
        cameraCapture.onStopped = { ConduitState.logEvent("Camera webcam stopped") }
        micServer = MicServer().also { runCatching { it.start() } }
        micCapture = MicCapture(micServer)
        clipboard = ClipboardHandler(this).also { it.registerListener() }
        media = MediaHandler(this).also { it.startTracking() }

        val selfInfo = DeviceIdentity.info(this, APP_VERSION)
        val handlers = listOf(
            fileTransfer,
            FileBrowseHandler(this, fileHttpServer),
            PhotosHandler(this, fileHttpServer),
            ScreenFeatureHandler(screenCapture, screenServer, screenAudioServer, ::requestScreenConsent, ::stopScreenCapture),
            CameraHandler(cameraCapture, cameraServer, micCapture, micServer),
            InputHandler(),
            StatusHandler(this),
            RingHandler(this),
            OpenUrlHandler(this),
            clipboard,
            media,
            SmsHandler(this, fileHttpServer),
            NotificationReplyHandler(),
        )

        startForegroundNotification()
        acquireMulticastLock()

        server = ConduitServer(DEFAULT_PORT, scope, pairing, selfInfo, handlers).also {
            it.onPeerAuthenticated = {
                media.pushCurrentState()
                scheduleDisconnectTimer()
            }
            it.start()
        }
        advertiser.register(DEFAULT_PORT, selfInfo.id, selfInfo.name)
        ConduitState.logEvent("Tether service started on port $DEFAULT_PORT")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        advertiser.unregister()
        server?.let { runCatching { it.shutdownGracefully(); it.stop(1000) } }
        runCatching { fileHttpServer.stop() }
        runCatching { screenCapture.stop() }
        runCatching { screenServer.stop() }
        runCatching { screenAudioServer.stop() }
        runCatching { cameraCapture.stop() }
        runCatching { cameraServer.stop() }
        runCatching { micCapture.stop() }
        runCatching { micServer.stop() }
        runCatching { clipboard.unregisterListener() }
        multicastLock?.let { if (it.isHeld) it.release() }
        cancelDisconnectTimer()
        scope.cancel()
        instance = null
        super.onDestroy()
    }

    // ---- Auto-disconnect timer ---------------------------------------------

    private fun scheduleDisconnectTimer() {
        cancelDisconnectTimer()
        val delayMs = ThemeStore.disconnectDelayMs() ?: return
        val r = Runnable {
            disconnectRunnable = null
            server?.primarySession()?.let { session ->
                ConduitState.logEvent("Auto-disconnecting after timer")
                runCatching { session.conn.close() }
            }
        }
        disconnectRunnable = r
        mainHandler.postDelayed(r, delayMs)
    }

    private fun cancelDisconnectTimer() {
        disconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        disconnectRunnable = null
    }

    // ---- UI-initiated actions ----------------------------------------------

    private fun beginPairing() {
        val secret = pairing.beginPairing()
        // The Mac connects to whichever address it discovered; QR carries id + secret to prove it.
        val qr = "tether://pair?deviceId=${DeviceIdentity.id(this)}&secret=${Uri.encode(secret)}&v=$PROTOCOL_VERSION"
        ConduitState.pairing.value = PairingInfo(secret = secret, url = qr, qrPayload = qr)
        ConduitState.logEvent("Pairing window open")
    }

    private fun sendPickedFile(uri: Uri) {
        val session = server?.primarySession()
        if (session == null) { ConduitState.logEvent("No connected device to send to"); return }
        fileTransfer.sendFile(session, uri)
    }

    // ---- screen mirroring ---------------------------------------------------

    /** Called from [MainActivity] once the user approves the MediaProjection consent dialog. */
    private fun beginScreenCapture(resultCode: Int, data: Intent) {
        // On Android 14+ the FGS must carry the mediaProjection type before projection starts.
        promoteForegroundForProjection()
        val ok = screenCapture.start(resultCode, data, maxLongSide = 1600, bitrate = 8_000_000)
        if (ok) {
            ConduitState.logEvent("Screen mirroring started")
            server?.broadcast(envelope("screen.ready",
                ScreenReadyPayload(screenServer.port, screenCapture.width, screenCapture.height, screenAudioServer.port)))
        } else {
            server?.broadcast(envelope("screen.error", ScreenErrorPayload("could not start screen capture")))
        }
    }

    private fun stopScreenCapture() {
        screenCapture.stop()
        server?.broadcast(envelope("screen.stop"))
    }

    /** A Mac asked to mirror but we have no consent yet — prompt the user on the phone. */
    private fun requestScreenConsent() {
        val nm = getSystemService(NotificationManager::class.java)
        val open = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_REQUEST_SCREEN, true)
        }
        val pi = PendingIntent.getActivity(this, 1, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("Allow screen mirroring?")
            .setContentText("Your Mac wants to mirror this screen. Tap to approve.")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF_SCREEN_CONSENT, n)
        ConduitState.logEvent("Mac requested screen mirroring — approve on the phone")
    }

    /** Re-issue the foreground notification including the mediaProjection FGS type (Android 14+). */
    private fun promoteForegroundForProjection() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val notification = foregroundNotification()
        // microphone type covers AudioPlaybackCapture (AudioRecord) used for "audio → Mac".
        startForeground(NOTIF_ID, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }

    // ---- foreground plumbing -----------------------------------------------

    private fun foregroundNotification(): Notification = Notification.Builder(this, CHANNEL)
        .setContentTitle("Tether is linked")
        .setContentText("Your phone is reachable from your Mac")
        .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
        .setOngoing(true)
        .build()

    private fun startForegroundNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Tether connection", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notification = foregroundNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun acquireMulticastLock() {
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("tether-nsd").apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
    }

    companion object {
        private const val CHANNEL = "tether_connection"
        private const val NOTIF_ID = 1
        private const val NOTIF_SCREEN_CONSENT = 2
        private const val TAG = "ConnectionService"

        /** Intent extra set on the MainActivity launch when a Mac is asking to mirror the screen. */
        const val EXTRA_REQUEST_SCREEN = "tether.request_screen"

        @Volatile private var instance: ConnectionService? = null

        fun start(context: Context) {
            val intent = Intent(context, ConnectionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) = context.stopService(Intent(context, ConnectionService::class.java))

        /** Called from the notification listener (different lifecycle) to push to peers. */
        fun broadcast(env: Envelope) = instance?.server?.broadcast(env)
            ?: Log.w(TAG, "broadcast dropped: service not running")

        fun beginPairing() = instance?.beginPairing()
        fun sendFile(uri: Uri) = instance?.sendPickedFile(uri)
        fun isRunning() = instance != null

        /** Called from MainActivity after the user approves the MediaProjection consent dialog. */
        fun startScreenCapture(resultCode: Int, data: Intent) = instance?.beginScreenCapture(resultCode, data)
        fun stopScreenMirroring() = instance?.stopScreenCapture()

        /** Read the phone's clipboard now and push it to the Mac (call when the app is foregrounded). */
        fun syncClipboard() = instance?.clipboard?.pushCurrent()
    }
}
