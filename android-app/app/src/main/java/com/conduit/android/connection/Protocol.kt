package com.conduit.android.connection

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import java.nio.ByteBuffer
import java.util.UUID

/**
 * Kotlin mirror of `protocol/types.ts`. Keep the two in sync.
 *
 * Control frames are decoded into [Envelope] with the payload left as a raw [JsonElement];
 * each feature handler decodes the concrete payload it cares about. Binary frames are parsed
 * by [BinaryFrame].
 */

const val PROTOCOL_VERSION = 2 // v2 adds LAN session encryption (protocol/README §6)
const val SERVICE_TYPE = "_tether._tcp"

object Channels {
    const val FILE_CHUNK: Byte = 0x01
    const val SCREEN_VIDEO: Byte = 0x02 // reserved (roadmap)
}

val protocolJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

@Serializable
data class Envelope(
    val v: Int = PROTOCOL_VERSION,
    val id: String = UUID.randomUUID().toString(),
    val type: String,
    val replyTo: String? = null,
    val payload: JsonElement = JsonObject(emptyMap()),
)

/** Build an [Envelope] whose payload is the serialized [payload] object. */
inline fun <reified T> envelope(type: String, payload: T, replyTo: String? = null): Envelope =
    Envelope(
        type = type,
        replyTo = replyTo,
        payload = protocolJson.encodeToJsonElement(payload),
    )

fun envelope(type: String, replyTo: String? = null): Envelope =
    Envelope(type = type, replyTo = replyTo)

inline fun <reified T> Envelope.decode(): T = protocolJson.decodeFromJsonElement(payload)

fun Envelope.encodeToText(): String = protocolJson.encodeToString(this)

// --------------------------------------------------------------------------
// Payloads (subset Android needs; superset documented in protocol/types.ts)
// --------------------------------------------------------------------------

@Serializable
data class DeviceInfo(
    val id: String,
    val name: String,
    val platform: String,
    val appVersion: String,
)

@Serializable
data class HelloPayload(val device: DeviceInfo, val nonce: String? = null)

@Serializable
data class PairRequiredPayload(val salt: String, val iterations: Int)

@Serializable
data class PairRequestPayload(val proof: String)

@Serializable
data class PairOkPayload(val tokenEnc: String, val nonce: String)

@Serializable
data class ErrorPayload(val code: String, val message: String, val fatal: Boolean = false)

@Serializable
data class FileOfferPayload(
    val transferId: String,
    val name: String,
    val size: Long,
    val mime: String,
    val httpPort: Int? = null,
)

@Serializable
data class FileAcceptPayload(val transferId: String, val httpPort: Int? = null)

@Serializable
data class TransferIdPayload(val transferId: String)

@Serializable
data class FileRejectPayload(val transferId: String, val reason: String)

@Serializable
data class FileProgressPayload(val transferId: String, val received: Long)

@Serializable
data class FileCompletePayload(val transferId: String, val sha256: String? = null)

@Serializable
data class FileErrorPayload(val transferId: String, val message: String)

// Remote file browsing (Mac browses the phone's storage). See protocol/types.ts.
@Serializable
data class FsEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val modified: Long,
)

@Serializable
data class FsListPayload(val path: String = "")

@Serializable
data class FsListingPayload(
    val path: String,
    val entries: List<FsEntry>,
    val error: String? = null,
)

@Serializable
data class FsPullPayload(val path: String)

@Serializable
data class FsPullReadyPayload(
    val transferId: String,
    val httpPort: Int,
    val name: String,
    val size: Long,
    val mime: String,
)

@Serializable
data class FsErrorPayload(val path: String? = null, val message: String)

// Photo gallery (Mac browses the phone's photos). See protocol/types.ts.
@Serializable
data class PhotoItem(
    val id: Long,
    val name: String,
    val date: Long,
    val width: Int,
    val height: Int,
    val thumbUrl: String,
)

@Serializable
data class PhotosListPayload(val limit: Int? = null)

@Serializable
data class PhotosListingPayload(val photos: List<PhotoItem>)

@Serializable
data class PhotosPullPayload(val id: Long)

