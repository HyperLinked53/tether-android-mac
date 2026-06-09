package com.conduit.android.features

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import android.util.Log
import android.view.textclassifier.ConversationActions
import android.view.textclassifier.TextClassificationManager
import com.conduit.android.connection.ConnectionService
import com.conduit.android.connection.NotifPostedPayload
import com.conduit.android.connection.NotifRemovedPayload
import com.conduit.android.connection.envelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Mirrors Android notifications to the Mac, enriched with the source app's icon + label, and —
 * for replyable (messaging) notifications — on-device smart-reply suggestions. Replies coming back
 * from the Mac (`notif.reply`) are injected into the original notification's RemoteInput and fired
 * via its PendingIntent, so the source app delivers them.
 */
class ConduitNotificationListener : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val iconCache = ConcurrentHashMap<String, String>() // package -> base64 PNG

    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        instance = null
    }

    override fun onDestroy() {
        instance = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return // skip persistent
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        val pkg = sbn.packageName
        val canReply = replyAction(sbn.notification) != null

        scope.launch {
            val suggestions = if (canReply && text.isNotBlank()) smartReplies(text) else emptyList()
            ConnectionService.broadcast(
                envelope(
                    "notif.posted",
                    NotifPostedPayload(
                        key = sbn.key,
                        app = pkg,
                        appName = appLabel(pkg),
                        title = title,
                        text = text,
                        postedAt = sbn.postTime,
                        iconPng = appIcon(pkg),
                        canReply = canReply,
                        suggestions = suggestions,
                    ),
                ),
            )
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        ConnectionService.broadcast(envelope("notif.removed", NotifRemovedPayload(sbn.key)))
    }

    /** Send [text] as a reply to the notification identified by [key]. Returns true if dispatched. */
    fun reply(key: String, text: String): Boolean {
        val sbn = runCatching { activeNotifications }.getOrNull()?.firstOrNull { it.key == key } ?: return false
        val (action, remoteInput) = replyAction(sbn.notification) ?: return false
        val intent = Intent()
        val results = Bundle().apply { putCharSequence(remoteInput.resultKey, text) }
        RemoteInput.addResultsToIntent(action.remoteInputs, intent, results)
        return runCatching { action.actionIntent.send(this, 0, intent); true }.getOrElse {
            Log.e(TAG, "reply send failed", it); false
        }
    }

    private fun replyAction(n: Notification): Pair<Notification.Action, RemoteInput>? {
        val actions = n.actions ?: return null
        for (a in actions) {
            val ri = a.remoteInputs?.firstOrNull { it.allowFreeFormInput } ?: continue
            return a to ri
        }
        return null
    }

    private fun smartReplies(text: String): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
        return runCatching {
            val classifier = (getSystemService(TextClassificationManager::class.java)).textClassifier
            val message = ConversationActions.Message.Builder(ConversationActions.Message.PERSON_USER_OTHERS)
                .setText(text)
                .build()
            val request = ConversationActions.Request.Builder(listOf(message))
                .setMaxSuggestions(3)
                .build()
            classifier.suggestConversationActions(request).conversationActions
                .mapNotNull { it.textReply?.toString() }
                .filter { it.isNotBlank() }
                .distinct()
                .take(3)
        }.getOrElse { emptyList() }
    }

    private fun appLabel(pkg: String): String = runCatching {
        val pm = packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun appIcon(pkg: String): String? = iconCache.getOrPut(pkg) {
        runCatching {
            val drawable = packageManager.getApplicationIcon(pkg)
            val bmp = drawableToBitmap(drawable, ICON_PX)
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }.getOrDefault("")
    }.ifEmpty { null }

    private fun drawableToBitmap(drawable: Drawable, size: Int): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return Bitmap.createScaledBitmap(drawable.bitmap, size, size, true)
        }
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        return bmp
    }

    companion object {
        private const val TAG = "ConduitNotif"
        private const val ICON_PX = 96

        @Volatile private var instance: ConduitNotificationListener? = null

        /** Dispatch a reply to a mirrored notification. Returns true if the listener handled it. */
        fun reply(key: String, text: String): Boolean = instance?.reply(key, text) ?: false
    }
}
