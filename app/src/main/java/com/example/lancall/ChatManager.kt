package com.example.lancall

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class ChatMessage(
    val id: String,
    val peerIp: String,
    val fromMe: Boolean,
    val senderName: String,
    val text: String,
    val timestamp: Long,
    @Volatile var status: Int
)

/**
 * Text chat over TCP (port 50008). One short connection per message:
 *   sender -> [magic][id][senderName][text]      receiver -> [1] (ack)
 * A message is "delivered" only once the receiver has acknowledged it.
 * The conversation key is the sender's real network address, never an address claimed inside the message.
 */
class ChatManager(private val dir: File) {

    companion object {
        const val CHAT_PORT = 50008
        const val STATUS_SENDING = 0
        const val STATUS_SENT = 1
        const val STATUS_FAILED = 2
        const val MAX_TEXT = 2000
        private const val MAGIC = 0x4C434831 // "LCH1"
        private const val MAX_HISTORY = 500
        private const val TAG = "ChatManager"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val lock = Any()
    private val conversations = HashMap<String, MutableList<ChatMessage>>()
    private val unread = ConcurrentHashMap<String, Int>()
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()
    private var serverSocket: ServerSocket? = null

    /** The chat screen currently on display (if any). Messages from this peer aren't counted as unread. */
    @Volatile var openPeerIp: String? = null

    /** Called for every message received from another device. */
    @Volatile var onIncoming: ((ChatMessage) -> Unit)? = null

    // ------------------------------------------------------------------ listeners

    fun addListener(l: (String) -> Unit) {
        if (!listeners.contains(l)) listeners.add(l)
    }

    fun removeListener(l: (String) -> Unit) {
        listeners.remove(l)
    }

    private fun notifyChanged(peerIp: String) {
        for (l in listeners) {
            try {
                l(peerIp)
            } catch (_: Exception) {}
        }
    }

    // ------------------------------------------------------------------ queries

    fun getMessages(peerIp: String): List<ChatMessage> =
        synchronized(lock) { conversation(peerIp).toList() }

    fun unreadCount(peerIp: String): Int = unread[peerIp] ?: 0

    fun markRead(peerIp: String) {
        unread.remove(peerIp)
    }

    // ------------------------------------------------------------------ sending

    /** Returns false if the text was empty. Delivery result arrives through the listeners. */
    fun send(peerIp: String, myName: String, text: String): Boolean {
        val clean = text.trim().take(MAX_TEXT)
        if (clean.isEmpty()) return false
        val msg = ChatMessage(
            UUID.randomUUID().toString(), peerIp, true, myName, clean,
            System.currentTimeMillis(), STATUS_SENDING
        )
        synchronized(lock) { conversation(peerIp).add(msg) }
        notifyChanged(peerIp)
        deliverAsync(msg)
        return true
    }

    fun resend(peerIp: String, messageId: String) {
        val msg = synchronized(lock) { conversation(peerIp).firstOrNull { it.id == messageId } } ?: return
        if (!msg.fromMe || msg.status != STATUS_FAILED) return
        msg.status = STATUS_SENDING
        notifyChanged(peerIp)
        deliverAsync(msg)
    }

    private fun deliverAsync(msg: ChatMessage) {
        scope.launch {
            msg.status = if (deliver(msg)) STATUS_SENT else STATUS_FAILED
            persist(msg)
            notifyChanged(msg.peerIp)
        }
    }

    private fun deliver(msg: ChatMessage): Boolean {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(msg.peerIp, CHAT_PORT), 3000)
                s.soTimeout = 3000
                val out = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
                out.writeInt(MAGIC)
                out.writeUTF(msg.id)
                out.writeUTF(msg.senderName)
                out.writeUTF(msg.text)
                out.flush()
                s.getInputStream().read() == 1
            }
        } catch (e: Exception) {
            Log.w(TAG, "Delivery failed: ${e.message}")
            false
        }
    }

    // ---------------------------------------------------------------- receiving

    fun start() {
        if (serverSocket != null) return
        scope.launch {
            try {
                val server = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(CHAT_PORT))
                }
                serverSocket = server
                while (isActive) {
                    val client = server.accept()
                    launch { handleClient(client) }
                }
            } catch (e: Exception) {
                if (isActive) Log.e(TAG, "Chat server stopped", e)
            }
        }
    }

    fun stop() {
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        scope.coroutineContext.cancelChildren()
    }

    private fun handleClient(client: Socket) {
        client.use { sock ->
            try {
                sock.soTimeout = 5000
                val input = DataInputStream(BufferedInputStream(sock.getInputStream()))
                if (input.readInt() != MAGIC) return
                val id = input.readUTF()
                val name = input.readUTF().take(40)
                val text = input.readUTF()
                if (id.length > 64 || text.isBlank() || text.length > MAX_TEXT) return
                val peerIp = sock.inetAddress?.hostAddress ?: return

                // Acknowledge first, so a repeated message is acknowledged but not stored twice.
                sock.getOutputStream().apply {
                    write(1)
                    flush()
                }

                val msg = ChatMessage(id, peerIp, false, name, text, System.currentTimeMillis(), STATUS_SENT)
                val isNew = synchronized(lock) {
                    val list = conversation(peerIp)
                    if (list.any { it.id == id }) false else list.add(msg)
                }
                if (!isNew) return

                persist(msg)
                if (openPeerIp != peerIp) unread[peerIp] = (unread[peerIp] ?: 0) + 1
                onIncoming?.invoke(msg)
                notifyChanged(peerIp)
            } catch (_: Exception) {
            }
        }
    }

    // ---------------------------------------------------------------- history

    fun clear(peerIp: String) {
        synchronized(lock) { conversations[peerIp] = mutableListOf() }
        unread.remove(peerIp)
        try {
            fileFor(peerIp).delete()
        } catch (_: Exception) {}
        notifyChanged(peerIp)
    }

    private fun conversation(peerIp: String): MutableList<ChatMessage> =
        conversations.getOrPut(peerIp) { loadFromDisk(peerIp) }

    private fun fileFor(peerIp: String): File =
        File(dir, "chat_" + peerIp.replace(Regex("[^A-Za-z0-9]"), "_") + ".jsonl")

    private fun loadFromDisk(peerIp: String): MutableList<ChatMessage> {
        val byId = LinkedHashMap<String, ChatMessage>()
        try {
            val f = fileFor(peerIp)
            if (f.exists()) {
                f.readLines().takeLast(MAX_HISTORY).forEach { line ->
                    try {
                        val o = JSONObject(line)
                        val id = o.getString("id")
                        byId[id] = ChatMessage(
                            id, peerIp, o.getBoolean("out"), o.optString("name"),
                            o.getString("text"), o.getLong("ts"), o.optInt("st", STATUS_SENT)
                        )
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
        // A message left "sending" when the app died never got an answer: show it as failed.
        byId.values.forEach { if (it.status == STATUS_SENDING) it.status = STATUS_FAILED }
        return byId.values.sortedBy { it.timestamp }.toMutableList()
    }

    private fun persist(m: ChatMessage) {
        try {
            dir.mkdirs()
            val o = JSONObject()
                .put("id", m.id)
                .put("out", m.fromMe)
                .put("name", m.senderName)
                .put("text", m.text)
                .put("ts", m.timestamp)
                .put("st", m.status)
            fileFor(m.peerIp).appendText(o.toString() + "\n")
        } catch (_: Exception) {}
    }
}