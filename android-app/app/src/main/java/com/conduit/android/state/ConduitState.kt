package com.conduit.android.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** UI-facing models. */
data class PeerConnection(
    val deviceId: String,
    val name: String,
    val platform: String,
    val authenticated: Boolean,
)

enum class TransferDirection { INCOMING, OUTGOING }
enum class TransferStatus { OFFERED, ACTIVE, COMPLETED, FAILED, REJECTED }

data class TransferUi(
    val id: String,
    val name: String,
    val size: Long,
    val transferred: Long,
    val direction: TransferDirection,
    val status: TransferStatus,
    val error: String? = null,
) {
    val fraction: Float get() = if (size <= 0) 0f else (transferred.toFloat() / size).coerceIn(0f, 1f)
}

/** A pairing window the user can act on (QR + copyable URL). */
data class PairingInfo(val secret: String, val url: String, val qrPayload: String)

data class LogEvent(val at: Long, val text: String)

/**
 * Single source of truth shared between [ConnectionService] and the Compose UI.
 * Kept as an object because the service and UI live in the same process but different lifecycles.
 */
object ConduitState {
    val serverRunning = MutableStateFlow(false)
    val port = MutableStateFlow(0)
    val pairing = MutableStateFlow<PairingInfo?>(null)
    val peers = MutableStateFlow<List<PeerConnection>>(emptyList())
    val transfers = MutableStateFlow<List<TransferUi>>(emptyList())
    val log = MutableStateFlow<List<LogEvent>>(emptyList())

    fun setPeer(peer: PeerConnection) = peers.update { list ->
        list.filterNot { it.deviceId == peer.deviceId } + peer
    }

    fun removePeer(deviceId: String) = peers.update { list -> list.filterNot { it.deviceId == deviceId } }

    fun upsertTransfer(t: TransferUi) = transfers.update { list ->
        (list.filterNot { it.id == t.id } + t).sortedByDescending { it.id }
    }

    fun logEvent(text: String) = log.update { (it + LogEvent(System.currentTimeMillis(), text)).takeLast(200) }
}
