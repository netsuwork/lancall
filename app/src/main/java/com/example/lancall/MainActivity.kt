package com.example.lancall

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ChatItem(val fromMe: Boolean, val senderName: String, val text: String, val time: String)

class MainActivity : AppCompatActivity() {

    private val callManager = AudioCallManager()
    private val RECORD_REQ_CODE = 101

    private lateinit var layoutMain: LinearLayout
    private lateinit var layoutChat: LinearLayout

    private lateinit var tvAvatarInitial: TextView
    private lateinit var tvUsername: TextView
    private lateinit var tvNetworkStatus: TextView
    private lateinit var cardStatus: MaterialCardView
    private lateinit var tvStatusTitle: TextView
    private lateinit var tvStatusSubtitle: TextView
    private lateinit var tvDeviceCount: TextView
    private lateinit var lvDevices: ListView
    private lateinit var btnCall: MaterialButton
    private lateinit var btnOpenChat: MaterialButton
    private lateinit var btnSpeaker: MaterialButton
    private lateinit var btnEndCall: MaterialButton
    private lateinit var btnChangeName: MaterialButton
    private lateinit var btnClearLogs: MaterialButton
    private lateinit var lvCallLogs: ListView

    // Chat UI elements
    private lateinit var btnBackFromChat: TextView
    private lateinit var tvChatTitle: TextView
    private lateinit var tvChatSubtitle: TextView
    private lateinit var btnClearChat: MaterialButton
    private lateinit var lvChatMessages: ListView
    private lateinit var etChatMessage: EditText
    private lateinit var btnSendChat: MaterialButton

    private val discoveredPeers = mutableListOf<PeerDevice>()
    private val callLogItems = mutableListOf<String>()
    private val chatMessages = mutableListOf<ChatItem>()

    private lateinit var devicesAdapter: BaseAdapter
    private lateinit var logsAdapter: BaseAdapter
    private lateinit var chatAdapter: BaseAdapter

    private var selectedPeer: PeerDevice? = null
    private var activeChatPeer: PeerDevice? = null

    private var activePeerIp: String = ""
    private var activePeerName: String = ""
    private var isOutgoingCall: Boolean = false
    private var callStartTime: Long = 0L
    private var speakerOn: Boolean = false

