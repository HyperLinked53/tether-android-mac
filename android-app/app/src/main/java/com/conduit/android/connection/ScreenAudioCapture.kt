package com.conduit.android.connection

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * Captures the phone's *playback* audio (other apps' media/game audio) via the same
 * [MediaProjection] used for screen capture, and streams it as raw PCM to [ScreenAudioServer].
 * Requires Android 10 (API 29) and the RECORD_AUDIO permission. Used only when the audio target is
 * "mac"; the phone keeps playing its own audio too (the user can lower phone volume to avoid echo).
 */
class ScreenAudioCapture(private val server: ScreenAudioServer) {

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    val isRunning: Boolean get() = running

    @SuppressLint("MissingPermission") // RECORD_AUDIO is requested in MainActivity; guarded below
    @RequiresApi(Build.VERSION_CODES.Q)
    fun start(projection: MediaProjection): Boolean {
        if (running) return true
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT)
        val bufferSize = maxOf(minBuf, 16 * 1024)

        val rec = try {
            AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .setAudioPlaybackCaptureConfig(config)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord build failed", e); return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized (missing RECORD_AUDIO?)")
            runCatching { rec.release() }
            return false
        }
        record = rec
        running = true
        rec.startRecording()
        thread = Thread {
            val buf = ByteArray(bufferSize)
            try {
                while (running) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) server.write(buf, n) else if (n < 0) break
                }
            } catch (e: Exception) {
                if (running) Log.e(TAG, "audio read error", e)
            }
        }.apply { isDaemon = true; name = "TetherAudioCap"; start() }
        Log.i(TAG, "audio playback capture started")
        return true
    }

    fun stop() {
        running = false
        runCatching { thread?.join(200) }; thread = null
        runCatching { record?.stop() }
        runCatching { record?.release() }; record = null
    }

    private companion object {
        const val TAG = "TetherAudioCap"
        const val SAMPLE_RATE = 48_000
    }
}
