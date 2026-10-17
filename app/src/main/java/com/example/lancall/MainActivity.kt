package com.example.lancall

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
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

class MainActivity : AppCompatActivity() {

    private val RECORD_REQ_CODE = 101

    private var service: CallService? = null
    private lateinit var rootContainer: FrameLayout
    private lateinit var callScreen: CallScreenView

    private lateinit var tvAvatarInitial: TextView
    private lateinit var tvUsername: TextView
    private lateinit var tvNetworkStatus: TextView
    private lateinit var cardStatus: MaterialCardView
    private lateinit var tvStatusTitle: TextView
    private lateinit var tvStatusSubtitle: TextView
    private lateinit var tvDeviceCount: TextView
    private lateinit var lvDevices: ListView
    private lateinit var btnCall: MaterialButton
    private lateinit var btnChat: MaterialButton
    private lateinit var btnEndCall: MaterialButton
    private lateinit var btnChangeName: MaterialButton
    private lateinit var btnClearLogs: MaterialButton
    private lateinit var lvCallLogs: ListView

    private val discoveredPeers = mutableListOf<PeerDevice>()
    private val callLogItems = mutableListOf<String>()

    private lateinit var devicesAdapter: BaseAdapter
    private lateinit var logsAdapter: BaseAdapter

    private var selectedPeer: PeerDevice? = null
    private var activePeerIp: String = ""
    private var activePeerName: String = ""
    private var isOutgoingCall: Boolean = false
    private var callStartTime: Long = 0L

    private var activeRingtone: Ringtone? = null

