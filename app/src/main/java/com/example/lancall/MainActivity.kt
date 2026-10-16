package com.example.lancall

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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

class MainActivity : AppCompatActivity() {

    private val callManager = AudioCallManager()
    private val RECORD_REQ_CODE = 101

    private lateinit var tvAvatarInitial: TextView
    private lateinit var tvUsername: TextView
    private lateinit var tvNetworkStatus: TextView
    private lateinit var cardStatus: MaterialCardView
    private lateinit var tvStatusTitle: TextView
    private lateinit var tvStatusSubtitle: TextView
    private lateinit var tvDeviceCount: TextView
    private lateinit var lvDevices: ListView
    private lateinit var btnCall: MaterialButton
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

        tvAvatarInitial = findViewById(R.id.tvAvatarInitial)
        tvUsername = findViewById(R.id.tvUsername)
        tvNetworkStatus = findViewById(R.id.tvNetworkStatus)
        cardStatus = findViewById(R.id.cardStatus)
        tvStatusTitle = findViewById(R.id.tvStatusTitle)
        tvStatusSubtitle = findViewById(R.id.tvStatusSubtitle)
        tvDeviceCount = findViewById(R.id.tvDeviceCount)
        lvDevices = findViewById(R.id.lvDevices)
        btnCall = findViewById(R.id.btnCall)
        btnEndCall = findViewById(R.id.btnEndCall)
        btnChangeName = findViewById(R.id.btnChangeName)
        btnClearLogs = findViewById(R.id.btnClearLogs)
        lvCallLogs = findViewById(R.id.lvCallLogs)

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
                tvStatusSubtitle.text = "Ready to call • Tap Call below"
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

        btnCall.setOnClickListener {
            val target = selectedPeer
            if (target == null) {
                Toast.makeText(this, "Tap a person from the online list first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            activePeerName = target.username
            activePeerIp = target.ip
            isOutgoingCall = true

            cardStatus.setCardBackgroundColor(Color.parseColor("#312E81"))
            tvStatusTitle.text = "Calling $activePeerName..."
            tvStatusSubtitle.text = "Ringing on their device"
            btnCall.isEnabled = false
            btnEndCall.isEnabled = true

            callManager.sendSignal(activePeerIp, "INVITE", getSavedUsername())
        }

        btnEndCall.setOnClickListener {
            if (activePeerIp.isNotEmpty()) {
                callManager.sendSignal(activePeerIp, "END", getSavedUsername())
            }
            finishActiveCall("Call Ended")
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

                    // Keep selection fresh if the selected person is still online
                    selectedPeer?.let { current ->
                        selectedPeer = peersList.find { it.ip == current.ip || it.username == current.username }
                    }

                    tvDeviceCount.text = "PEOPLE ON NETWORK (${peersList.size})"
                    devicesAdapter.notifyDataSetChanged()
                }
            },
            onSignalReceived = { type, senderIp, senderName ->
                runOnUiThread {
                    handleIncomingSignal(type, senderIp, senderName)
                }
            }
        )
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
                    setPadding(28, 22, 28, 22)
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
                    text = peer.username.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
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
                        marginStart = 28
                    }
                }

                val nameTv = TextView(this@MainActivity).apply {
                    text = peer.username
                    setTextColor(Color.parseColor("#F8FAFC"))
                    textSize = 16f
                    typeface = Typeface.DEFAULT_BOLD
                }

                val subTv = TextView(this@MainActivity).apply {
                    text = if (isSelected) "Selected for call" else "Tap to select"
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
                cardStatus.setCardBackgroundColor(Color.parseColor("#065F46"))
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
                cardStatus.setCardBackgroundColor(Color.parseColor("#065F46"))
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
        cardStatus.setCardBackgroundColor(Color.parseColor("#1E293B"))
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

    // --- Username Profile ---

    private fun getSavedUsername(): String {
        val prefs = getSharedPreferences("lancall_prefs", Context.MODE_PRIVATE)
        return prefs.getString("username", "") ?: ""
    }

    private fun updateProfileHeader(name: String) {
        tvUsername.text = name
        tvAvatarInitial.text = name.firstOrNull()?.uppercaseChar()?.toString() ?: "U"
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

    // --- Call Log Storage (Hides any IP addresses) ---

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

    // --- Ringtone & Permissions ---

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
        try {
            if (multicastLock?.isHeld == true) multicastLock?.release()
        } catch (_: Exception) {}
        callManager.shutdown()
    }
}