    private var incomingDialog: AlertDialog? = null
    private var activeRingtone: Ringtone? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private val timerHandler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            if (callStartTime > 0L) {
                val elapsed = (System.currentTimeMillis() - callStartTime) / 1000
                val mins = elapsed / 60
                val secs = elapsed % 60
                tvStatusSubtitle.text = String.format(Locale.US, "Connected • %02d:%02d", mins, secs)
                timerHandler.postDelayed(this, 1000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        layoutMain = findViewById(R.id.layoutMain)
        layoutChat = findViewById(R.id.layoutChat)

        tvAvatarInitial = findViewById(R.id.tvAvatarInitial)
        tvUsername = findViewById(R.id.tvUsername)
        tvNetworkStatus = findViewById(R.id.tvNetworkStatus)
        cardStatus = findViewById(R.id.cardStatus)
        tvStatusTitle = findViewById(R.id.tvStatusTitle)
        tvStatusSubtitle = findViewById(R.id.tvStatusSubtitle)
        tvDeviceCount = findViewById(R.id.tvDeviceCount)
        lvDevices = findViewById(R.id.lvDevices)
        btnCall = findViewById(R.id.btnCall)
        btnOpenChat = findViewById(R.id.btnOpenChat)
        btnSpeaker = findViewById(R.id.btnSpeaker)
        btnEndCall = findViewById(R.id.btnEndCall)
        btnChangeName = findViewById(R.id.btnChangeName)
        btnClearLogs = findViewById(R.id.btnClearLogs)
        lvCallLogs = findViewById(R.id.lvCallLogs)

        btnBackFromChat = findViewById(R.id.btnBackFromChat)
        tvChatTitle = findViewById(R.id.tvChatTitle)
        tvChatSubtitle = findViewById(R.id.tvChatSubtitle)
        btnClearChat = findViewById(R.id.btnClearChat)
        lvChatMessages = findViewById(R.id.lvChatMessages)
        etChatMessage = findViewById(R.id.etChatMessage)
        btnSendChat = findViewById(R.id.btnSendChat)

        tvAvatarInitial.background = createCircleDrawable("#6366F1")

        setupAdapters()
        acquireMulticastLock()
        checkAudioPermissions()
        ensureUsernameSet()
        loadCallLogs()

        lvDevices.setOnItemClickListener { _, _, position, _ ->
            if (position in discoveredPeers.indices && callStartTime == 0L && activePeerIp.isEmpty()) {
                val peer = discoveredPeers[position]
                selectedPeer = peer
                tvStatusTitle.text = peer.username
                tvStatusSubtitle.text = "Ready • Tap Call or Chat below"
                btnCall.text = "Call ${peer.username}"
                devicesAdapter.notifyDataSetChanged()
            }
        }

        btnChangeName.setOnClickListener {
            showUsernameDialog(cancelable = true)
        }

        btnClearLogs.setOnClickListener {
            clearCallLogs()
        }

        btnSpeaker.setOnClickListener {
            setSpeakerphone(!speakerOn)
        }

        btnCall.setOnClickListener {
            val target = selectedPeer
            if (target == null) {
                Toast.makeText(this, "Tap a person from the online list first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            activePeerName = target.username
            activePeerIp = target.ip
            isOutgoingCall = true

            cardStatus.setCardBackgroundColor(ColorStateList.valueOf(Color.parseColor("#312E81")))
            tvStatusTitle.text = "Calling $activePeerName..."
            tvStatusSubtitle.text = "Ringing on their device"
            btnCall.isEnabled = false
            btnOpenChat.isEnabled = false
            btnEndCall.isEnabled = true

            callManager.sendSignal(activePeerIp, "INVITE", getSavedUsername())
        }

        btnEndCall.setOnClickListener {
            if (activePeerIp.isNotEmpty()) {
                callManager.sendSignal(activePeerIp, "END", getSavedUsername())
            }
            finishActiveCall("Call Ended")
        }

        // --- Chat Controls ---

        btnOpenChat.setOnClickListener {
            val target = selectedPeer
            if (target == null) {
                Toast.makeText(this, "Tap a person from the online list first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            openChatMode(target)
        }

        btnBackFromChat.setOnClickListener {
            closeChatMode()
        }

        btnClearChat.setOnClickListener {
            val peer = activeChatPeer ?: return@setOnClickListener
            val file = File(filesDir, "chat_${peer.username}.txt")
            if (file.exists()) file.delete()
            chatMessages.clear()
            chatAdapter.notifyDataSetChanged()
        }

        btnSendChat.setOnClickListener {
            val text = etChatMessage.text.toString().trim()
            val target = activeChatPeer
            if (text.isEmpty() || target == null) return@setOnClickListener

            val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            callManager.sendChatMessage(target.ip, getSavedUsername(), text)

            val item = ChatItem(fromMe = true, senderName = getSavedUsername(), text = text, time = time)
            chatMessages.add(item)
            chatAdapter.notifyDataSetChanged()
            lvChatMessages.setSelection(chatMessages.size - 1)
            etChatMessage.text.clear()

            saveChatMessage(target.username, "ME|$text|$time")
        }

        callManager.startNetworkServices(
            getUsername = { getSavedUsername() },
            onPeersUpdated = { peersList ->
                runOnUiThread {
                    val hasIp = callManager.getLocalIpAddress() != "0.0.0.0"
                    tvNetworkStatus.text = if (hasIp) "● Active on Local Network" else "○ Waiting for Wi-Fi / Hotspot"
                    tvNetworkStatus.setTextColor(
                        Color.parseColor(if (hasIp) "#10B981" else "#F59E0B")
                    )

                    discoveredPeers.clear()
                    discoveredPeers.addAll(peersList)

                    selectedPeer?.let { current ->
                        selectedPeer = peersList.find { it.ip == current.ip || it.username == current.username }
                    }

                    tvDeviceCount.text = "PEOPLE ON NETWORK (${peersList.size})"
                    devicesAdapter.notifyDataSetChanged()

                    activeChatPeer?.let { chatPeer ->
                        val isOnline = peersList.any { it.username == chatPeer.username }
                        tvChatSubtitle.text = if (isOnline) "● Online on LAN" else "○ Offline"
                        tvChatSubtitle.setTextColor(Color.parseColor(if (isOnline) "#10B981" else "#F59E0B"))
                    }
                }
            },
            onSignalReceived = { type, senderIp, senderName ->
                runOnUiThread {
                    handleIncomingSignal(type, senderIp, senderName)
                }
            },
            onChatMessageReceived = { senderIp, senderName, text ->
                runOnUiThread {
                    handleIncomingChatMessage(senderIp, senderName, text)
                }
            }
        )
    }

    private fun openChatMode(peer: PeerDevice) {
        activeChatPeer = peer
        tvChatTitle.text = "Chat with ${peer.username}"
        val isOnline = discoveredPeers.any { it.username == peer.username }
        tvChatSubtitle.text = if (isOnline) "● Online on LAN" else "○ Offline"
        tvChatSubtitle.setTextColor(Color.parseColor(if (isOnline) "#10B981" else "#F59E0B"))

        loadChatMessages(peer.username)
        layoutMain.visibility = View.GONE
        layoutChat.visibility = View.VISIBLE
    }

    private fun closeChatMode() {
        activeChatPeer = null
        layoutChat.visibility = View.GONE
        layoutMain.visibility = View.VISIBLE
        devicesAdapter.notifyDataSetChanged()
    }

    private fun handleIncomingChatMessage(senderIp: String, senderName: String, text: String) {
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        saveChatMessage(senderName, "PEER|$text|$time")

        if (layoutChat.visibility == View.VISIBLE && activeChatPeer?.username == senderName) {
            chatMessages.add(ChatItem(fromMe = false, senderName = senderName, text = text, time = time))
            chatAdapter.notifyDataSetChanged()
            lvChatMessages.setSelection(chatMessages.size - 1)
        } else {
            Toast.makeText(this, "💬 $senderName: $text", Toast.LENGTH_LONG).show()
            devicesAdapter.notifyDataSetChanged()
        }
    }

    private fun saveChatMessage(peerUsername: String, line: String) {
        try {
            val file = File(filesDir, "chat_${peerUsername}.txt")
            file.appendText("$line\n")
        } catch (_: Exception) {}
    }

    private fun loadChatMessages(peerUsername: String) {
        chatMessages.clear()
        try {
            val file = File(filesDir, "chat_${peerUsername}.txt")
            if (file.exists()) {
                for (line in file.readLines()) {
                    val parts = line.split("|", limit = 3)
                    if (parts.size == 3) {
                        chatMessages.add(
                            ChatItem(
                                fromMe = parts[0] == "ME",
                                senderName = if (parts[0] == "ME") getSavedUsername() else peerUsername,
                                text = parts[1],
                                time = parts[2]
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {}
        chatAdapter.notifyDataSetChanged()
        if (chatMessages.isNotEmpty()) lvChatMessages.setSelection(chatMessages.size - 1)
    }

    private fun setupAdapters() {
        devicesAdapter = object : BaseAdapter() {
            override fun getCount(): Int = maxOf(1, discoveredPeers.size)
            override fun getItem(position: Int): Any? = discoveredPeers.getOrNull(position)
            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                if (discoveredPeers.isEmpty()) {
                    return TextView(this@MainActivity).apply {
                        text = "Scanning network for people online..."
                        setTextColor(Color.parseColor("#64748B"))
                        textSize = 14f
                        gravity = Gravity.CENTER
                        setPadding(24, 48, 24, 48)
                    }
                }

                val peer = discoveredPeers[position]
                val isSelected = selectedPeer?.ip == peer.ip

                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(28, 20, 28, 20)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 32f
                        setColor(Color.parseColor(if (isSelected) "#312E81" else "#0F172A"))
                        setStroke(
                            if (isSelected) 3 else 1,
                            Color.parseColor(if (isSelected) "#818CF8" else "#334155")
                        )
                    }
                }

                val avatar = TextView(this@MainActivity).apply {
                    text = peer.username.firstOrNull()?.uppercase() ?: "?"
                    setTextColor(Color.WHITE)
                    textSize = 16f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    background = createCircleDrawable(if (isSelected) "#6366F1" else "#059669")
                    layoutParams = LinearLayout.LayoutParams(96, 96)
                }

                val nameCol = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(28, 0, 0, 0)
                    }
                }

                val nameTv = TextView(this@MainActivity).apply {
                    text = peer.username
                    setTextColor(Color.parseColor("#F8FAFC"))
                    textSize = 16f
                    typeface = Typeface.DEFAULT_BOLD
                }

                val subTv = TextView(this@MainActivity).apply {
                    text = if (isSelected) "Selected • Tap Call or Chat" else "Tap to select"
                    setTextColor(Color.parseColor(if (isSelected) "#A5B4FC" else "#64748B"))
                    textSize = 12f
                }

                nameCol.addView(nameTv)
                nameCol.addView(subTv)

                val badge = TextView(this@MainActivity).apply {
                    text = "● Online"
                    setTextColor(Color.parseColor("#10B981"))
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                }

                row.addView(avatar)
                row.addView(nameCol)
                row.addView(badge)
                return row
            }
        }
        lvDevices.adapter = devicesAdapter

        logsAdapter = object : BaseAdapter() {
            override fun getCount(): Int = maxOf(1, callLogItems.size)
            override fun getItem(position: Int): Any? = callLogItems.getOrNull(position)
            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                if (callLogItems.isEmpty()) {
                    return TextView(this@MainActivity).apply {
                        text = "No call history yet"
                        setTextColor(Color.parseColor("#64748B"))
                        textSize = 14f
                        gravity = Gravity.CENTER
                        setPadding(24, 48, 24, 48)
                    }
                }

                val raw = callLogItems[position]
                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(24, 18, 24, 18)
                }

                val parts = raw.split("|")
                val titleText = parts.getOrNull(0) ?: raw
                val metaText = parts.getOrNull(1) ?: ""

                val topTv = TextView(this@MainActivity).apply {
                    text = titleText
                    setTextColor(Color.parseColor("#E2E8F0"))
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                }

                row.addView(topTv)
                if (metaText.isNotEmpty()) {
                    val bottomTv = TextView(this@MainActivity).apply {
                        text = metaText
                        setTextColor(Color.parseColor("#94A3B8"))
                        textSize = 12f
                    }
                    row.addView(bottomTv)
                }
                return row
            }
        }
        lvCallLogs.adapter = logsAdapter

        chatAdapter = object : BaseAdapter() {
            override fun getCount(): Int = chatMessages.size
            override fun getItem(position: Int): Any? = chatMessages.getOrNull(position)
            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val item = chatMessages[position]
                val mine = item.fromMe

                val bubble = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(32, 20, 32, 20)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 28f
                        setColor(Color.parseColor(if (mine) "#6366F1" else "#1E293B"))
                        if (!mine) setStroke(2, Color.parseColor("#334155"))
                    }
                }

                val textTv = TextView(this@MainActivity).apply {
                    text = item.text
                    setTextColor(Color.WHITE)
                    textSize = 15f
                }

                val timeTv = TextView(this@MainActivity).apply {
                    text = item.time
                    setTextColor(Color.parseColor(if (mine) "#C7D2FE" else "#94A3B8"))
                    textSize = 11f
                    gravity = if (mine) Gravity.END else Gravity.START
                    setPadding(0, 8, 0, 0)
                }

                bubble.addView(textTv)
                bubble.addView(timeTv)

                val container = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = if (mine) Gravity.END else Gravity.START
                    setPadding(8, 6, 8, 6)
                    addView(bubble)
                }
                return container
            }
        }
        lvChatMessages.adapter = chatAdapter
    }

