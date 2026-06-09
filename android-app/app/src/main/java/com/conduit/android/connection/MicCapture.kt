package com.conduit.android.connection

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

/**
 * Captures audio from the phone's microphone via [AudioRecord] and streams raw PCM
 * (48 kHz, mono, 16-bit LE) to [MicServer]. Requires RECORD_AUDIO permission.
 */
class MicCapture(private val server: MicServer) {

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    val isRunning: Boolean get() = running

    @SuppressLint("MissingPermission") // RECORD_AUDIO declared in manifest
    fun start(): Boolean {
        if (running) return true
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "unsupported audio format"); return false
        }
        val bufferSize = maxOf(minBuf, 8 * 1024)
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, bufferSize)
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord build failed", e); return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized (missing RECORD_AUDIO?)")
            runCatching { rec.release() }; return false
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
                if (running) Log.e(TAG, "mic read error", e)
            }
        }.apply { isDaemon = true; name = "TetherMicCap"; start() }
        Log.i(TAG, "mic capture started ${SAMPLE_RATE}Hz mono")
        return true
    }

    fun stop() {
        running = false
        runCatching { thread?.join(200) }; thread = null
        runCatching { record?.stop() }
        runCatching { record?.release() }; record = null
    }

    private companion object {
        const val TAG = "TetherMicCap"
        const val SAMPLE_RATE = 48_000
    }
}
