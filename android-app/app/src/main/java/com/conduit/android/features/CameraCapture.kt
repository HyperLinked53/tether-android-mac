package com.conduit.android.features

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.util.Log
import android.view.Surface
import androidx.core.content.ContextCompat
import com.conduit.android.connection.CameraServer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Captures from the phone's camera via Camera2, encodes H.264 with MediaCodec, and pushes the
 * stream to [CameraServer].
 *
 * Resilient to mid-stream camera disconnection (e.g. screen-off on OEM ROMs that revoke camera
 * while the display is off): when [CameraDevice.StateCallback.onDisconnected] fires after the
 * initial open, the encoder and TCP connection are kept alive and the camera is re-opened
 * automatically (up to [MAX_RETRIES] attempts with exponential back-off). The Mac window
 * freezes on the last good frame during the gap and resumes without user action.
 */
class CameraCapture(
    private val context: Context,
    private val server: CameraServer,
) {
    /** "front" or "back". Must be set before [start]; [switchCamera] restarts capture. */
    @Volatile var facing: String = "front"

    @Volatile private var running = false
    @Volatile private var intentionalStop = false  // distinguishes user stop from system eviction
    @Volatile private var cameraDevice: CameraDevice? = null
    private var encoder: MediaCodec? = null
    private var encoderSurface: Surface? = null
    private var drainThread: Thread? = null
    private var lastCameraId: String? = null
    // Screen wake lock: keeps the display on while the camera is streaming so the OS
    // doesn't revoke camera access when the user's phone screen would normally turn off.
    private val wakeLock: PowerManager.WakeLock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "tether:camera")
        .also { it.setReferenceCounted(false) }

    private val cameraThread = HandlerThread("TetherCamCb").also { it.start() }
    private val cameraHandler = Handler(cameraThread.looper)

    var width = 0; private set
    var height = 0; private set
    val isRunning: Boolean get() = running

    @Volatile var onStopped: (() -> Unit)? = null

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Start capturing. Returns true on success (width/height reflect the encoded frame size).
     * Blocks up to 3 s for the initial camera open. [facing] must be set before calling.
     */
    fun start(maxWidth: Int = 1280, bitrate: Int = 4_000_000): Boolean {
        if (running) return true
        if (!hasPermission()) { Log.e(TAG, "camera permission not granted"); return false }

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = selectCamera(manager) ?: run {
            Log.e(TAG, "no $facing camera found"); return false
        }
        lastCameraId = cameraId

        val (w, h) = pickResolution(manager, cameraId, maxWidth)
        width = w; height = h

        return try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            }
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = codec.createInputSurface()
            codec.start()
            encoder = codec
            encoderSurface = surface
            intentionalStop = false
            // Set running=true BEFORE openCamera so createCaptureSession's onConfigured
            // callback sees it as true. If onConfigured fires after the latch releases but
            // before running=true (the old order), it would close the session and return
            // without setRepeatingRequest — encoder never gets input, preview stays black.
            running = true
            wakeLock.acquire()   // keep screen on so OS can't revoke camera access
            startDraining(codec)

            // Initial open: use a one-shot latch to block until the camera opens, then hand
            // control to PersistentCameraCallback for the lifetime of the session.
            val openLatch = CountDownLatch(1)
            var opened = false
            manager.openCamera(cameraId, object : PersistentCameraCallback(surface, retryCount = 0) {
                override fun onOpened(device: CameraDevice) {
                    super.onOpened(device)
                    opened = true
                    openLatch.countDown()
                }
                override fun onDisconnected(device: CameraDevice) {
                    super.onDisconnected(device)
                    if (!opened) openLatch.countDown()
                }
                override fun onError(device: CameraDevice, error: Int) {
                    super.onError(device, error)
                    if (!opened) openLatch.countDown()
                }
            }, cameraHandler)

            if (!openLatch.await(3, TimeUnit.SECONDS) || !opened) {
                Log.e(TAG, "camera open timed out"); stop(); return false
            }

            Log.i(TAG, "camera started ${w}x${h} facing=$facing bitrate=${bitrate / 1000}kbps")
            true
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e); stop(); false
        }
    }

    /** Switch to the given camera while streaming. Stops current capture and restarts. */
    fun switchCamera(newFacing: String, maxWidth: Int = 1280, bitrate: Int = 4_000_000) {
        if (newFacing == facing && running) return
        facing = newFacing
        stop()
        start(maxWidth, bitrate)
    }

    fun stop() {
        if (!running && encoder == null && cameraDevice == null) return
        intentionalStop = true
        running = false
        cameraHandler.removeCallbacksAndMessages(null) // cancel any pending retries
        runCatching { drainThread?.join(300) }; drainThread = null
        runCatching { cameraDevice?.close() }; cameraDevice = null
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }; encoder = null
        runCatching { encoderSurface?.release() }; encoderSurface = null
        if (wakeLock.isHeld) wakeLock.release()
        onStopped?.invoke()
        Log.i(TAG, "camera capture stopped")
    }

    // ── Camera reconnect logic ────────────────────────────────────────────────

    /**
     * CameraDevice.StateCallback that survives mid-stream disconnection. On disconnect/error it
     * schedules a re-open via [cameraHandler] (exponential back-off, up to [MAX_RETRIES]).
     * The encoder and drain thread are left running so the TCP stream just pauses and resumes.
     */
    private open inner class PersistentCameraCallback(
        private val surface: Surface,
        private val retryCount: Int,
    ) : CameraDevice.StateCallback() {

        override fun onOpened(device: CameraDevice) {
            cameraDevice = device
            // Restart the drain thread if it exited (e.g. encoder threw during gap in input).
            val enc = encoder
            if (enc != null && (drainThread == null || drainThread?.isAlive == false)) {
                Log.i(TAG, "restarting drain thread on camera reconnect")
                startDraining(enc)
            }
            createCaptureSession(device, surface)
        }

        override fun onDisconnected(device: CameraDevice) {
            Log.w(TAG, "camera disconnected (running=$running intentional=$intentionalStop)")
            runCatching { device.close() }
            cameraDevice = null
            scheduleRetry(surface, retryCount)
        }

        override fun onError(device: CameraDevice, error: Int) {
            Log.e(TAG, "camera device error $error (running=$running intentional=$intentionalStop)")
            runCatching { device.close() }
            cameraDevice = null
            scheduleRetry(surface, retryCount)
        }
    }

    private fun scheduleRetry(surface: Surface, previousRetryCount: Int) {
        if (!running || intentionalStop) return
        val delayMs = minOf(RETRY_BASE_MS * (1L shl previousRetryCount), RETRY_MAX_MS)
        // Cap the count so the delay stays at RETRY_MAX_MS but retries never stop —
        // the camera may be unavailable for as long as the phone screen is off.
        val nextCount = minOf(previousRetryCount + 1, MAX_RETRIES)
        Log.i(TAG, "scheduling camera reconnect in ${delayMs}ms (attempt ${previousRetryCount + 1})")
        cameraHandler.postDelayed({ reopenCamera(surface, nextCount) }, delayMs)
    }

    private fun reopenCamera(surface: Surface, retryCount: Int) {
        if (!running || intentionalStop) return
        val cameraId = lastCameraId ?: run { Log.e(TAG, "no camera id for retry"); return }
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            manager.openCamera(cameraId, PersistentCameraCallback(surface, retryCount), cameraHandler)
            Log.i(TAG, "camera reopen attempt $retryCount")
        } catch (e: Exception) {
            Log.e(TAG, "camera reopen failed: ${e.message}")
            scheduleRetry(surface, retryCount)
        }
    }

    private fun createCaptureSession(device: CameraDevice, surface: Surface) {
        try {
        @Suppress("DEPRECATION")
        device.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                if (!running || intentionalStop) { runCatching { session.close() }; return }
                try {
                    val req = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(surface)
                        set(CaptureRequest.CONTROL_AF_MODE,
                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    }
                    session.setRepeatingRequest(req.build(), null, cameraHandler)
                    Log.i(TAG, "camera capture session active")
                } catch (e: Exception) {
                    Log.e(TAG, "setRepeatingRequest failed", e)
                    runCatching { session.close() }
                }
            }
            override fun onConfigureFailed(session: CameraCaptureSession) {
                Log.e(TAG, "capture session configure failed")
                runCatching { session.close() }
                runCatching { device.close() }
            }
        }, cameraHandler)
        } catch (e: Exception) {
            Log.e(TAG, "createCaptureSession failed: ${e.message}")
            runCatching { device.close() }
        }
    }

    // ── Encoder drain ─────────────────────────────────────────────────────────

    private fun startDraining(codec: MediaCodec) {
        drainThread = Thread {
            val info = MediaCodec.BufferInfo()
            try {
                while (running) {
                    val index = codec.dequeueOutputBuffer(info, 50_000)
                    if (index < 0) continue
                    val buf = codec.getOutputBuffer(index)
                    if (buf != null && info.size > 0) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val bytes = ByteArray(info.size)
                        buf.get(bytes)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            server.sendConfig(bytes)
                        } else {
                            server.sendFrame(bytes, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            } catch (e: Exception) {
                if (running) Log.e(TAG, "drain error", e)
            }
        }.apply { isDaemon = true; name = "TetherCameraEnc"; start() }
    }

    // ── Camera selection ──────────────────────────────────────────────────────

    private fun selectCamera(manager: CameraManager): String? {
        val target = if (facing == "front") CameraCharacteristics.LENS_FACING_FRONT
                     else CameraCharacteristics.LENS_FACING_BACK
        return manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == target
        } ?: manager.cameraIdList.firstOrNull()
    }

    /**
     * Pick the largest supported 16:9 landscape resolution whose long side is ≤ maxLong.
     * Falls back to any resolution if no 16:9 option exists.
     */
    private fun pickResolution(manager: CameraManager, cameraId: String, maxLong: Int): Pair<Int, Int> {
        val chars = manager.getCameraCharacteristics(cameraId)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(android.graphics.SurfaceTexture::class.java) ?: emptyArray()
        val even: (Int) -> Int = { (it / 2) * 2 }

        fun is16x9(w: Int, h: Int) = w > h && kotlin.math.abs(w * 9.0 / h - 16.0) < 0.2

        val preferred = sizes
            .filter { maxOf(it.width, it.height) <= maxLong && is16x9(it.width, it.height) }
            .maxByOrNull { it.width.toLong() * it.height }
        if (preferred != null) return even(preferred.width) to even(preferred.height)

        val fallback = sizes
            .filter { maxOf(it.width, it.height) <= maxLong }
            .maxByOrNull { it.width.toLong() * it.height }
        return if (fallback != null) even(fallback.width) to even(fallback.height)
        else { val w = minOf(maxLong, 1280); w to ((w * 9 / 16) and -2) }
    }

    private companion object {
        const val TAG = "TetherCameraCapture"
        const val MAX_RETRIES = 8            // delay cap — retries continue indefinitely
        const val RETRY_BASE_MS = 1_000L   // 1 s, 2 s, 4 s … up to RETRY_MAX_MS
        const val RETRY_MAX_MS = 3_000L    // cap at 3 s so screen-on recovery is fast
    }
}
