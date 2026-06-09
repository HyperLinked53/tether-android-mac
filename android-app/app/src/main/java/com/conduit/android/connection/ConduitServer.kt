package com.conduit.android.connection

import android.util.Base64
import android.util.Log
import com.conduit.android.state.ConduitState
import com.conduit.android.state.PeerConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

const val DEFAULT_PORT = 5333

/** A feature module that consumes a slice of the protocol's message types. */
interface FeatureHandler {
    fun handles(type: String): Boolean
    suspend fun onText(session: PeerSession, env: Envelope)
    suspend fun onBinary(session: PeerSession, frame: BinaryFrame) {}
    /** Channel this handler reads binary frames from, or null if it consumes no binary. */
    val binaryChannel: Byte? get() = null
}

/** One connected peer. Messages are funnelled through [inbox] so they're handled in arrival order. */
class PeerSession(val conn: WebSocket, private val scope: CoroutineScope) {
    @Volatile var device: DeviceInfo? = null
    @Volatile var authenticated = false
    @Volatile var sessionKey: ByteArray? = null // once set, frames are AES-GCM encrypted (§6)
    @Volatile var clientNonce: ByteArray? = null
    @Volatile var serverNonce: ByteArray? = null

    private val inbox = Channel<Inbound>(Channel.UNLIMITED)

    sealed interface Inbound {
        data class Text(val env: Envelope) : Inbound
        data class Binary(val frame: BinaryFrame) : Inbound
    }

    fun offer(msg: Inbound) { inbox.trySend(msg) }

    fun startConsumer(route: suspend (PeerSession, Inbound) -> Unit) = scope.launch {
        for (m in inbox) runCatching { route(this@PeerSession, m) }
            .onFailure { Log.e(TAG, "handler error", it) }
    }

    /** Encrypted (binary) once a session key is set; plaintext text during the handshake. */
    fun send(env: Envelope) = runCatching {
        if (!conn.isOpen) return@runCatching
        val key = sessionKey
        if (key != null) conn.send(ByteBuffer.wrap(ConduitCrypto.encrypt(key, env.encodeToText().toByteArray())))
        else conn.send(env.encodeToText())
    }

    /** Always plaintext — used for the handshake frames even after the key is computed. */
    fun sendPlain(env: Envelope) = runCatching { if (conn.isOpen) conn.send(env.encodeToText()) }

    fun sendError(code: String, message: String, fatal: Boolean = false, replyTo: String? = null) {
        send(envelope("error", ErrorPayload(code, message, fatal), replyTo))
        if (fatal) conn.close()
    }

    fun close() = inbox.close()

    private companion object { const val TAG = "PeerSession" }
}

/**
 * Embedded WebSocket server. Owns the connection lifecycle (hello → pair/auth → heartbeat) and
 * routes authenticated messages to [handlers] by type prefix / binary channel.
 */
