package com.conduit.android.features

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.conduit.android.connection.ClipPushPayload
import com.conduit.android.connection.ConnectionService
import com.conduit.android.connection.Envelope
import com.conduit.android.connection.FeatureHandler
import com.conduit.android.connection.PeerSession
import com.conduit.android.connection.decode
import com.conduit.android.connection.envelope

/**
 * Two-way clipboard sync (protocol `clip.push`).
 *
 * Inbound `clip.push` (Mac → Android) is written to the Android clipboard — always works.
 *
 * Outbound (Android → Mac): a [ClipboardManager.OnPrimaryClipChangedListener] broadcasts local copies
 * to the Mac. **Since Android 10, apps can only read the clipboard while in the foreground**, so this
 * captures copies made while Conduit is open; [pushCurrent] is also called from MainActivity's
 * onResume so a copy made elsewhere syncs when you next open Conduit. A `lastValue` guard prevents
 * the set→change→resend echo loop.
 */
class ClipboardHandler(private val context: Context) : FeatureHandler {

    private val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    @Volatile private var lastValue: String? = null
    private val listener = ClipboardManager.OnPrimaryClipChangedListener { pushCurrent() }

    override fun handles(type: String) = type == "clip.push"

    fun registerListener() {
        runCatching { cm.addPrimaryClipChangedListener(listener) }
    }

    fun unregisterListener() {
        runCatching { cm.removePrimaryClipChangedListener(listener) }
    }

    override suspend fun onText(session: PeerSession, env: Envelope) {
        val text = env.decode<ClipPushPayload>().text
        lastValue = text // remember before writing so the resulting clip change doesn't echo back
        cm.setPrimaryClip(ClipData.newPlainText("Tether", text))
    }

    /** Read the current clipboard and broadcast it to peers if it changed (foreground-only on 10+). */
    fun pushCurrent() {
        val clip = runCatching { cm.primaryClip }.getOrNull() ?: return
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0).coerceToText(context)?.toString() ?: return
        if (text.isEmpty() || text == lastValue) return
        lastValue = text
        ConnectionService.broadcast(envelope("clip.push", ClipPushPayload(text)))
    }
}
