package com.conduit.android.features

import android.content.Context
import android.os.Build
import android.os.Environment
import com.conduit.android.connection.Envelope
import com.conduit.android.connection.FeatureHandler
import com.conduit.android.connection.FileHttpServer
import com.conduit.android.connection.FsEntry
import com.conduit.android.connection.FsListPayload
import com.conduit.android.connection.FsListingPayload
import com.conduit.android.connection.FsPullPayload
import com.conduit.android.connection.FsPullReadyPayload
import com.conduit.android.connection.PeerSession
import com.conduit.android.connection.decode
import com.conduit.android.connection.envelope
import java.io.File
import java.io.FileInputStream
import java.net.URLConnection
import java.util.UUID

/**
 * Lets the Mac browse the phone's shared storage and pull files it finds (protocol/README §4.1).
 * Listing is metadata-only over the WebSocket; bytes reuse [FileHttpServer] — `fs.pull` just arms a
 * download (mints a `transferId` and registers a [FileHttpServer.DownloadSource]) exactly like
 * [FileTransferHandler.sendFile], and the Mac's `file.complete` (handled there) unregisters it.
 *
 * Browsing the whole tree needs All-files-access (`MANAGE_EXTERNAL_STORAGE`); without it `fs.list`
 * replies with `error="permission"` so the Mac can prompt.
 */
class FileBrowseHandler(
    private val context: Context,
    private val httpServer: FileHttpServer,
) : FeatureHandler {

    override fun handles(type: String) = type.startsWith("fs.")

    override suspend fun onText(session: PeerSession, env: Envelope) {
        when (env.type) {
            "fs.list" -> onList(session, env.decode())
            "fs.pull" -> onPull(session, env.decode())
        }
    }

    private fun onList(session: PeerSession, req: FsListPayload) {
        if (!hasAllFilesAccess()) {
            session.send(envelope("fs.listing", FsListingPayload(req.path, emptyList(), "permission")))
            return
        }
        val dir = if (req.path.isBlank()) Environment.getExternalStorageDirectory() else File(req.path)
        if (!dir.isDirectory) {
            session.send(envelope("fs.listing", FsListingPayload(dir.absolutePath, emptyList(), "not_a_directory")))
            return
        }
        val entries = (dir.listFiles() ?: emptyArray())
            .map { f -> FsEntry(f.name, f.absolutePath, f.isDirectory, if (f.isDirectory) 0 else f.length(), f.lastModified()) }
            .sortedWith(compareByDescending<FsEntry> { it.isDir }.thenBy { it.name.lowercase() })
        session.send(envelope("fs.listing", FsListingPayload(dir.absolutePath, entries)))
    }

    private fun onPull(session: PeerSession, req: FsPullPayload) {
        val file = File(req.path)
        if (!file.isFile || !file.canRead()) {
            session.send(envelope("fs.error", com.conduit.android.connection.FsErrorPayload(req.path, "cannot read file")))
            return
        }
        val transferId = UUID.randomUUID().toString()
        httpServer.registerDownload(
            transferId,
            FileHttpServer.DownloadSource(file.length()) { FileInputStream(file) },
        )
        val mime = URLConnection.guessContentTypeFromName(file.name) ?: "application/octet-stream"
        session.send(
            envelope(
                "fs.pullReady",
                FsPullReadyPayload(transferId, httpServer.port, file.name, file.length(), mime),
            ),
        )
    }

    private fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else true // pre-11: legacy READ_EXTERNAL_STORAGE covers shared storage
}
