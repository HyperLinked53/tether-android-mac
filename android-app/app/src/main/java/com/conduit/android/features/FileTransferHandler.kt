package com.conduit.android.features

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.conduit.android.connection.Envelope
import com.conduit.android.connection.FeatureHandler
import com.conduit.android.connection.FileAcceptPayload
import com.conduit.android.connection.FileErrorPayload
import com.conduit.android.connection.FileHttpServer
import com.conduit.android.connection.FileOfferPayload
import com.conduit.android.connection.FileProgressPayload
import com.conduit.android.connection.PeerSession
import com.conduit.android.connection.TransferIdPayload
import com.conduit.android.connection.decode
import com.conduit.android.connection.envelope
import com.conduit.android.state.ConduitState
import com.conduit.android.state.TransferDirection
import com.conduit.android.state.TransferStatus
import com.conduit.android.state.TransferUi
import java.io.File
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Coordinates file transfer (protocol/README §4.1). The WebSocket carries only control messages;
 * bytes stream over [FileHttpServer] (Android is always the HTTP server). Received files are saved
 * to the user-visible Downloads collection (MediaStore on API 29+).
 */
class FileTransferHandler(
    private val context: Context,
    private val httpServer: FileHttpServer,
) : FeatureHandler, FileHttpServer.UploadListener {

    private data class Meta(val name: String, val size: Long, val direction: TransferDirection)

    /** A place to write an incoming file, plus how to finalize/show it. */
    private class Destination(
        val displayName: String,
        val open: () -> OutputStream,
        val finalize: () -> Unit = {},
    )

    private val meta = ConcurrentHashMap<String, Meta>()
    private val uploadSessions = ConcurrentHashMap<String, PeerSession>()
    private val destinations = ConcurrentHashMap<String, Destination>()

    init { httpServer.listener = this }

    override fun handles(type: String) = type.startsWith("file.")

    override suspend fun onText(session: PeerSession, env: Envelope) {
        when (env.type) {
            "file.offer" -> onOffer(session, env.decode())          // Mac → Android (we receive)
            "file.progress" -> onProgress(env.decode())             // Mac reporting an Android→Mac download
            "file.complete" -> onComplete(env.decode())             // Mac finished an Android→Mac download
            "file.error" -> onError(env.decode())
        }
    }

    /** Mac → Android: open a destination in Downloads and tell the Mac where to POST. */
    private fun onOffer(session: PeerSession, offer: FileOfferPayload) {
        val name = File(offer.name).name.ifBlank { "file_${offer.transferId.take(8)}" }
        val dest = createDestination(name, offer.mime)
        destinations[offer.transferId] = dest
        httpServer.registerUpload(offer.transferId, FileHttpServer.UploadSink(offer.size, dest.open))
        uploadSessions[offer.transferId] = session
        meta[offer.transferId] = Meta(name, offer.size, TransferDirection.INCOMING)
        publish(offer.transferId, 0, TransferStatus.ACTIVE)
        session.send(envelope("file.accept", FileAcceptPayload(offer.transferId, httpServer.port)))
        ConduitState.logEvent("Receiving $name…")
    }

    /** Android → Mac: serve a user-picked file for the Mac to download, then offer it. */
    fun sendFile(session: PeerSession, uri: Uri) {
        val (name, size) = queryMeta(uri)
        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        val transferId = UUID.randomUUID().toString()
        meta[transferId] = Meta(name, size, TransferDirection.OUTGOING)
        httpServer.registerDownload(
            transferId,
            FileHttpServer.DownloadSource(size) {
                context.contentResolver.openInputStream(uri) ?: error("cannot open $uri")
            },
        )
        publish(transferId, 0, TransferStatus.ACTIVE)
        session.send(envelope("file.offer", FileOfferPayload(transferId, name, size, mime, httpServer.port)))
        ConduitState.logEvent("Offering $name to Mac…")
    }

    private fun onProgress(p: FileProgressPayload) = publish(p.transferId, p.received, TransferStatus.ACTIVE)

    private fun onComplete(p: TransferIdPayload) {
        httpServer.unregister(p.transferId)
        val m = meta.remove(p.transferId)
        if (m != null) {
            ConduitState.upsertTransfer(TransferUi(p.transferId, m.name, m.size, m.size, m.direction, TransferStatus.COMPLETED))
            ConduitState.logEvent("Sent ${m.name}")
        }
    }

    private fun onError(p: FileErrorPayload) {
        httpServer.unregister(p.transferId)
        uploadSessions.remove(p.transferId)
        destinations.remove(p.transferId)
        markFailed(p.transferId, p.message)
    }

    // ---- FileHttpServer.UploadListener (Mac → Android byte stream) ----------

    override fun onUploadProgress(transferId: String, received: Long, total: Long) =
        publish(transferId, received, TransferStatus.ACTIVE)

    override fun onUploadComplete(transferId: String) {
        httpServer.unregister(transferId)
        val dest = destinations.remove(transferId)
        runCatching { dest?.finalize?.invoke() }
        val m = meta.remove(transferId)
        ConduitState.upsertTransfer(
            TransferUi(transferId, m?.name ?: "file", m?.size ?: 0, m?.size ?: 0,
                TransferDirection.INCOMING, TransferStatus.COMPLETED),
        )
        uploadSessions.remove(transferId)?.send(envelope("file.complete", TransferIdPayload(transferId)))
        ConduitState.logEvent("Saved ${dest?.displayName ?: m?.name}")
    }

    override fun onUploadError(transferId: String, message: String) {
        httpServer.unregister(transferId)
        destinations.remove(transferId)
        uploadSessions.remove(transferId)?.send(envelope("file.error", FileErrorPayload(transferId, message)))
        markFailed(transferId, message)
    }

    // ---- destination + metadata --------------------------------------------

    /** Save into the user-visible Downloads collection (MediaStore on API 29+); else app Inbox. */
    private fun createDestination(name: String, mime: String): Destination {
        val type = mime.ifBlank { "application/octet-stream" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, type)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                ?: error("MediaStore insert failed")
            return Destination(
                displayName = "Downloads/$name",
                open = { resolver.openOutputStream(uri) ?: error("openOutputStream failed") },
                finalize = {
                    resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                },
            )
        }
        // Pre-Q fallback: app-private Inbox (visible via USB / Files ▸ Android/data).
        val inbox = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "Inbox").apply { mkdirs() }
        val file = uniqueFile(inbox, name)
        return Destination(file.absolutePath, { file.outputStream() })
    }

    private fun publish(id: String, transferred: Long, status: TransferStatus) {
        val m = meta[id] ?: return
        ConduitState.upsertTransfer(TransferUi(id, m.name, m.size, transferred, m.direction, status))
    }

    private fun markFailed(id: String, message: String) {
        val m = meta.remove(id)
        ConduitState.upsertTransfer(
            TransferUi(id, m?.name ?: id.take(8), m?.size ?: 0, 0,
                m?.direction ?: TransferDirection.INCOMING, TransferStatus.FAILED, message),
        )
        ConduitState.logEvent("Transfer failed: $message")
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (f.exists()) { f = File(dir, "$base ($i)$ext"); i++ }
        return f
    }

    private fun queryMeta(uri: Uri): Pair<String, Long> {
        var name = "file"
        var size = 0L
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
            if (c.moveToFirst()) {
                if (nameIdx >= 0) name = c.getString(nameIdx) ?: name
                if (sizeIdx >= 0 && !c.isNull(sizeIdx)) size = c.getLong(sizeIdx)
            }
        }
        // SAF providers often omit SIZE; fall back to the file descriptor's real length.
        if (size <= 0L) {
            size = runCatching {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
            }.getOrNull()?.coerceAtLeast(0L) ?: 0L
        }
        return name to size
    }
}
