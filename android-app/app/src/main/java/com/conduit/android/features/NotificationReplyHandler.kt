package com.conduit.android.features

import com.conduit.android.connection.Envelope
import com.conduit.android.connection.FeatureHandler
import com.conduit.android.connection.NotifReplyPayload
import com.conduit.android.connection.PeerSession
import com.conduit.android.connection.decode

/** Routes `notif.reply` from the Mac into the notification listener's RemoteInput reply path. */
class NotificationReplyHandler : FeatureHandler {
    override fun handles(type: String) = type == "notif.reply"

    override suspend fun onText(session: PeerSession, env: Envelope) {
        val payload = env.decode<NotifReplyPayload>()
        ConduitNotificationListener.reply(payload.key, payload.text)
    }
}
