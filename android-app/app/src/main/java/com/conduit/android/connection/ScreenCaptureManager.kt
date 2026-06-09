package com.conduit.android.connection

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.WindowManager

/**
 * Captures the whole screen via [MediaProjection], encodes it to H.264 with [MediaCodec] (rendering
 * the projection into the encoder's input Surface), and pushes the encoded stream to [ScreenServer].
 * One capture session at a time. Consent (resultCode + data) must be obtained from an Activity first.
 */
class ScreenCaptureManager(
    private val context: Context,
    private val server: ScreenServer,
    private val audioCapture: ScreenAudioCapture,
) {
    /** Where audio should play: "phone" (default, not streamed) or "mac". */
    @Volatile var audioTarget: String = "phone"
        set(value) {
            field = value
            if (running) {
                if (value == "mac") startAudio() else audioCapture.stop()
            }
        }

    @Volatile private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var drainThread: Thread? = null
    @Volatile private var running = false

    private val callbackThread = HandlerThread("TetherScreenCb").apply { start() }
    private val handler = Handler(callbackThread.looper)

    @Volatile var width = 0
        private set
    @Volatile var height = 0
        private set

    @Volatile var onStopped: (() -> Unit)? = null

    val isRunning: Boolean get() = running

    /**
     * Start capturing. Returns true on success; [width]/[height] hold the encoded dimensions.
     * @param maxLongSide cap the longer dimension (keeps aspect), 0 = native.
     */
    fun start(resultCode: Int, data: Intent, maxLongSide: Int, bitrate: Int): Boolean {
        if (running) return true
        val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mgr.getMediaProjection(resultCode, data) ?: run {
            Log.e(TAG, "getMediaProjection returned null"); return false
        }

        val (sw, sh, density) = realScreenSize()
        val (w, h) = scaled(sw, sh, if (maxLongSide > 0) maxLongSide else maxOf(sw, sh))
        width = w; height = h

        return try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, 60)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // an IDR ~every second for late joiners
                setInteger(MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            }
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = codec.createInputSurface()
            codec.start()
            encoder = codec

            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stop() }
            }, handler)
            projection = proj

            virtualDisplay = proj.createVirtualDisplay(
                "tether-screen", w, h, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface, null, handler,
            )

            running = true
            startDraining(codec)
            if (audioTarget == "mac") startAudio()
            Log.i(TAG, "screen capture started ${w}x$h @ ${bitrate / 1_000_000}Mbps audio=$audioTarget")
            true
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            stop()
            false
        }
    }

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
                            server.sendConfig(bytes) // SPS/PPS
                        } else {
                            val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                            server.sendFrame(bytes, key)
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            } catch (e: Exception) {
                if (running) Log.e(TAG, "drain error", e)
            }
        }.apply { isDaemon = true; name = "TetherScreenEnc"; start() }
    }

    private fun startAudio() {
        val proj = projection ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) audioCapture.start(proj)
    }

    fun stop() {
        if (!running && encoder == null && projection == null) return
        running = false
        runCatching { audioCapture.stop() }
        runCatching { drainThread?.join(300) }
        drainThread = null
        runCatching { virtualDisplay?.release() }; virtualDisplay = null
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }; encoder = null
        runCatching { inputSurface?.release() }; inputSurface = null
        runCatching { projection?.stop() }; projection = null
        onStopped?.invoke()
        Log.i(TAG, "screen capture stopped")
    }

    private data class Size(val w: Int, val h: Int, val density: Int)

    private fun realScreenSize(): Size {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            Size(b.width(), b.height(), context.resources.displayMetrics.densityDpi)
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
            Size(m.widthPixels, m.heightPixels, m.densityDpi)
        }
    }

    /** Scale (w,h) so the longer side is at most [maxLong], preserving aspect; dims rounded even. */
    private fun scaled(w: Int, h: Int, maxLong: Int): Pair<Int, Int> {
        val longSide = maxOf(w, h)
        val scale = if (longSide > maxLong) maxLong.toDouble() / longSide else 1.0
        fun even(v: Double) = (Math.round(v / 2.0) * 2).toInt().coerceAtLeast(2)
        return even(w * scale) to even(h * scale)
    }

    private companion object { const val TAG = "TetherScreenCap" }
}
