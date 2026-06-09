package com.conduit.android.features

import com.conduit.android.connection.CameraErrorPayload
import com.conduit.android.connection.CameraReadyPayload
import com.conduit.android.connection.CameraServer
import com.conduit.android.connection.CameraStartPayload
import com.conduit.android.connection.CameraSwitchPayload
import com.conduit.android.connection.Envelope
import com.conduit.android.connection.FeatureHandler
import com.conduit.android.connection.MicCapture
import com.conduit.android.connection.MicServer
import com.conduit.android.connection.PeerSession
import com.conduit.android.connection.decode
import com.conduit.android.connection.envelope

/**
 * Handles `camera.*` messages (protocol/README §4.7). The Mac sends `camera.start`; we open the
 * phone's camera immediately (no consent dialog — just CAMERA runtime permission) and reply
 * `camera.ready {port, width, height}`. The Mac then connects to `host:port` and reads the H.264
 * stream from [CameraServer]. `camera.switch` rotates between front/back without stopping the
 * server. `camera.stop` tears down capture.
 *
 * Works identically over Wi-Fi and USB tethering since both appear as IP-reachable interfaces.
 */
class CameraHandler(
    private val capture: CameraCapture,
    private val server: CameraServer,
    private val micCapture: MicCapture,
    private val micServer: MicServer,
) : FeatureHandler {

    override fun handles(type: String) = type.startsWith("camera.")

    private fun bitrateForWidth(maxWidth: Int) = when {
        maxWidth >= 1920 -> 8_000_000
        maxWidth >= 1280 -> 4_000_000
        else -> 2_000_000
    }

    override suspend fun onText(session: PeerSession, env: Envelope) {
        when (env.type) {
            "camera.start" -> {
                val req = runCatching { env.decode<CameraStartPayload>() }.getOrNull()
                val facing = req?.facing ?: "front"
                val maxWidth = req?.maxWidth ?: 1280
                val bitrate = req?.bitrate ?: bitrateForWidth(maxWidth)

                if (!capture.hasPermission()) {
                    session.send(envelope("camera.error",
                        CameraErrorPayload("permission — grant Camera access to Tether on your phone")))
                    return
                }

                if (capture.isRunning && capture.facing == facing) {
                    session.send(envelope("camera.ready",
                        CameraReadyPayload(server.port, capture.width, capture.height, micServer.port)))
                    return
                }

                // Restart with the new facing if needed (stop is no-op if not running).
                capture.facing = facing
                if (capture.isRunning) { capture.stop(); micCapture.stop() }
                val ok = capture.start(maxWidth, bitrate)
                if (ok) {
                    micCapture.start()
                    session.send(envelope("camera.ready",
                        CameraReadyPayload(server.port, capture.width, capture.height, micServer.port)))
                } else {
                    session.send(envelope("camera.error",
                        CameraErrorPayload("could not open camera")))
                }
            }

            "camera.switch" -> {
                val p = env.decode<CameraSwitchPayload>()
                val maxWidth = p.maxWidth ?: 1280
                val bitrate = p.bitrate ?: bitrateForWidth(maxWidth)
                // switchCamera is blocking; run on the coroutine's IO thread (caller is on the
                // per-session channel, so no other messages for this session are processed concurrently).
                capture.switchCamera(p.facing, maxWidth, bitrate)
                if (capture.isRunning) {
                    session.send(envelope("camera.ready",
                        CameraReadyPayload(server.port, capture.width, capture.height, micServer.port)))
                } else {
                    session.send(envelope("camera.error",
                        CameraErrorPayload("camera switch failed")))
                }
            }

            "camera.stop" -> { capture.stop(); micCapture.stop() }
        }
    }
}
