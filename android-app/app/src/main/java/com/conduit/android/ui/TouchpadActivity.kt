package com.conduit.android.ui

import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.conduit.android.connection.ConnectionService
import com.conduit.android.connection.envelope
import com.conduit.android.ui.theme.ConduitTheme
import kotlinx.serialization.Serializable
import kotlin.math.abs

@Serializable data class TrackpadMovePayload(val dx: Float, val dy: Float)
@Serializable data class TrackpadScrollPayload(val dx: Float, val dy: Float)
@Serializable data class TrackpadTextPayload(val text: String)
@Serializable data class TrackpadCodePayload(val code: String)

class TouchpadActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ConduitTheme { TouchpadScreen(onBack = { finish() }) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TouchpadScreen(onBack: () -> Unit) {
    var kbVisible by remember { mutableStateOf(false) }
    var keyboardView by remember { mutableStateOf<KeyboardCaptureView?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Remote Cursor") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = {
                        kbVisible = !kbVisible
                        if (kbVisible) keyboardView?.showKeyboard()
                        else keyboardView?.hideKeyboard()
                    }) { Text(if (kbVisible) "Hide keyboard" else "⌨ Keyboard") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "1 finger — move  •  2 fingers — scroll  •  Tap — click  •  2-finger tap — right-click  •  Double-tap — double-click",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            if (kbVisible) {
                // KeyboardCaptureView is a focusable View whose InputConnection intercepts
                // every soft-keyboard event directly — no text state, no diff, no sentinel.
                AndroidView(
                    factory = { ctx ->
                        KeyboardCaptureView(ctx).also {
                            keyboardView = it
                            it.showKeyboard()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            AndroidView(
                factory = { ctx -> TouchpadView(ctx) },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
    }
}

/**
 * An invisible-but-focusable View whose InputConnection intercepts every soft-keyboard event
 * and forwards it directly to the Mac as trackpad.key messages — no text state, no diff,
 * no sentinel character, no Compose recomposition in the hot path.
 */
private class KeyboardCaptureView(context: Context) : View(context) {

    private val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        setBackgroundColor(0xFF2B2D30.toInt())
        setOnClickListener { showKeyboard() }
    }

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo) =
        object : BaseInputConnection(this, false) {
            // Fired by soft keyboards for normal text input (letters, numbers, symbols).
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val s = text?.toString() ?: return true
                if (s.isNotEmpty()) send("trackpad.key", TrackpadTextPayload(s))
                return true
            }

            // Some keyboards send composing text in stages (e.g. CJK predictive input).
            // Commit the final composed string when it's ready.
            override fun commitCompletion(text: android.view.inputmethod.CompletionInfo?): Boolean {
                text?.text?.toString()?.takeIf { it.isNotEmpty() }
                    ?.let { send("trackpad.key", TrackpadTextPayload(it)) }
                return true
            }

            // Backspace is reported here for most soft keyboards.
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength.coerceIn(0, 20)) {
                    send("trackpad.key", TrackpadCodePayload("backspace"))
                }
                return true
            }

            // Physical keyboards and some soft keyboards send key events directly.
            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_DEL    -> send("trackpad.key", TrackpadCodePayload("backspace"))
                        KeyEvent.KEYCODE_ENTER  -> send("trackpad.key", TrackpadCodePayload("enter"))
                        else -> {
                            val c = event.unicodeChar
                            if (c != 0) send("trackpad.key", TrackpadTextPayload(c.toChar().toString()))
                        }
                    }
                }
                return true
            }

            private fun send(type: String, payload: Any) =
                ConnectionService.broadcast(
                    when (payload) {
                        is TrackpadTextPayload -> envelope(type, payload)
                        is TrackpadCodePayload -> envelope(type, payload)
                        else -> envelope(type)
                    }
                )
        }.also {
            outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD // suppresses autocorrect
            outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
                EditorInfo.IME_FLAG_NO_EXTRACT_UI
        }

    fun showKeyboard() {
        requestFocus()
        imm.showSoftInput(this, InputMethodManager.SHOW_FORCED)
    }

    fun hideKeyboard() {
        imm.hideSoftInputFromWindow(windowToken, 0)
    }

    // Draw a subtle hint so the user knows where to tap to dismiss/reopen keyboard.
    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        val paint = android.graphics.Paint().apply {
            color = 0xFFAAAAAA.toInt()
            textSize = 38f
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
        }
        canvas.drawText("⌨  Typing to Mac…", width / 2f, height / 2f + paint.textSize / 3, paint)
    }
}

/** Full-screen touch surface that sends trackpad.* events to the connected Mac. */
private class TouchpadView(context: Context) : View(context) {

    private val sensitivity = 1.8f
    private var lastX = 0f
    private var lastY = 0f
    private var twoFingers = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (e.pointerCount >= 2) return false
                ConnectionService.broadcast(envelope("trackpad.tap"))
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                ConnectionService.broadcast(envelope("trackpad.doubleTap"))
                return true
            }
        })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                lastX = event.getX(0)
                lastY = event.getY(0)
                twoFingers = event.pointerCount >= 2
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount == 2) {
                    val dx = abs(event.getX(0) - lastX)
                    val dy = abs(event.getY(0) - lastY)
                    if (dx < slop && dy < slop) {
                        ConnectionService.broadcast(envelope("trackpad.rightTap"))
                    }
                }
                twoFingers = event.pointerCount - 1 >= 2
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.getX(0) - lastX) * sensitivity
                val dy = (event.getY(0) - lastY) * sensitivity
                lastX = event.getX(0)
                lastY = event.getY(0)
                if (abs(dx) < 0.5f && abs(dy) < 0.5f) return true

                if (twoFingers || event.pointerCount >= 2) {
                    ConnectionService.broadcast(envelope("trackpad.scroll",
                        TrackpadScrollPayload(dx / 4f, -dy / 4f)))
                } else {
                    ConnectionService.broadcast(envelope("trackpad.move",
                        TrackpadMovePayload(dx, dy)))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                twoFingers = false
            }
        }
        return true
    }

    init {
        setBackgroundColor(0xFF1E1F22.toInt())
    }
}
