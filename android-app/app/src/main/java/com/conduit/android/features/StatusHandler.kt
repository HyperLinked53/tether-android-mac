package com.conduit.android.features

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.conduit.android.connection.BatteryInfo
import com.conduit.android.connection.DeviceIdentity
import com.conduit.android.connection.Envelope
import com.conduit.android.connection.FeatureHandler
import com.conduit.android.connection.PeerSession
import com.conduit.android.connection.StatusReportPayload
import com.conduit.android.connection.envelope

/** Replies to `status.query` with this phone's battery + identity. Smallest end-to-end feature. */
class StatusHandler(private val context: Context) : FeatureHandler {
    override fun handles(type: String) = type == "status.query"

    override suspend fun onText(session: PeerSession, env: Envelope) {
        session.send(envelope("status.report", report(), replyTo = env.id))
    }

    private fun report(): StatusReportPayload {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return StatusReportPayload(BatteryInfo(level, charging), DeviceIdentity.name(), "android")
    }
}
