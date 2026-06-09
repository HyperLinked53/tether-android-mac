package com.conduit.android.features

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.conduit.android.connection.ConnectionService
import com.conduit.android.connection.envelope
import kotlinx.serialization.Serializable

/**
 * Pushes inbound SMS to the Mac live (protocol `sms.incoming`). Registered in the manifest on
 * `SMS_RECEIVED`; if the connection service is running, broadcasts the new message so the open
 * conversation updates immediately. (Live MMS is out of scope — it shows on the next refresh.)
 */
class SmsReceiver : BroadcastReceiver() {

    @Serializable private data class MsgDto(val id: String, val body: String, val date: Long, val mine: Boolean = false)
    @Serializable private data class IncomingDto(val threadId: String, val address: String, val name: String?, val message: MsgDto)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val msgs = runCatching { Telephony.Sms.Intents.getMessagesFromIntent(intent) }.getOrNull() ?: return
        if (msgs.isEmpty()) return

        val address = msgs[0].originatingAddress ?: ""
        val body = msgs.joinToString("") { it.messageBody ?: "" } // multipart SMS arrives as several PDUs
        val date = System.currentTimeMillis()
        val threadId = runCatching { Telephony.Threads.getOrCreateThreadId(context, address).toString() }
            .getOrDefault("")

        val payload = IncomingDto(
            threadId = threadId,
            address = address,
            name = contactName(context, address),
            message = MsgDto(id = "sms-in-$date", body = body, date = date),
        )
        ConnectionService.broadcast(envelope("sms.incoming", payload))
    }

    private fun contactName(context: Context, number: String): String? {
        if (number.isBlank() ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED
        ) return null
        return runCatching {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
    }
}
