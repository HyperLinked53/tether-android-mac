package com.conduit.android.connection

import android.util.Log
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference

const val SCREEN_VIDEO_PORT = 5335

/**
 * Streams the encoded H.264 screen to one Mac viewer over a raw TCP socket (same "Android hosts,
 * Mac connects natively" idea as [FileHttpServer], so video bytes never touch the WebSocket/JS
 * path). Wire format is length-prefixed frames:
 *
 *   [4-byte BE length][1-byte flags: bit0=config(SPS/PPS), bit1=keyframe][Annex-B payload]
 *
 * The latest config (SPS/PPS) and last keyframe are replayed to a freshly-connected viewer so it can
 * start decoding immediately rather than waiting for the next IDR.
 */
class ScreenServer(val port: Int = SCREEN_VIDEO_PORT) {

    private val FLAG_CONFIG: Byte = 0x01
    private val FLAG_KEYFRAME: Byte = 0x02

    private var serverSocket: ServerSocket? = null
    private val client = AtomicReference<DataOutputStream?>(null)
    private val configFrame = AtomicReference<ByteArray?>(null) // SPS/PPS, replayed to new viewers
    private val lastKeyframe = AtomicReference<ByteArray?>(null)

    @Volatile var onViewerConnected: (() -> Unit)? = null
    @Volatile var onViewerGone: (() -> Unit)? = null

    fun start() {
        if (serverSocket != null) return
        val ss = ServerSocket(port).apply { reuseAddress = true }
        serverSocket = ss
        Thread {
            while (!ss.isClosed) {
                val socket = try { ss.accept() } catch (e: Exception) { if (ss.isClosed) break else continue }
                runCatching { handleViewer(socket) }.onFailure { Log.e(TAG, "viewer error", it) }
            }
        }.apply { isDaemon = true; name = "TetherScreenSrv"; start() }
        Log.i(TAG, "screen video server on $port")
    }

    fun stop() {
        runCatching { client.getAndSet(null)?.close() }
        runCatching { serverSocket?.close() }
        serverSocket = null
        configFrame.set(null)
        lastKeyframe.set(null)
    }

    private fun handleViewer(socket: Socket) {
        socket.tcpNoDelay = true
        val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 256 * 1024))
        // Only one viewer; drop any previous one.
        client.getAndSet(out)?.let { runCatching { it.close() } }
        // Prime the new viewer so it can decode right away.
        configFrame.get()?.let { runCatching { out.write(it); out.flush() } }
        lastKeyframe.get()?.let { runCatching { out.write(it); out.flush() } }
        onViewerConnected?.invoke()
    }

    fun hasViewer(): Boolean = client.get() != null

    /** Push the codec config (SPS/PPS). Stored and replayed to future viewers. */
    fun sendConfig(annexB: ByteArray) {
        val frame = frame(annexB, FLAG_CONFIG)
        configFrame.set(frame)
        writeToClient(frame)
    }

    /** Push one encoded access unit. */
    fun sendFrame(annexB: ByteArray, keyframe: Boolean) {
        val frame = frame(annexB, if (keyframe) FLAG_KEYFRAME else 0)
        if (keyframe) lastKeyframe.set(frame)
        writeToClient(frame)
    }

    private fun frame(payload: ByteArray, flags: Byte): ByteArray {
        val out = ByteArray(5 + payload.size)
        val len = payload.size
        out[0] = (len ushr 24).toByte()
        out[1] = (len ushr 16).toByte()
        out[2] = (len ushr 8).toByte()
        out[3] = len.toByte()
        out[4] = flags
        System.arraycopy(payload, 0, out, 5, payload.size)
        return out
    }

    private fun writeToClient(frame: ByteArray) {
        val out = client.get() ?: return
        try {
            synchronized(out) { out.write(frame); out.flush() }
        } catch (e: Exception) {
            // Viewer disconnected mid-stream.
            if (client.compareAndSet(out, null)) {
                runCatching { out.close() }
                onViewerGone?.invoke()
            }
        }
    }

    private companion object { const val TAG = "TetherScreenSrv" }
}