class ConduitServer(
    port: Int,
    private val scope: CoroutineScope,
    private val pairing: PairingManager,
    private val selfInfo: DeviceInfo,
    private val handlers: List<FeatureHandler>,
) : WebSocketServer(InetSocketAddress(port)) {

    private val sessions = ConcurrentHashMap<WebSocket, PeerSession>()

    init {
        isReuseAddr = true
        connectionLostTimeout = 30 // built-in ping/pong watchdog (seconds)
    }

    override fun onStart() {
        Log.i(TAG, "server started on ${address.port}")
        ConduitState.serverRunning.value = true
        ConduitState.port.value = address.port.takeIf { it > 0 } ?: DEFAULT_PORT
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        val session = PeerSession(conn, scope)
        sessions[conn] = session
        session.startConsumer(::route)
        ConduitState.logEvent("Client connected from ${conn.remoteSocketAddress}")
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        val session = sessions.remove(conn)
        session?.close()
        session?.device?.let { ConduitState.removePeer(it.id) }
        ConduitState.logEvent("Client disconnected ($code)")
    }

    override fun onMessage(conn: WebSocket, message: String) {
        val session = sessions[conn] ?: return
        val env = runCatching { protocolJson.decodeFromString<Envelope>(message) }.getOrNull()
            ?: return session.sendError("bad_request", "malformed envelope")
        session.offer(PeerSession.Inbound.Text(env))
    }

    override fun onMessage(conn: WebSocket, message: ByteBuffer) {
        val session = sessions[conn] ?: return
        // Post-handshake, binary frames are AES-GCM encrypted envelopes (§6) — decrypt + route as text.
        val key = session.sessionKey ?: return
        val bytes = ByteArray(message.remaining()).also { message.get(it) }
        val json = runCatching { String(ConduitCrypto.decrypt(key, bytes)) }.getOrNull() ?: return
        val env = runCatching { protocolJson.decodeFromString<Envelope>(json) }.getOrNull() ?: return
        session.offer(PeerSession.Inbound.Text(env))
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        Log.e(TAG, "ws error", ex)
        if (conn == null) ConduitState.serverRunning.value = false
    }

    // ---- routing -----------------------------------------------------------

    private suspend fun route(session: PeerSession, inbound: PeerSession.Inbound) {
        when (inbound) {
            is PeerSession.Inbound.Text -> routeText(session, inbound.env)
            is PeerSession.Inbound.Binary -> routeBinary(session, inbound.frame)
        }
    }

    private suspend fun routeText(session: PeerSession, env: Envelope) {
        if (env.v != PROTOCOL_VERSION && env.type == "hello") {
            return session.sendError("version_unsupported", "need v$PROTOCOL_VERSION", fatal = true)
        }
        when (env.type) {
            "hello" -> handleHello(session, env)
            "pair.request" -> handlePairRequest(session, env)
            "ping" -> session.send(envelope("pong", replyTo = env.id))
            "pong" -> {} // heartbeat ack
            else -> {
                if (!session.authenticated) return session.sendError("unauthenticated", "say hello first")
                val handler = handlers.firstOrNull { it.handles(env.type) }
                if (handler != null) handler.onText(session, env)
                // unknown types are ignored per protocol §1
            }
        }
    }

    private suspend fun routeBinary(session: PeerSession, frame: BinaryFrame) {
        if (!session.authenticated) return
        handlers.firstOrNull { it.binaryChannel == frame.channel }?.onBinary(session, frame)
    }

    private fun handleHello(session: PeerSession, env: Envelope) {
        val hello = runCatching { env.decode<HelloPayload>() }.getOrNull()
            ?: return session.sendError("bad_request", "bad hello", fatal = true)
        session.device = hello.device
        val clientNonce = hello.nonce?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }
        val serverNonce = ConduitCrypto.randomBytes(16)
        session.clientNonce = clientNonce
        session.serverNonce = serverNonce

        val token = pairing.tokenFor(hello.device.id)
        if (token != null && clientNonce != null) {
            // Already paired: reply with our nonce (plaintext), then derive the session key so all
            // subsequent frames are encrypted. The client proves token possession by sending frames
            // we can decrypt.
            session.sendPlain(envelope("hello",
                HelloPayload(selfInfo, Base64.encodeToString(serverNonce, Base64.NO_WRAP)), replyTo = env.id))
            session.sessionKey = ConduitCrypto.hkdf(
                Base64.decode(token, Base64.NO_WRAP), clientNonce + serverNonce, "conduit-session-v1")
            authenticate(session, hello.device)
        } else {
            session.sendPlain(envelope("hello", HelloPayload(selfInfo), replyTo = env.id))
            session.sendPlain(envelope("pair.required", PairRequiredPayload(pairing.salt, pairing.iterations)))
            ConduitState.logEvent("Pairing required for ${hello.device.name}")
        }
    }

    private fun handlePairRequest(session: PeerSession, env: Envelope) {
        val device = session.device
            ?: return session.sendError("bad_request", "hello before pairing", fatal = true)
        val req = runCatching { env.decode<PairRequestPayload>() }.getOrNull()
            ?: return session.sendError("bad_request", "bad pair request")
        val secret = pairing.pairingSecret // capture before issueToken clears it
        if (secret == null || !pairing.verifyProof(device.id, req.proof)) {
            return session.sendError("pair_failed", "incorrect pairing code", fatal = true, replyTo = env.id)
        }
        val token = pairing.issueToken(device.id)
        val clientNonce = session.clientNonce ?: ByteArray(0)
        val serverNonce = session.serverNonce ?: ConduitCrypto.randomBytes(16).also { session.serverNonce = it }
        // Encrypt the token under a key derived from the pairing secret so it isn't sent in the clear.
        val pairKey = ConduitCrypto.hkdf(secret.toByteArray(), Base64.decode(pairing.salt, Base64.NO_WRAP), "conduit-pair-v1")
        val tokenEnc = Base64.encodeToString(ConduitCrypto.encrypt(pairKey, token.toByteArray()), Base64.NO_WRAP)
        session.sendPlain(envelope("pair.ok",
            PairOkPayload(tokenEnc, Base64.encodeToString(serverNonce, Base64.NO_WRAP)), replyTo = env.id))
        session.sessionKey = ConduitCrypto.hkdf(
            Base64.decode(token, Base64.NO_WRAP), clientNonce + serverNonce, "conduit-session-v1")
        ConduitState.pairing.value = null
        authenticate(session, device)
    }

    /** Called once per session immediately after authentication. Used by features that need to push
     *  their current state to a freshly connected Mac (media info, status, etc.). */
    var onPeerAuthenticated: (() -> Unit)? = null

    private fun authenticate(session: PeerSession, device: DeviceInfo) {
        session.authenticated = true
        ConduitState.setPeer(PeerConnection(device.id, device.name, device.platform, true))
        ConduitState.logEvent("${device.name} authenticated")
        onPeerAuthenticated?.invoke()
    }

    /** Send an envelope to every authenticated peer (used by push features: notifications, clipboard). */
    fun broadcast(env: Envelope) = sessions.values.filter { it.authenticated }.forEach { it.send(env) }

    /** The first authenticated session, if any (used by UI-initiated actions like sending a file). */
    fun primarySession(): PeerSession? = sessions.values.firstOrNull { it.authenticated }

    fun shutdownGracefully() {
        sessions.keys.forEach { runCatching { it.close() } }
        sessions.clear()
        ConduitState.peers.value = emptyList()
        ConduitState.serverRunning.value = false
    }

    private companion object { const val TAG = "TetherServer" }
}
