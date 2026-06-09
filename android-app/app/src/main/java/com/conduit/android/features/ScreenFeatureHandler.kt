package com.conduit.android.features

import com.conduit.android.connection.Envelope
import com.conduit.android.connection.FeatureHandler
import com.conduit.android.connection.PeerSession
import com.conduit.android.connection.ScreenAudioPayload
import com.conduit.android.connection.ScreenAudioServer
import com.conduit.android.connection.ScreenCaptureManager
import com.conduit.android.connection.ScreenReadyPayload
import com.conduit.android.connection.ScreenServer
import com.conduit.android.connection.ScreenStartPayload
import com.conduit.android.connection.decode
import com.conduit.android.connection.envelope

/**
 * Coordinates screen mirroring (protocol/README §4.6). The WebSocket only coordinates; H.264 bytes
 * stream over [ScreenServer] and (optionally) PCM audio over [ScreenAudioServer]. Capture consent is
 * granted on the phone, so `screen.start` from the Mac either replies `screen.ready` immediately
 * (already capturing) or kicks off the on-phone consent prompt via [requestConsent]; the actual
 * `screen.ready` is broadcast once capture starts. `screen.audio` flips audio routing live.
 */
class ScreenFeatureHandler(
    private val capture: ScreenCaptureManager,
    private val server: ScreenServer,
    private val audioServer: ScreenAudioServer,
    private val requestConsent: () -> Unit,
    private val stopCapture: () -> Unit,
) : FeatureHandler {

    override fun handles(type: String) = type.startsWith("screen.")

    override suspend fun onText(session: PeerSession, env: Envelope) {
        when (env.type) {
            "screen.start" -> {
                val req = runCatching { env.decode<ScreenStartPayload>() }.getOrNull()
                capture.audioTarget = req?.audio ?: "phone"
                if (capture.isRunning) {
                    session.send(envelope("screen.ready",
                        ScreenReadyPayload(server.port, capture.width, capture.height, audioServer.port)))
                } else {
                    requestConsent()
                }
            }
            "screen.audio" -> {
                val p = env.decode<ScreenAudioPayload>()
                capture.audioTarget = p.target
            }
            "screen.stop" -> stopCapture()
        }
    }
}
