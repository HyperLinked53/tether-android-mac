package com.conduit.android.features

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.conduit.android.connection.Envelope
import com.conduit.android.connection.FeatureHandler
import com.conduit.android.connection.FileHttpServer
import com.conduit.android.connection.PeerSession
import com.conduit.android.connection.envelope
import com.conduit.android.connection.protocolJson
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Text messaging: list conversations (SMS + MMS, by contact name), read full history (MMS images
 * served over [FileHttpServer]), send SMS, and search contacts to compose. Reading needs READ_SMS +
 * READ_CONTACTS; sending needs SEND_SMS. Sending MMS is out of scope (needs default-SMS-app role).
 *
 * `attachmentUrl` is emitted with a `%HOST%` placeholder because the phone doesn't reliably know its
 * own reachable IP; the Mac rewrites it to the host it connected to.
 */
@Serializable private data class ThreadDto(val id: String, val address: String, val name: String? = null, val snippet: String, val date: Long)
@Serializable private data class ThreadListDto(val threads: List<ThreadDto>)
@Serializable private data class MessagesQuery(val threadId: String)
@Serializable private data class MessageDto(
    val id: String, val body: String, val date: Long, val mine: Boolean,
    val mms: Boolean = false, val attachmentUrl: String? = null, val attachmentMime: String? = null,
)
@Serializable private data class MessageListDto(val threadId: String, val messages: List<MessageDto>)
@Serializable private data class SendDto(val address: String, val body: String)
@Serializable private data class SentDto(val address: String, val ok: Boolean, val error: String? = null)
@Serializable private data class ContactsQuery(val query: String? = null)
@Serializable private data class ContactDto(val name: String, val number: String)
@Serializable private data class ContactListDto(val contacts: List<ContactDto>)

