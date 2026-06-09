package com.conduit.android.features

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Lets the Mac drive the phone while mirroring (protocol/README §4.6, `input.*`). A non-system app
 * can't inject input into other apps, so control goes through this AccessibilityService:
 * `dispatchGesture` for taps/swipes/scroll, global actions for Back/Home/Recents, and focused-node
 * text editing for the keyboard. The user enables it once in Settings → Accessibility.
 *
 * System-bound lifecycle (separate from [com.conduit.android.connection.ConnectionService]); the
 * `input.*` feature handler reaches it through [instance]. Coordinates arrive normalized [0,1] and
 * are scaled to the real display here.
 */
class ConduitInputService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())

    // We can't read a field's existing text reliably (some apps, e.g. WhatsApp, expose their
    // placeholder as the node's `text` with no hint flag), so we track what the user has typed since
    // focusing a field. Reset it only when input focus actually moves to a different view — signalled
    // by TYPE_VIEW_FOCUSED, not by node identity (findFocus() returns a fresh node each call, and
    // WhatsApp's field has no viewIdResourceName, so node-based keys are unstable).
    @Volatile private var startFresh = true
    private var lastFocusBounds: Rect? = null
    private var typedBuffer: String = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "input accessibility service connected")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // When input focus moves to a different view, the next keystroke starts a fresh field. (Our
        // own setText fires TEXT_CHANGED/SELECTION_CHANGED, not FOCUSED, so typing keeps accumulating.)
        if (event?.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            val r = Rect()
            event.source?.getBoundsInScreen(r)
            if (r != lastFocusBounds) { startFresh = true; lastFocusBounds = r }
        }
    }

    override fun onInterrupt() {}

    // ---- gestures ----------------------------------------------------------

    fun tap(xN: Float, yN: Float) = strokeAt(xN, yN, 60)
    fun longPress(xN: Float, yN: Float) = strokeAt(xN, yN, 600)

    private fun strokeAt(xN: Float, yN: Float, durationMs: Long) {
        val (w, h) = displaySize()
        val path = Path().apply { moveTo((xN * w), (yN * h)) }
        dispatch(path, 0, durationMs)
    }

    fun swipe(x1N: Float, y1N: Float, x2N: Float, y2N: Float, ms: Int) {
        val (w, h) = displaySize()
        val path = Path().apply {
            moveTo(x1N * w, y1N * h)
            lineTo(x2N * w, y2N * h)
        }
        dispatch(path, 0, ms.coerceIn(40, 1500).toLong())
    }

    /** Mouse wheel / trackpad: turn a scroll delta into a swipe at the cursor (opposite direction). */
    fun scroll(xN: Float, yN: Float, dx: Float, dy: Float) {
        val (w, h) = displaySize()
        val cx = xN * w
        val cy = yN * h
        // Scrolling down (content moves up) = finger swipes up. Scale the wheel delta to pixels.
        val sx = (-dx * SCROLL_GAIN).coerceIn(-w * 0.45f, w * 0.45f)
        val sy = (-dy * SCROLL_GAIN).coerceIn(-h * 0.45f, h * 0.45f)
        val path = Path().apply {
            moveTo(cx, cy)
            lineTo((cx + sx).coerceIn(1f, w - 1f), (cy + sy).coerceIn(1f, h - 1f))
        }
        dispatch(path, 0, 120)
    }

    private fun dispatch(path: Path, startTime: Long, durationMs: Long) {
        if (path.isEmpty) return
        main.post {
            runCatching {
                val stroke = GestureDescription.StrokeDescription(path, startTime, durationMs.coerceAtLeast(1))
                dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
            }.onFailure { Log.e(TAG, "dispatchGesture failed", it) }
        }
    }

    // ---- navigation buttons ------------------------------------------------

    fun button(name: String) = main.post {
        val action = when (name) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            else -> return@post
        }
        runCatching { performGlobalAction(action) }
    }

    // ---- keyboard (focused editable node) ----------------------------------

    fun key(text: String?, code: String?) = main.post {
        when {
            code == "enter" -> imeEnterOrNewline()
            code == "backspace" -> editFocused { cur -> if (cur.isEmpty()) cur else cur.dropLast(1) }
            code == "space" -> editFocused { cur -> "$cur " }
            !text.isNullOrEmpty() -> editFocused { cur -> cur + text }
        }
    }

    private fun imeEnterOrNewline() {
        val node = focusedEditable() ?: return
        // Chat apps (WhatsApp, etc.) send via a Send button, not the Enter key — tap it if present.
        val send = findSendButton()
        if (send != null) {
            send.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            startFresh = true; typedBuffer = "" // message sent → field clears
            return
        }
        if (node.isMultiLine) {
            editFocused { cur -> "$cur\n" } // newline, kept in sync with our buffer
            return
        }
        // Single-line field: trigger its editor action (search / go / done).
        startFresh = true; typedBuffer = ""
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
    }

    /** A visible, clickable node labelled "send" in the active window (the chat send button). */
    private fun findSendButton(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var budget = 500
        while (queue.isNotEmpty() && budget-- > 0) {
            val n = queue.removeFirst()
            val label = (n.contentDescription ?: n.text)?.toString()?.lowercase()
            if (n.isClickable && n.isVisibleToUser && !n.isEditable &&
                label != null && label.contains("send")
            ) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    private fun editFocused(transform: (String) -> String) {
        val node = focusedEditable() ?: return
        // On the first keystroke after focusing a field, start fresh (replace what's shown, since a
        // field's existing text can't be read reliably); subsequent keystrokes accumulate.
        if (startFresh) {
            startFresh = false
            typedBuffer = ""
        }
        typedBuffer = transform(typedBuffer)
        setNodeText(node, typedBuffer)
    }

    private fun setNodeText(node: AccessibilityNodeInfo, value: String) {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            val sel = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, value.length)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, value.length)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
        }
    }

    private fun focusedEditable(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        runCatching { focused.refresh() } // the findFocus node can be stale (esp. isShowingHintText)
        return if (focused.isEditable) focused else null
    }

    // ---- helpers -----------------------------------------------------------

    private fun displaySize(): Pair<Float, Float> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            b.width().toFloat() to b.height().toFloat()
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
            m.widthPixels.toFloat() to m.heightPixels.toFloat()
        }
    }

    companion object {
        private const val TAG = "ConduitInput"
        private const val SCROLL_GAIN = 8f // wheel delta → pixels

        @Volatile var instance: ConduitInputService? = null
            private set

        fun isEnabled() = instance != null
    }
}
