package com.conduit.android.connection

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

const val FILE_HTTP_PORT = 5334

/**
 * Tiny raw-socket HTTP/1.1 server that streams file bytes over the LAN (no external server). It is
 * deliberately minimal: the WebSocket already authenticated the peer and minted the per-transfer
 * `transferId` (an unguessable UUID), which acts as a capability token here — only registered ids
 * are served, everything else 404s.
 *
 *   GET  /download/<transferId>  → streams the registered file       (Android → Mac)
 *   POST /upload/<transferId>    → writes the Content-Length body to disk (Mac → Android)
 *
 * Bytes move disk↔socket with a large buffer and never touch the (slow) WebSocket/JS path.
 */
class FileHttpServer(val port: Int = FILE_HTTP_PORT) {

    interface UploadListener {
        fun onUploadProgress(transferId: String, received: Long, total: Long)
        fun onUploadComplete(transferId: String)
        fun onUploadError(transferId: String, message: String)
    }

    /** A byte source to serve for download — a local File or a SAF content stream. */
    class DownloadSource(val length: Long, val open: () -> InputStream)

    /** A byte sink to write an upload into — a local File or a MediaStore stream. */
    class UploadSink(val size: Long, val open: () -> OutputStream)

    private val downloads = ConcurrentHashMap<String, DownloadSource>()
    private val uploads = ConcurrentHashMap<String, UploadSink>()
    private val pool = Executors.newCachedThreadPool()
    private var serverSocket: ServerSocket? = null

    @Volatile var listener: UploadListener? = null

    fun registerDownload(transferId: String, source: DownloadSource) { downloads[transferId] = source }
    fun registerUpload(transferId: String, sink: UploadSink) { uploads[transferId] = sink }
    fun unregister(transferId: String) { downloads.remove(transferId); uploads.remove(transferId) }

    fun start() {
        if (serverSocket != null) return
        val ss = ServerSocket(port)
        ss.reuseAddress = true
        serverSocket = ss
        Thread {
            while (!ss.isClosed) {
                val socket = try { ss.accept() } catch (e: Exception) { if (ss.isClosed) break else continue }
                pool.execute { runCatching { handle(socket) }.onFailure { Log.e(TAG, "conn error", it) } }
            }
        }.apply { isDaemon = true; name = "TetherFileHttp"; start() }
        Log.i(TAG, "file HTTP server on $port")
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        pool.shutdownNow()
        downloads.clear(); uploads.clear()
    }

    private fun handle(socket: Socket) {
        socket.tcpNoDelay = true
        socket.use {
            val input = BufferedInputStream(it.getInputStream(), BUFFER)
            val output = BufferedOutputStream(it.getOutputStream(), BUFFER)
            val req = readRequest(input) ?: return
            val path = req.path.substringBefore('?')
            when {
                req.method == "GET" && path.startsWith("/download/") ->
                    handleDownload(path.removePrefix("/download/"), output)
                (req.method == "POST" || req.method == "PUT") && path.startsWith("/upload/") ->
                    handleUpload(path.removePrefix("/upload/"), req.headers, input, output)
                else -> writeStatus(output, 404, "Not Found")
            }
            output.flush()
        }
    }

    private fun handleDownload(transferId: String, output: OutputStream) {
        val source = downloads[transferId]
        if (source == null) { writeStatus(output, 404, "Not Found"); return }
        // Only advertise Content-Length when we actually know it; otherwise rely on Connection:close
        // to delimit the body (the client reads until EOF). This avoids truncating to a stale/0 size.
        val lengthHeader = if (source.length > 0) "Content-Length: ${source.length}\r\n" else ""
        output.write(
            ("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n" +
                lengthHeader + "Connection: close\r\n\r\n").toByteArray(),
        )
        source.open().use { input ->
            val buf = ByteArray(BUFFER)
            var n: Int
            while (input.read(buf).also { n = it } > 0) output.write(buf, 0, n)
        }
    }

    private fun handleUpload(transferId: String, headers: Map<String, String>, input: InputStream, output: OutputStream) {
        val sink = uploads[transferId]
        if (sink == null) { writeStatus(output, 404, "Not Found"); return }
        val length = headers["content-length"]?.toLongOrNull() ?: -1L
        try {
            sink.open().use { out ->
                val buf = ByteArray(BUFFER)
                var received = 0L
                var lastReport = 0L
                var remaining = length
                while (remaining != 0L) {
                    val want = if (remaining < 0) buf.size else minOf(buf.size.toLong(), remaining).toInt()
                    val n = input.read(buf, 0, want)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    received += n
                    if (remaining > 0) remaining -= n
                    if (received - lastReport >= REPORT_EVERY) {
                        listener?.onUploadProgress(transferId, received, sink.size)
                        lastReport = received
                    }
                }
                out.flush()
            }
            writeStatus(output, 200, "OK")
            listener?.onUploadComplete(transferId)
        } catch (e: Exception) {
            Log.e(TAG, "upload failed", e)
            runCatching { writeStatus(output, 500, "Error") }
            listener?.onUploadError(transferId, e.message ?: "upload failed")
        }
    }

    private data class Request(val method: String, val path: String, val headers: Map<String, String>)

    private fun readRequest(input: InputStream): Request? {
        val header = ByteArrayOutputStream()
        var b: Int
        while (input.read().also { b = it } != -1) {
            header.write(b)
            val s = header.size()
            if (s > MAX_HEADER) return null
            if (s >= 4) {
                val a = header.toByteArray()
                if (a[s - 4] == CR && a[s - 3] == LF && a[s - 2] == CR && a[s - 1] == LF) break
            }
        }
        val lines = header.toString("ISO-8859-1").split("\r\n").filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        val first = lines[0].split(" ")
        if (first.size < 2) return null
        val headers = HashMap<String, String>()
        for (i in 1 until lines.size) {
            val idx = lines[i].indexOf(':')
            if (idx > 0) headers[lines[i].substring(0, idx).trim().lowercase()] = lines[i].substring(idx + 1).trim()
        }
        return Request(first[0], first[1], headers)
    }

    private fun writeStatus(output: OutputStream, code: Int, text: String) {
        val body = text.toByteArray()
        output.write(
            ("HTTP/1.1 $code $text\r\nContent-Type: text/plain\r\n" +
                "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray(),
        )
        output.write(body)
    }

    private companion object {
        const val TAG = "TetherFileHttp"
        const val BUFFER = 256 * 1024
        const val REPORT_EVERY = 2_000_000L
        const val MAX_HEADER = 16 * 1024
        const val CR: Byte = 13
        const val LF: Byte = 10
    }
}