class SmsHandler(
    private val context: Context,
    private val httpServer: FileHttpServer,
) : FeatureHandler {

    private val resolver get() = context.contentResolver
    private val nameCache = HashMap<String, String?>()

    override fun handles(type: String) = type.startsWith("sms.")

    private fun granted(perm: String) =
        ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED

    override suspend fun onText(session: PeerSession, env: Envelope) {
        when (env.type) {
            "sms.threads" -> {
                if (!granted(Manifest.permission.READ_SMS))
                    return session.sendError("internal", "READ_SMS not granted", replyTo = env.id)
                session.send(envelope("sms.threadList", ThreadListDto(readThreads()), replyTo = env.id))
            }
            "sms.messages" -> {
                val q = protocolJson.decodeFromJsonElement(MessagesQuery.serializer(), env.payload)
                session.send(envelope("sms.messageList", readMessages(q.threadId), replyTo = env.id))
            }
            "sms.contacts" -> {
                val q = protocolJson.decodeFromJsonElement(ContactsQuery.serializer(), env.payload)
                session.send(envelope("sms.contactList", ContactListDto(readContacts(q.query)), replyTo = env.id))
            }
            "sms.send" -> {
                val s = protocolJson.decodeFromJsonElement(SendDto.serializer(), env.payload)
                session.send(envelope("sms.sent", send(s), replyTo = env.id))
            }
        }
    }

    // ---- conversations -----------------------------------------------------

    private fun readThreads(): List<ThreadDto> {
        val out = mutableListOf<ThreadDto>()
        // The "conversations?simple=true" view merges SMS + MMS threads.
        runCatching {
            resolver.query(Uri.parse("content://mms-sms/conversations?simple=true"), null, null, null, "date DESC")
                ?.use { c ->
                    val idIdx = c.getColumnIndex("_id")
                    val dateIdx = c.getColumnIndex("date")
                    val snipIdx = c.getColumnIndex("snippet")
                    val recipIdx = c.getColumnIndex("recipient_ids")
                    while (c.moveToNext()) {
                        val tid = if (idIdx >= 0) c.getString(idIdx) else continue
                        val recipientIds = if (recipIdx >= 0) c.getString(recipIdx) else null
                        val address = addressForRecipients(recipientIds)
                        out += ThreadDto(
                            id = tid,
                            address = address,
                            name = contactName(address),
                            snippet = (if (snipIdx >= 0) c.getString(snipIdx) else null).orEmpty().take(140),
                            date = if (dateIdx >= 0) c.getLong(dateIdx) else 0L,
                        )
                    }
                }
        }
        return out.ifEmpty { readSmsThreadsFallback() }
    }

    private fun readSmsThreadsFallback(): List<ThreadDto> {
        val out = mutableListOf<ThreadDto>()
        val proj = arrayOf(Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE)
        resolver.query(Telephony.Sms.CONTENT_URI, proj, null, null, "${Telephony.Sms.DATE} DESC")?.use { c ->
            val seen = HashSet<String>()
            while (c.moveToNext()) {
                val tid = c.getString(0) ?: continue
                if (!seen.add(tid)) continue
                val addr = c.getString(1) ?: ""
                out += ThreadDto(tid, addr, contactName(addr), (c.getString(2) ?: "").take(140), c.getLong(3))
            }
        }
        return out
    }

    /** recipient_ids → space-separated canonical-address ids → the (first) phone number. */
    private fun addressForRecipients(recipientIds: String?): String {
        val first = recipientIds?.trim()?.split(" ")?.firstOrNull()?.takeIf { it.isNotBlank() } ?: return ""
        return runCatching {
            resolver.query(
                Uri.parse("content://mms-sms/canonical-addresses"),
                arrayOf("_id", "address"), "_id = ?", arrayOf(first), null,
            )?.use { c -> if (c.moveToFirst()) c.getString(1) ?: "" else "" } ?: ""
        }.getOrDefault("")
    }

    // ---- messages (SMS + MMS merged) ---------------------------------------

    private fun readMessages(threadId: String): MessageListDto {
        val out = mutableListOf<MessageDto>()
        runCatching {
            val proj = arrayOf(Telephony.Sms._ID, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE)
            resolver.query(
                Telephony.Sms.CONTENT_URI, proj, "${Telephony.Sms.THREAD_ID} = ?", arrayOf(threadId),
                "${Telephony.Sms.DATE} ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val mine = c.getInt(3) == Telephony.Sms.MESSAGE_TYPE_SENT
                    out += MessageDto("sms-${c.getString(0)}", c.getString(1) ?: "", c.getLong(2), mine)
                }
            }
        }
        // MMS date is in seconds; normalize to millis.
        runCatching {
            val proj = arrayOf(Telephony.Mms._ID, Telephony.Mms.DATE, Telephony.Mms.MESSAGE_BOX)
            resolver.query(
                Telephony.Mms.CONTENT_URI, proj, "${Telephony.Mms.THREAD_ID} = ?", arrayOf(threadId), null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val date = c.getLong(1) * 1000L
                    val mine = c.getInt(2) == Telephony.Mms.MESSAGE_BOX_SENT
                    out += readMmsParts(id, date, mine)
                }
            }
        }
        out.sortBy { it.date }
        return MessageListDto(threadId, out)
    }

    /** Read an MMS message's text + image parts; arm image parts on the file server for the Mac. */
    private fun readMmsParts(mmsId: String, date: Long, mine: Boolean): MessageDto {
        var text = ""
        var attachmentUrl: String? = null
        var attachmentMime: String? = null
        runCatching {
            resolver.query(
                Uri.parse("content://mms/part"),
                arrayOf("_id", "ct", "text"), "mid = ?", arrayOf(mmsId), null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val partId = c.getString(0) ?: continue
                    val ct = c.getString(1) ?: ""
                    when {
                        ct == "text/plain" -> text = c.getString(2) ?: text
                        ct.startsWith("image/") && attachmentUrl == null -> {
                            val partUri = Uri.parse("content://mms/part/$partId")
                            val transferId = UUID.randomUUID().toString()
                            httpServer.registerDownload(
                                transferId,
                                FileHttpServer.DownloadSource(0L) {
                                    resolver.openInputStream(partUri) ?: error("cannot open mms part")
                                },
                            )
                            attachmentUrl = "http://%HOST%:${httpServer.port}/download/$transferId"
                            attachmentMime = ct
                        }
                    }
                }
            }
        }
        return MessageDto("mms-$mmsId", text, date, mine, mms = true,
            attachmentUrl = attachmentUrl, attachmentMime = attachmentMime)
    }

    // ---- contacts ----------------------------------------------------------

    private fun contactName(number: String?): String? {
        if (number.isNullOrBlank()) return null
        if (nameCache.containsKey(number)) return nameCache[number]
        if (!granted(Manifest.permission.READ_CONTACTS)) return null
        val name = runCatching {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            resolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
        nameCache[number] = name
        return name
    }

    private fun readContacts(query: String?): List<ContactDto> {
        if (!granted(Manifest.permission.READ_CONTACTS)) return emptyList()
        val out = LinkedHashMap<String, ContactDto>() // dedup by number
        val uri = if (query.isNullOrBlank()) {
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        } else {
            Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(query))
        }
        runCatching {
            resolver.query(
                uri,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
            )?.use { c ->
                while (c.moveToNext() && out.size < 200) {
                    val name = c.getString(0) ?: continue
                    val number = c.getString(1) ?: continue
                    out.putIfAbsent(number.filter { !it.isWhitespace() }, ContactDto(name, number))
                }
            }
        }
        return out.values.toList()
    }

    // ---- send --------------------------------------------------------------

    private fun send(s: SendDto): SentDto {
        if (!granted(Manifest.permission.SEND_SMS)) return SentDto(s.address, false, "SEND_SMS not granted")
        return runCatching {
            val sm = context.getSystemService(SmsManager::class.java)
            val parts = sm.divideMessage(s.body)
            if (parts.size > 1) sm.sendMultipartTextMessage(s.address, null, parts, null, null)
            else sm.sendTextMessage(s.address, null, s.body, null, null)
            SentDto(s.address, true)
        }.getOrElse { SentDto(s.address, false, it.message) }
    }
}
