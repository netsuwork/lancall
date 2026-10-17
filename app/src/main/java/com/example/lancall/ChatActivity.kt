package com.example.lancall

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class ChatActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PEER_IP = "peer_ip"
        const val EXTRA_PEER_NAME = "peer_name"
    }

    private var service: CallService? = null
    private lateinit var peerIp: String
    private lateinit var peerName: String

    private val messages = mutableListOf<ChatMessage>()
    private lateinit var adapter: BaseAdapter
    private lateinit var lvMessages: ListView
    private lateinit var tvEmpty: TextView
    private lateinit var etMessage: EditText
    private lateinit var tvTitle: TextView
    private lateinit var tvStatus: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val presenceRunnable = object : Runnable {
        override fun run() {
            updatePresence()
            handler.postDelayed(this, 2000)
        }
    }

    private val chatListener: (String) -> Unit = { ip ->
        if (ip == peerIp) runOnUiThread { reload() }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as CallService.LocalBinder).getService()
            service = svc
            svc.chatManager.addListener(chatListener)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) setOpen(true)
            reload()
            updatePresence()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ip = intent.getStringExtra(EXTRA_PEER_IP)
        if (ip.isNullOrEmpty()) {
            finish()
            return
        }
        peerIp = ip
        peerName = intent.getStringExtra(EXTRA_PEER_NAME) ?: "Chat"

        buildUi()
        bindService(Intent(this, CallService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStart() {
        super.onStart()
        handler.post(presenceRunnable)
        setOpen(true)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(presenceRunnable)
        setOpen(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(presenceRunnable)
        service?.chatManager?.removeListener(chatListener)
        try {
            unbindService(connection)
        } catch (_: Exception) {}
        service = null
    }

    private fun setOpen(visible: Boolean) {
        val svc = service ?: return
        svc.chatManager.openPeerIp = if (visible) peerIp else null
        if (visible) {
            svc.chatManager.markRead(peerIp)
            svc.cancelMessageNotification(peerIp)
        }
    }

    private fun reload() {
        val svc = service ?: return
        messages.clear()
        messages.addAll(svc.chatManager.getMessages(peerIp))
        adapter.notifyDataSetChanged()
        tvEmpty.visibility = if (messages.isEmpty()) View.VISIBLE else View.GONE
        if (messages.isNotEmpty()) lvMessages.setSelection(messages.size - 1)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) svc.chatManager.markRead(peerIp)
    }

    private fun updatePresence() {
        val peer = service?.currentPeers()?.firstOrNull { it.ip == peerIp }
        if (peer != null) {
            tvTitle.text = peer.username
            tvStatus.text = "● Online"
            tvStatus.setTextColor(Color.parseColor("#94A3B8"))
        } else {
            tvTitle.text = peerName
            tvStatus.text = "○ Offline — messages may not be delivered"
            tvStatus.setTextColor(Color.parseColor("#64748B"))
        }
    }

    private fun getSavedUsername(): String =
        getSharedPreferences("lancall_prefs", Context.MODE_PRIVATE).getString("username", "") ?: ""

    private fun sendCurrent() {
        val svc = service ?: return
        val text = etMessage.text.toString()
        if (text.isBlank()) return
        if (svc.chatManager.send(peerIp, getSavedUsername(), text)) {
            etMessage.text.clear()
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun formatTime(ts: Long): String {
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = ts }
        val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
        val pattern = if (sameDay) "HH:mm" else "MMM d, HH:mm"
        return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(ts))
    }

    private fun metaText(m: ChatMessage): String {
        val time = formatTime(m.timestamp)
        if (!m.fromMe) return time
        return when (m.status) {
            ChatManager.STATUS_SENDING -> "$time • Sending…"
            ChatManager.STATUS_FAILED -> "$time • Not delivered — tap to retry"
            else -> "$time  ✓"
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F172A"))
        }

        // Header
        val back = TextView(this).apply {
            text = "‹"
            textSize = 34f
            setTextColor(Color.parseColor("#E2E8F0"))
            setPadding(dp(8), 0, dp(16), dp(4))
            setOnClickListener { finish() }
        }
        tvTitle = TextView(this).apply {
            text = peerName
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#F8FAFC"))
        }
        tvStatus = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
        }
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(tvTitle)
            addView(tvStatus)
        }
        val clear = TextView(this).apply {
            text = "Clear"
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener {
                AlertDialog.Builder(this@ChatActivity)
                    .setTitle("Clear this chat?")
                    .setMessage("Messages are removed from this phone only.")
                    .setPositiveButton("Clear") { _, _ -> service?.chatManager?.clear(peerIp) }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#1E293B"))
            setPadding(dp(12), dp(10), dp(8), dp(10))
            addView(back)
            addView(titles)
            addView(clear)
        }

        // Messages list
        adapter = object : BaseAdapter() {
            override fun getCount(): Int = messages.size
            override fun getItem(position: Int): Any = messages[position]
            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val m = messages[position]
                val mine = m.fromMe

                val textTv = TextView(this@ChatActivity).apply {
                    text = m.text
                    textSize = 16f
                    setTextColor(Color.WHITE)
                    maxWidth = (resources.displayMetrics.widthPixels * 0.72).toInt()
                }
                val meta = TextView(this@ChatActivity).apply {
                    text = metaText(m)
                    textSize = 11f
                    setTextColor(
                        Color.parseColor(
                            when {
                                m.status == ChatManager.STATUS_FAILED -> "#FCA5A5"
                                mine -> "#CBD5E1"
                                else -> "#94A3B8"
                            }
                        )
                    )
                    setPadding(0, dp(4), 0, 0)
                }
                val bubble = LinearLayout(this@ChatActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    background = GradientDrawable().apply {
                        cornerRadius = dp(18).toFloat()
                        setColor(Color.parseColor(if (mine) "#334155" else "#1E293B"))
                        setStroke(dp(1), Color.parseColor(if (mine) "#475569" else "#334155"))
                    }
                    setPadding(dp(14), dp(10), dp(14), dp(8))
                    addView(textTv)
                    addView(meta)
                }
                return LinearLayout(this@ChatActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = if (mine) Gravity.END else Gravity.START
                    setPadding(dp(10), dp(4), dp(10), dp(4))
                    addView(
                        bubble,
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    )
                }
            }
        }

        lvMessages = ListView(this).apply {
            adapter = this@ChatActivity.adapter
            divider = null
            dividerHeight = 0
            setSelector(android.R.color.transparent)
            transcriptMode = ListView.TRANSCRIPT_MODE_ALWAYS_SCROLL
            clipToPadding = false
            setPadding(0, dp(8), 0, dp(8))
            setOnItemClickListener { _, _, pos, _ ->
                val m = messages.getOrNull(pos)
                if (m != null && m.fromMe && m.status == ChatManager.STATUS_FAILED) {
                    service?.chatManager?.resend(peerIp, m.id)
                }
            }
            setOnItemLongClickListener { _, _, pos, _ ->
                val m = messages.getOrNull(pos)
                if (m != null) {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("message", m.text))
                    Toast.makeText(this@ChatActivity, "Copied", Toast.LENGTH_SHORT).show()
                }
                true
            }
        }
        tvEmpty = TextView(this).apply {
            text = "No messages yet.\nSay hello to $peerName 👋"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#64748B"))
        }
        val content = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(
                lvMessages,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
            addView(
                tvEmpty,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER
                )
            )
        }

        // Input row with gray Send button
        etMessage = EditText(this).apply {
            hint = "Message"
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 16f
            maxLines = 4
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(InputFilter.LengthFilter(ChatManager.MAX_TEXT))
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(Color.parseColor("#0F172A"))
                setStroke(dp(1), Color.parseColor("#334155"))
            }
            setPadding(dp(16), dp(10), dp(16), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnSend = MaterialButton(this).apply {
            text = "Send"
            isAllCaps = false
            backgroundTintList = ColorStateList.valueOf(Color.parseColor("#475569"))
            setTextColor(Color.WHITE)
            cornerRadius = dp(22)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(46)
            ).apply { setMargins(dp(8), 0, 0, 0) }
            setOnClickListener { sendCurrent() }
        }
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#1E293B"))
            setPadding(dp(10), dp(8), dp(10), dp(8))
            addView(etMessage)
            addView(btnSend)
        }

        root.addView(header)
        root.addView(content)
        root.addView(inputRow)
        setContentView(root)
    }
}