@Serializable
data class PhotosPullReadyPayload(
    val id: Long,
    val transferId: String,
    val httpPort: Int,
    val name: String,
    val size: Long,
    val mime: String,
)

@Serializable
data class BatteryInfo(val level: Int, val charging: Boolean)

@Serializable
data class StatusReportPayload(val battery: BatteryInfo, val name: String, val platform: String)

@Serializable
data class ClipPushPayload(val text: String)

@Serializable
data class NotifPostedPayload(
    val key: String,
    val app: String,
    val appName: String,
    val title: String,
    val text: String,
    val postedAt: Long,
    val iconPng: String? = null,
    val canReply: Boolean = false,
    val suggestions: List<String> = emptyList(),
)

@Serializable
data class NotifRemovedPayload(val key: String)

@Serializable
data class NotifReplyPayload(val key: String, val text: String)

@Serializable
data class ScreenStartPayload(
    val maxWidth: Int? = null,
    val maxHeight: Int? = null,
    val bitrate: Int? = null,
    val audio: String? = null, // "phone" | "mac"
)

@Serializable
data class ScreenReadyPayload(val port: Int, val width: Int, val height: Int, val audioPort: Int)

@Serializable
data class ScreenErrorPayload(val message: String)

@Serializable
data class ScreenAudioPayload(val target: String) // "phone" | "mac"

// Phone-as-webcam. Same TCP streaming pattern as screen mirroring (port 5337).
@Serializable
data class CameraStartPayload(
    val facing: String? = null, // "front" | "back"
    val maxWidth: Int? = null,
    val bitrate: Int? = null,
)

@Serializable
data class CameraReadyPayload(val port: Int, val width: Int, val height: Int, val micPort: Int = 0)

@Serializable
data class CameraErrorPayload(val message: String)

@Serializable
data class CameraSwitchPayload(
    val facing: String,          // "front" | "back"
    val maxWidth: Int? = null,
    val bitrate: Int? = null,
)

// Remote control — coordinates normalized [0,1] relative to the mirrored screen.
@Serializable
data class InputTapPayload(val x: Float, val y: Float)

@Serializable
data class InputLongPressPayload(val x: Float, val y: Float)

@Serializable
data class InputSwipePayload(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val ms: Int)

@Serializable
data class InputScrollPayload(val x: Float, val y: Float, val dx: Float, val dy: Float)

@Serializable
data class InputKeyPayload(val text: String? = null, val code: String? = null)

@Serializable
data class InputButtonPayload(val name: String) // "back" | "home" | "recents"

@Serializable
data class MediaInfoPayload(
    val title: String,
    val artist: String,
    val album: String,
    val appName: String,
    val isPlaying: Boolean,
    val duration: Long,
    val position: Long,
    val lastUpdateTime: Long,
)

@Serializable
data class MediaControlPayload(val action: String) // "play_pause" | "next" | "previous"

// --------------------------------------------------------------------------
// Binary data frame (see protocol/README.md §3)
// --------------------------------------------------------------------------

const val BINARY_HEADER_SIZE = 21
private const val BINARY_VERSION: Byte = 0x01

data class BinaryFrame(val channel: Byte, val streamId: UUID, val payload: ByteBuffer) {
    companion object {
        fun encode(channel: Byte, streamId: UUID, data: ByteArray, off: Int, len: Int): ByteBuffer {
            val buf = ByteBuffer.allocate(BINARY_HEADER_SIZE + len)
            buf.put(BINARY_VERSION)
            buf.put(channel)
            buf.putLong(streamId.mostSignificantBits)
            buf.putLong(streamId.leastSignificantBits)
            buf.put(0); buf.put(0); buf.put(0) // reserved
            buf.put(data, off, len)
            buf.flip()
            return buf
        }

        /** Returns null if the buffer is too short or the version byte is wrong. */
        fun decode(buf: ByteBuffer): BinaryFrame? {
            if (buf.remaining() < BINARY_HEADER_SIZE) return null
            val b = buf.duplicate()
            if (b.get() != BINARY_VERSION) return null
            val channel = b.get()
            val streamId = UUID(b.long, b.long)
            b.position(b.position() + 3) // skip reserved
            return BinaryFrame(channel, streamId, b.slice())
        }
    }
}
