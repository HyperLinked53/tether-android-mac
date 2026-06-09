package com.conduit.android.connection

import android.util.Log
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference

const val SCREEN_AUDIO_PORT = 5336

/**
 * Streams raw PCM (48 kHz, stereo, 16-bit LE) to one Mac viewer over a raw TCP socket — the audio
 * companion to [ScreenServer]. No framing: it's a continuous PCM byte stream the Mac plays as it
 * arrives. Only used when the audio target is "mac".
 */
class ScreenAudioServer(val port: Int = SCREEN_AUDIO_PORT) {

    private var serverSocket: ServerSocket? = null
    private val client = AtomicReference<OutputStream?>(null)

    fun start() {
        if (serverSocket != null) return
        val ss = ServerSocket(port).apply { reuseAddress = true }
        serverSocket = ss
        Thread {
            while (!ss.isClosed) {
                val socket = try { ss.accept() } catch (e: Exception) { if (ss.isClosed) break else continue }
                runCatching { handleViewer(socket) }.onFailure { Log.e(TAG, "audio viewer error", it) }
            }
        }.apply { isDaemon = true; name = "TetherAudioSrv"; start() }
        Log.i(TAG, "screen audio server on $port")
    }

    fun stop() {
        runCatching { client.getAndSet(null)?.close() }
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun handleViewer(socket: Socket) {
        socket.tcpNoDelay = true
        val out = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
        client.getAndSet(out)?.let { runCatching { it.close() } } // one viewer
    }

    fun hasViewer(): Boolean = client.get() != null

    fun write(pcm: ByteArray, len: Int) {
        val out = client.get() ?: return
        try {
            synchronized(out) { out.write(pcm, 0, len); out.flush() }
        } catch (e: Exception) {
            if (client.compareAndSet(out, null)) runCatching { out.close() }
        }
    }

    private companion object { const val TAG = "TetherAudioSrv" }
}
