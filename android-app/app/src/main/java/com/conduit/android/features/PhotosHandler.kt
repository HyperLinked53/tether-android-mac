package com.conduit.android.features

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Size
import com.conduit.android.connection.Envelope
import com.conduit.android.connection.FeatureHandler
import com.conduit.android.connection.FileHttpServer
import com.conduit.android.connection.PeerSession
import com.conduit.android.connection.PhotoItem
import com.conduit.android.connection.PhotosListPayload
import com.conduit.android.connection.PhotosListingPayload
import com.conduit.android.connection.PhotosPullPayload
import com.conduit.android.connection.PhotosPullReadyPayload
import com.conduit.android.connection.decode
import com.conduit.android.connection.envelope
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * Browse the phone's photos (MediaStore). Thumbnails and full images stream over [FileHttpServer]
 * (Android hosts, the Mac fetches natively) — same pattern as file transfer / MMS images, so bytes
 * never cross the WebSocket. `photos.list` arms a lazy thumbnail per photo; `photos.pull` arms the
 * full image (for download / drag-out). `thumbUrl` uses a `%HOST%` placeholder the Mac rewrites.
 */
class PhotosHandler(
    private val context: Context,
    private val httpServer: FileHttpServer,
) : FeatureHandler {

    private val resolver get() = context.contentResolver
    private val collection: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    override fun handles(type: String) = type.startsWith("photos.")

    override suspend fun onText(session: PeerSession, env: Envelope) {
        when (env.type) {
            "photos.list" -> session.send(envelope("photos.listing", PhotosListingPayload(list(env.decode<PhotosListPayload>().limit ?: 300))))
            "photos.pull" -> pull(session, env.decode<PhotosPullPayload>().id)
        }
    }

    private fun list(limit: Int): List<PhotoItem> {
        val out = mutableListOf<PhotoItem>()
        val proj = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
        )
        runCatching {
            resolver.query(collection, proj, null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
                val idIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dateIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val wIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                val hIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
                while (c.moveToNext() && out.size < limit) {
                    val id = c.getLong(idIdx)
                    val transferId = UUID.randomUUID().toString()
                    // Lazy: the thumbnail is only generated when the Mac actually requests this URL.
                    httpServer.registerDownload(transferId, FileHttpServer.DownloadSource(0L) { thumbnailStream(id) })
                    out += PhotoItem(
                        id = id,
                        name = c.getString(nameIdx) ?: "photo_$id.jpg",
                        date = c.getLong(dateIdx) * 1000L, // DATE_ADDED is seconds
                        width = c.getInt(wIdx),
                        height = c.getInt(hIdx),
                        thumbUrl = "http://%HOST%:${httpServer.port}/download/$transferId",
                    )
                }
            }
        }
        return out
    }

    private fun thumbnailStream(id: Long): java.io.InputStream {
        val uri = ContentUris.withAppendedId(collection, id)
        val bmp: Bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.loadThumbnail(uri, Size(320, 320), null)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Thumbnails.getThumbnail(resolver, id, MediaStore.Images.Thumbnails.MINI_KIND, null)
                ?: error("no thumbnail")
        }
        val baos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, baos)
        return ByteArrayInputStream(baos.toByteArray())
    }

    private fun pull(session: PeerSession, id: Long) {
        val uri = ContentUris.withAppendedId(collection, id)
        var name = "photo_$id.jpg"
        var size = 0L
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        val mime = resolver.getType(uri) ?: "image/jpeg"
        val transferId = UUID.randomUUID().toString()
        httpServer.registerDownload(
            transferId,
            FileHttpServer.DownloadSource(size) { resolver.openInputStream(uri) ?: error("cannot open photo $id") },
        )
        session.send(envelope("photos.pullReady", PhotosPullReadyPayload(id, transferId, httpServer.port, name, size, mime)))
    }
}
