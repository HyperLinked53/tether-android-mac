package com.conduit.android.features

import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.conduit.android.connection.Envelope
import com.conduit.android.connection.FeatureHandler
import com.conduit.android.connection.PeerSession
import com.conduit.android.state.ConduitState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Handles `phone.ring` from the Mac: plays the default ringtone + vibrates for 5 seconds.
 *
 * Audio is flagged USAGE_ALARM so Android routes it at alarm volume rather than ring volume —
 * this works without any permission and is not blocked by Do Not Disturb or silent mode.
 * Volume is never touched directly (setStreamVolume needs ACCESS_NOTIFICATION_POLICY to work
 * when DND is active and will throw a SecurityException without it).
 */
class RingHandler(private val context: Context) : FeatureHandler {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun handles(type: String) = type == "phone.ring"

    override suspend fun onText(session: PeerSession, env: Envelope) {
        ConduitState.logEvent("Ringing…")
        scope.launch { ring() }
    }

    private suspend fun ring() {
        // --- ringtone --------------------------------------------------------
        val ringtone = try {
            RingtoneManager.getRingtone(
                context,
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            )
        } catch (e: Exception) {
            Log.w("RingHandler", "Could not get ringtone", e)
            null
        }

        if (ringtone != null) {
            try {
                ringtone.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                ringtone.play()
            } catch (e: Exception) {
                Log.w("RingHandler", "Could not play ringtone", e)
            }
        }

        // --- vibrate ---------------------------------------------------------
        try {
            val pattern = longArrayOf(0, 600, 300, 600, 300, 600)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v?.vibrate(VibrationEffect.createWaveform(pattern, -1))
                } else {
                    @Suppress("DEPRECATION")
                    v?.vibrate(pattern, -1)
                }
            }
        } catch (e: Exception) {
            Log.w("RingHandler", "Could not vibrate", e)
        }

        // --- stop after 5 s --------------------------------------------------
        delay(10_000)
        try { ringtone?.stop() } catch (e: Exception) { /* ignore */ }
    }
}