    private fun handleIncomingSignal(type: String, senderIp: String, senderName: String) {
        when (type) {
            "INVITE" -> {
                activePeerIp = senderIp
                activePeerName = senderName
                isOutgoingCall = false
                showIncomingCallDialog(senderName, senderIp)
            }
            "ACCEPT" -> {
                callStartTime = System.currentTimeMillis()
                cardStatus.setCardBackgroundColor(ColorStateList.valueOf(Color.parseColor("#065F46")))
                tvStatusTitle.text = "In Call with $senderName"
                timerHandler.post(timerRunnable)
                callManager.startAudioStream(senderIp) { inCall ->
                    runOnUiThread { updateCallButtons(inCall) }
                }
            }
            "REJECT" -> {
                saveCallLog("↗ Outgoing to $senderName", "Declined")
                resetUiAfterCall("Call declined by $senderName")
                Toast.makeText(this, "$senderName declined the call", Toast.LENGTH_SHORT).show()
            }
            "END" -> {
                incomingDialog?.dismiss()
                stopRingtone()
                finishActiveCall("Call ended by $senderName")
            }
        }
    }

    private fun showIncomingCallDialog(callerName: String, callerIp: String) {
        playRingtone()
        incomingDialog?.dismiss()

        incomingDialog = AlertDialog.Builder(this)
            .setTitle("Incoming Voice Call")
            .setMessage("$callerName is calling you...")
            .setCancelable(false)
            .setPositiveButton("Accept") { dialog, _ ->
                stopRingtone()
                dialog.dismiss()
                callStartTime = System.currentTimeMillis()
                cardStatus.setCardBackgroundColor(ColorStateList.valueOf(Color.parseColor("#065F46")))
                tvStatusTitle.text = "In Call with $callerName"
                timerHandler.post(timerRunnable)
                callManager.sendSignal(callerIp, "ACCEPT", getSavedUsername())
                callManager.startAudioStream(callerIp) { inCall ->
                    runOnUiThread { updateCallButtons(inCall) }
                }
            }
            .setNegativeButton("Decline") { dialog, _ ->
                stopRingtone()
                dialog.dismiss()
                callManager.sendSignal(callerIp, "REJECT", getSavedUsername())
                saveCallLog("↙ Incoming from $callerName", "Declined")
                resetUiAfterCall("Ready")
            }
            .create()

        incomingDialog?.show()
    }