    private val timerHandler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            if (callStartTime > 0L) {
                val elapsed = (System.currentTimeMillis() - callStartTime) / 1000
                val mins = elapsed / 60
                val secs = elapsed % 60
                val formatted = String.format(Locale.US, "Connected • %02d:%02d", mins, secs)
                tvStatusSubtitle.text = formatted
                if (callScreen.isShowing) {
                    callScreen.setStatus(formatted)
                }
                timerHandler.postDelayed(this, 1000)
            }
        }
    }

    private val chatUpdateListener: (String) -> Unit = {
        runOnUiThread { devicesAdapter.notifyDataSetChanged() }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as CallService.LocalBinder).getService()
            service = svc
            svc.appVisible = true
            svc.chatManager.addListener(chatUpdateListener)

            svc.peersListener = { peersList ->
                runOnUiThread {
                    val hasIp = svc.callManager.getLocalIpAddress() != "0.0.0.0"
                    tvNetworkStatus.text = if (hasIp) "● Active on Local Network" else "○ Waiting for Wi-Fi / Hotspot"
                    tvNetworkStatus.setTextColor(
                        Color.parseColor(if (hasIp) "#94A3B8" else "#64748B")
                    )

                    discoveredPeers.clear()
                    discoveredPeers.addAll(peersList)

                    selectedPeer?.let { current ->
                        selectedPeer = peersList.find { it.ip == current.ip || it.username == current.username }
                    }

                    tvDeviceCount.text = "PEOPLE ON NETWORK (${peersList.size})"
                    devicesAdapter.notifyDataSetChanged()
                }
            }

            svc.signalListener = { type, senderIp, senderName ->
                runOnUiThread {
                    handleIncomingSignal(type, senderIp, senderName)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        rootContainer = findViewById(R.id.rootContainer)
        tvAvatarInitial = findViewById(R.id.tvAvatarInitial)
        tvUsername = findViewById(R.id.tvUsername)
        tvNetworkStatus = findViewById(R.id.tvNetworkStatus)
        cardStatus = findViewById(R.id.cardStatus)
        tvStatusTitle = findViewById(R.id.tvStatusTitle)
        tvStatusSubtitle = findViewById(R.id.tvStatusSubtitle)
        tvDeviceCount = findViewById(R.id.tvDeviceCount)
        lvDevices = findViewById(R.id.lvDevices)
        btnCall = findViewById(R.id.btnCall)
        btnChat = findViewById(R.id.btnChat)
        btnEndCall = findViewById(R.id.btnEndCall)
        btnChangeName = findViewById(R.id.btnChangeName)
        btnClearLogs = findViewById(R.id.btnClearLogs)
        lvCallLogs = findViewById(R.id.lvCallLogs)

        tvAvatarInitial.background = createCircleDrawable("#475569")

        callScreen = CallScreenView(this).apply {
            onAccept = { acceptIncomingCall() }
            onDecline = { declineIncomingCall() }
            onEnd = { endCurrentCall() }
            onSpeakerToggle = { speakerOn -> setSpeakerphone(speakerOn) }
        }
        rootContainer.addView(
            callScreen,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        setupAdapters()
        checkPermissions()
        ensureUsernameSet()
        loadCallLogs()

        val svcIntent = Intent(this, CallService::class.java)
        startService(svcIntent)
        bindService(svcIntent, serviceConnection, Context.BIND_AUTO_CREATE)

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

        btnChat.setOnClickListener {
            val target = selectedPeer
            if (target == null) {
                Toast.makeText(this, "Tap a person from the online list first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            openChat(target)
        }

        btnCall.setOnClickListener {
            val target = selectedPeer
            if (target == null) {
                Toast.makeText(this, "Tap a person from the online list first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startOutgoingCall(target)
        }

        btnEndCall.setOnClickListener {
            endCurrentCall()
        }
    }

    override fun onStart() {
        super.onStart()
        service?.appVisible = true
    }

    override fun onStop() {
        super.onStop()
        service?.appVisible = false
    }

    override fun onResume() {
        super.onResume()
        devicesAdapter.notifyDataSetChanged()
    }

    private fun openChat(peer: PeerDevice) {
        val intent = Intent(this, ChatActivity::class.java).apply {
            putExtra(ChatActivity.EXTRA_PEER_IP, peer.ip)
            putExtra(ChatActivity.EXTRA_PEER_NAME, peer.username)
        }
        startActivity(intent)
    }

    private fun startOutgoingCall(target: PeerDevice) {
        val svc = service ?: return
        activePeerName = target.username
        activePeerIp = target.ip
        isOutgoingCall = true

        cardStatus.setCardBackgroundColor(ColorStateList.valueOf(Color.parseColor("#334155")))
        tvStatusTitle.text = "Calling $activePeerName..."
        tvStatusSubtitle.text = "Ringing on their device"
        btnCall.isEnabled = false
        btnEndCall.isEnabled = true

        callScreen.showOutgoing(activePeerName)
        svc.callManager.sendSignal(activePeerIp, "INVITE", getSavedUsername())
    }

    private fun acceptIncomingCall() {
        val svc = service ?: return
        stopRingtone()
        svc.cancelIncomingNotification()

        callStartTime = System.currentTimeMillis()
        cardStatus.setCardBackgroundColor(ColorStateList.valueOf(Color.parseColor("#334155")))
        tvStatusTitle.text = "In Call with $activePeerName"
        callScreen.showActive(activePeerName)
        timerHandler.post(timerRunnable)

        svc.onCallStarted(activePeerName)
        svc.callManager.sendSignal(activePeerIp, "ACCEPT", getSavedUsername())
        svc.callManager.startAudioStream(activePeerIp) { inCall ->
            runOnUiThread { updateCallButtons(inCall) }
        }
    }

    private fun declineIncomingCall() {
        val svc = service ?: return
        stopRingtone()
        svc.cancelIncomingNotification()
        callScreen.hide()

        if (activePeerIp.isNotEmpty()) {
            svc.callManager.sendSignal(activePeerIp, "REJECT", getSavedUsername())
            saveCallLog("↙ Incoming from $activePeerName", "Declined")
        }
        resetUiAfterCall("Ready")
    }

    private fun endCurrentCall() {
        val svc = service
        if (activePeerIp.isNotEmpty()) {
            svc?.callManager?.sendSignal(activePeerIp, "END", getSavedUsername())
        }
        finishActiveCall("Call Ended")
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
                val unread = service?.chatManager?.unreadCount(peer.ip) ?: 0

                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(28, 22, 28, 22)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 32f
                        setColor(Color.parseColor(if (isSelected) "#334155" else "#0F172A"))
                        setStroke(
                            if (isSelected) 2 else 1,
                            Color.parseColor(if (isSelected) "#64748B" else "#334155")
                        )
                    }
                }

                val avatar = TextView(this@MainActivity).apply {
                    text = peer.username.firstOrNull()?.uppercase() ?: "?"
                    setTextColor(Color.WHITE)
                    textSize = 16f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    background = createCircleDrawable(if (isSelected) "#475569" else "#334155")
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
                    text = when {
                        unread > 0 -> "💬 $unread new message${if (unread > 1) "s" else ""}"
                        isSelected -> "Selected • Tap Call or Chat"
                        else -> "Tap to select"
                    }
                    setTextColor(
                        Color.parseColor(
                            when {
                                unread > 0 -> "#94A3B8"
                                isSelected -> "#CBD5E1"
                                else -> "#64748B"
                            }
                        )
                    )
                    textSize = 12f
                }

                nameCol.addView(nameTv)
                nameCol.addView(subTv)

                val badge = TextView(this@MainActivity).apply {
                    text = "● Online"
                    setTextColor(Color.parseColor("#94A3B8"))
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
    }

    private fun handleIncomingSignal(type: String, senderIp: String, senderName: String) {
        val svc = service ?: return
        when (type) {
            "INVITE" -> {
                activePeerIp = senderIp
                activePeerName = senderName
                isOutgoingCall = false
                playRingtone()
                callScreen.showIncoming(senderName)
            }
            "ACCEPT" -> {
                callStartTime = System.currentTimeMillis()
                cardStatus.setCardBackgroundColor(ColorStateList.valueOf(Color.parseColor("#334155")))
                tvStatusTitle.text = "In Call with $senderName"
                callScreen.showActive(senderName)
                timerHandler.post(timerRunnable)
                svc.onCallStarted(senderName)
                svc.callManager.startAudioStream(senderIp) { inCall ->
                    runOnUiThread { updateCallButtons(inCall) }
                }
            }
            "REJECT" -> {
                callScreen.hide()
                saveCallLog("↗ Outgoing to $senderName", "Declined")
                resetUiAfterCall("Call declined by $senderName")
                Toast.makeText(this, "$senderName declined the call", Toast.LENGTH_SHORT).show()
            }
            "END" -> {
                stopRingtone()
                svc.cancelIncomingNotification()
                callScreen.hide()
                finishActiveCall("Call ended by $senderName")
            }
        }
    }

    private fun finishActiveCall(reason: String) {
        stopRingtone()
        timerHandler.removeCallbacks(timerRunnable)
        callScreen.hide()
        setSpeakerphone(false)

        val wasConnected = callStartTime > 0L
        val durationSec = if (wasConnected) (System.currentTimeMillis() - callStartTime) / 1000 else 0L

        if (activePeerName.isNotEmpty()) {
            val prefix = if (isOutgoingCall) "↗ Outgoing to" else "↙ Incoming from"
            val outcome = if (wasConnected) "Duration: ${durationSec}s" else "Cancelled"
            saveCallLog("$prefix $activePeerName", outcome)
        }

        service?.onCallEnded()
        service?.callManager?.stopAudioStream {
            runOnUiThread {
                resetUiAfterCall(reason)
            }
        } ?: resetUiAfterCall(reason)
    }

    private fun resetUiAfterCall(statusMessage: String) {
        timerHandler.removeCallbacks(timerRunnable)
        callStartTime = 0L
        activePeerIp = ""
        activePeerName = ""
        cardStatus.setCardBackgroundColor(ColorStateList.valueOf(Color.parseColor("#1E293B")))
        btnCall.isEnabled = true
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
        btnEndCall.isEnabled = inCall
    }

    @Suppress("DEPRECATION")
    private fun setSpeakerphone(on: Boolean) {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.mode = if (on) AudioManager.MODE_IN_COMMUNICATION else AudioManager.MODE_NORMAL
            am.isSpeakerphoneOn = on
        } catch (_: Exception) {}
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

    private fun checkPermissions() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), RECORD_REQ_CODE)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        timerHandler.removeCallbacks(timerRunnable)
        stopRingtone()
        service?.chatManager?.removeListener(chatUpdateListener)
        service?.peersListener = null
        service?.signalListener = null
        try {
            unbindService(serviceConnection)
        } catch (_: Exception) {}
        service = null
    }
}