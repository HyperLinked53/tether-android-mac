package com.conduit.android.features

import com.conduit.android.connection.Envelope
import com.conduit.android.connection.FeatureHandler
import com.conduit.android.connection.InputButtonPayload
import com.conduit.android.connection.InputKeyPayload
import com.conduit.android.connection.InputLongPressPayload
import com.conduit.android.connection.InputScrollPayload
import com.conduit.android.connection.InputSwipePayload
import com.conduit.android.connection.InputTapPayload
import com.conduit.android.connection.PeerSession
import com.conduit.android.connection.decode

/**
 * Applies remote-control events from the Mac (protocol/README §4.6, `input.*`) by delegating to
 * [ConduitInputService] (the AccessibilityService). If that service isn't enabled, events are
 * silently dropped — the Mac surfaces a "enable control on the phone" hint separately.
 */
class InputHandler : FeatureHandler {

    override fun handles(type: String) = type.startsWith("input.")

    override suspend fun onText(session: PeerSession, env: Envelope) {
        val svc = ConduitInputService.instance ?: return
        when (env.type) {
            "input.tap" -> env.decode<InputTapPayload>().let { svc.tap(it.x, it.y) }
            "input.longpress" -> env.decode<InputLongPressPayload>().let { svc.longPress(it.x, it.y) }
            "input.swipe" -> env.decode<InputSwipePayload>().let { svc.swipe(it.x1, it.y1, it.x2, it.y2, it.ms) }
            "input.scroll" -> env.decode<InputScrollPayload>().let { svc.scroll(it.x, it.y, it.dx, it.dy) }
            "input.key" -> env.decode<InputKeyPayload>().let { svc.key(it.text, it.code) }
            "input.button" -> env.decode<InputButtonPayload>().let { svc.button(it.name) }
        }
    }
}