    private fun finishActiveCall(reason: String) {
        stopRingtone()
        timerHandler.removeCallbacks(timerRunnable)
        setSpeakerphone(false)

        val wasConnected = callStartTime > 0L
        val durationSec = if (wasConnected) (System.currentTimeMillis() - callStartTime) / 1000 else 0L

        if (activePeerName.isNotEmpty()) {
            val prefix = if (isOutgoingCall) "↗ Outgoing to" else "↙ Incoming from"
            val outcome = if (wasConnected) "Duration: ${durationSec}s" else "Cancelled"
            saveCallLog("$prefix $activePeerName", outcome)
        }

        callManager.stopAudioStream {
            runOnUiThread {
                resetUiAfterCall(reason)
            }
        }
    }

    private fun resetUiAfterCall(statusMessage: String) {
        timerHandler.removeCallbacks(timerRunnable)
        callStartTime = 0L
        activePeerIp = ""
        activePeerName = ""
        cardStatus.setCardBackgroundColor(ColorStateList.valueOf(Color.parseColor("#1E293B")))
        btnCall.isEnabled = true
        btnOpenChat.isEnabled = true
        btnSpeaker.isEnabled = false
        btnEndCall.isEnabled = false

        val peer = selectedPeer
        if (peer != null) {
            tvStatusTitle.text = peer.username
            tvStatusSubtitle.text = statusMessage
            btnCall.text = "Call ${peer.username}"
        } else {
            tvStatusTitle.text = "No Contact Selected"
            tvStatusSubtitle.text = statusMessage
            btnCall.text = "Call"
        }
    }

    private fun updateCallButtons(inCall: Boolean) {
        btnCall.isEnabled = !inCall
        btnOpenChat.isEnabled = !inCall
        btnSpeaker.isEnabled = inCall
        btnEndCall.isEnabled = inCall
    }

    @Suppress("DEPRECATION")
    private fun setSpeakerphone(on: Boolean) {
        speakerOn = on
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.mode = if (on) AudioManager.MODE_IN_COMMUNICATION else AudioManager.MODE_NORMAL
            am.isSpeakerphoneOn = on
        } catch (_: Exception) {}
        btnSpeaker.text = if (on) "Speaker: On" else "Speaker: Off"
        btnSpeaker.backgroundTintList = ColorStateList.valueOf(
            Color.parseColor(if (on) "#6366F1" else "#334155")
        )
    }

    private fun getSavedUsername(): String {
        val prefs = getSharedPreferences("lancall_prefs", Context.MODE_PRIVATE)
        return prefs.getString("username", "") ?: ""
    }

    private fun updateProfileHeader(name: String) {
        tvUsername.text = name
        tvAvatarInitial.text = name.firstOrNull()?.uppercase() ?: "U"
    }

    private fun ensureUsernameSet() {
        val current = getSavedUsername()
        if (current.isEmpty()) {
            showUsernameDialog(cancelable = false)
        } else {
            updateProfileHeader(current)
        }
    }

    private fun showUsernameDialog(cancelable: Boolean) {
        val input = EditText(this).apply {
            hint = "Enter your username"
            setText(getSavedUsername())
            setPadding(48, 32, 48, 32)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Your Display Name")
            .setMessage("People on the network will see only this username.")
            .setView(input)
            .setCancelable(cancelable)
            .setPositiveButton("Save", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, "Please enter a username", Toast.LENGTH_SHORT).show()
                } else {
                    getSharedPreferences("lancall_prefs", Context.MODE_PRIVATE)
                        .edit()
                        .putString("username", name)
                        .apply()
                    updateProfileHeader(name)
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun saveCallLog(title: String, outcome: String) {
        try {
            val timestamp = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date())
            val line = "$title|$outcome • $timestamp\n"
            val file = File(filesDir, "call_logs.txt")
            file.appendText(line)
            loadCallLogs()
        } catch (_: Exception) {}
    }

    private fun loadCallLogs() {
        try {
            val file = File(filesDir, "call_logs.txt")
            callLogItems.clear()
            if (file.exists()) {
                val ipRegex = Regex("""\s*\(\d+\.\d+\.\d+\.\d+\)""")
                val lines = file.readLines()
                    .filter { it.isNotBlank() }
                    .map { it.replace(ipRegex, "") }
                    .reversed()
                callLogItems.addAll(lines)
            }
            logsAdapter.notifyDataSetChanged()
        } catch (_: Exception) {}
    }

    private fun clearCallLogs() {
        try {
            val file = File(filesDir, "call_logs.txt")
            if (file.exists()) file.delete()
            loadCallLogs()
        } catch (_: Exception) {}
    }

    private fun createCircleDrawable(hexColor: String): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(hexColor))
        }
    }

    private fun playRingtone() {
        try {
            stopRingtone()
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            activeRingtone = RingtoneManager.getRingtone(applicationContext, uri)
            activeRingtone?.play()
        } catch (_: Exception) {}
    }

    private fun stopRingtone() {
        try {
            activeRingtone?.stop()
        } catch (_: Exception) {}
        activeRingtone = null
    }

    private fun acquireMulticastLock() {
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("lancall_multicast_lock").apply {
                setReferenceCounted(true)
                acquire()
            }
        } catch (_: Exception) {}
    }

    private fun checkAudioPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                RECORD_REQ_CODE
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        timerHandler.removeCallbacks(timerRunnable)
        stopRingtone()
        setSpeakerphone(false)
        try {
            if (multicastLock?.isHeld == true) multicastLock?.release()
        } catch (_: Exception) {}
        callManager.shutdown()
    }
}