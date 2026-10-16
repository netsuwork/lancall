package com.example.lancall

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val callManager = AudioCallManager()
    private val RECORD_REQ_CODE = 101

    private lateinit var tvUsername: TextView
    private lateinit var tvMyIp: TextView
    private lateinit var tvDeviceCount: TextView
    private lateinit var lvDevices: ListView
    private lateinit var etTargetIp: EditText
    private lateinit var tvStatus: TextView
    private lateinit var btnCall: Button
    private lateinit var btnEndCall: Button
    private lateinit var btnChangeName: Button
    private lateinit var btnClearLogs: Button
    private lateinit var lvCallLogs: ListView

    private val discoveredPeers = mutableListOf<PeerDevice>()
    private lateinit var devicesAdapter: ArrayAdapter<String>
    private lateinit var logsAdapter: ArrayAdapter<String>

    private var selectedPeerName: String = "Peer"
    private var activePeerIp: String = ""
    private var activePeerName: String = ""
    private var isOutgoingCall: Boolean = false
    private var callStartTime: Long = 0L

    private var incomingDialog: AlertDialog? = null
    private var activeRingtone: Ringtone? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvUsername = findViewById(R.id.tvUsername)
        tvMyIp = findViewById(R.id.tvMyIp)
        tvDeviceCount = findViewById(R.id.tvDeviceCount)
        lvDevices = findViewById(R.id.lvDevices)
        etTargetIp = findViewById(R.id.etTargetIp)
        tvStatus = findViewById(R.id.tvStatus)
        btnCall = findViewById(R.id.btnCall)
        btnEndCall = findViewById(R.id.btnEndCall)
        btnChangeName = findViewById(R.id.btnChangeName)
        btnClearLogs = findViewById(R.id.btnClearLogs)
        lvCallLogs = findViewById(R.id.lvCallLogs)

        devicesAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        lvDevices.adapter = devicesAdapter

        logsAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        lvCallLogs.adapter = logsAdapter

        acquireMulticastLock()
        checkAudioPermissions()
        ensureUsernameSet()
        loadCallLogs()

        tvMyIp.text = "My IP: ${callManager.getLocalIpAddress()}"

        // Select device when tapped from the network list
        lvDevices.setOnItemClickListener { _, _, position, _ ->
            if (position in discoveredPeers.indices) {
                val peer = discoveredPeers[position]
                selectedPeerName = peer.username
                etTargetIp.setText(peer.ip)
                tvStatus.text = "Selected: ${peer.username} (${peer.ip})"
            }
        }

        btnChangeName.setOnClickListener {
            showUsernameDialog(cancelable = true)
        }

        btnClearLogs.setOnClickListener {
            clearCallLogs()
        }

        btnCall.setOnClickListener {
            val targetIp = etTargetIp.text.toString().trim()
            if (targetIp.isEmpty()) {
                Toast.makeText(this, "Select a device or enter an IP address", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val matchedPeer = discoveredPeers.find { it.ip == targetIp }
            activePeerName = matchedPeer?.username ?: selectedPeerName
            activePeerIp = targetIp
            isOutgoingCall = true

            tvStatus.text = "Calling $activePeerName ($activePeerIp)..."
            btnCall.isEnabled = false
            btnEndCall.isEnabled = true
            etTargetIp.isEnabled = false

            callManager.sendSignal(activePeerIp, "INVITE", getSavedUsername())
        }

        btnEndCall.setOnClickListener {
            if (activePeerIp.isNotEmpty()) {
                callManager.sendSignal(activePeerIp, "END", getSavedUsername())
            }
            finishActiveCall("Ended")
        }

        // Start background discovery and signaling listeners
        callManager.startNetworkServices(
            getUsername = { getSavedUsername() },
            onPeersUpdated = { peersList ->
                runOnUiThread {
                    tvMyIp.text = "My IP: ${callManager.getLocalIpAddress()}"
                    discoveredPeers.clear()
                    discoveredPeers.addAll(peersList)
                    tvDeviceCount.text = "Devices on Network: ${peersList.size} (Tap to select)"
                    devicesAdapter.clear()
                    if (peersList.isEmpty()) {
                        devicesAdapter.add("Scanning network for online devices...")
                    } else {
                        devicesAdapter.addAll(peersList.map { "🟢 ${it.username}  —  ${it.ip}" })
                    }
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
                tvStatus.text = "In Call with $senderName"
                callManager.startAudioStream(senderIp) { inCall ->
                    runOnUiThread { updateCallButtons(inCall) }
                }
            }
            "REJECT" -> {
                saveCallLog("Outgoing to $senderName ($senderIp) — Rejected")
                resetUiAfterCall("Call rejected by $senderName")
                Toast.makeText(this, "$senderName rejected the call", Toast.LENGTH_SHORT).show()
            }
            "END" -> {
                incomingDialog?.dismiss()
                stopRingtone()
                finishActiveCall("Ended by $senderName")
            }
        }
    }

    private fun showIncomingCallDialog(callerName: String, callerIp: String) {
        playRingtone()
        incomingDialog?.dismiss()

        incomingDialog = AlertDialog.Builder(this)
            .setTitle("Incoming Call")
            .setMessage("$callerName ($callerIp) is calling you on the local network.")
            .setCancelable(false)
            .setPositiveButton("Accept") { dialog, _ ->
                stopRingtone()
                dialog.dismiss()
                callStartTime = System.currentTimeMillis()
                tvStatus.text = "In Call with $callerName"
                callManager.sendSignal(callerIp, "ACCEPT", getSavedUsername())
                callManager.startAudioStream(callerIp) { inCall ->
                    runOnUiThread { updateCallButtons(inCall) }
                }
            }
            .setNegativeButton("Reject") { dialog, _ ->
                stopRingtone()
                dialog.dismiss()
                callManager.sendSignal(callerIp, "REJECT", getSavedUsername())
                saveCallLog("Incoming from $callerName ($callerIp) — Rejected")
                resetUiAfterCall("Ready")
            }
            .create()

        incomingDialog?.show()
    }

    private fun finishActiveCall(reason: String) {
        stopRingtone()
        val wasConnected = callStartTime > 0L
        val durationSec = if (wasConnected) (System.currentTimeMillis() - callStartTime) / 1000 else 0L

        if (activePeerIp.isNotEmpty()) {
            val direction = if (isOutgoingCall) "Outgoing to" else "Incoming from"
            val details = if (wasConnected) "Connected (${durationSec}s)" else "Cancelled"
            saveCallLog("$direction $activePeerName ($activePeerIp) — $details")
        }

        callManager.stopAudioStream {
            runOnUiThread {
                resetUiAfterCall("Status: $reason")
            }
        }
    }

    private fun resetUiAfterCall(statusMessage: String) {
        callStartTime = 0L
        activePeerIp = ""
        btnCall.isEnabled = true
        btnEndCall.isEnabled = false
        etTargetIp.isEnabled = true
        tvStatus.text = statusMessage
    }

    private fun updateCallButtons(inCall: Boolean) {
        btnCall.isEnabled = !inCall
        btnEndCall.isEnabled = inCall
        etTargetIp.isEnabled = !inCall
    }

    // --- Username Storage (SharedPreferences) ---

    private fun getSavedUsername(): String {
        val prefs = getSharedPreferences("lancall_prefs", Context.MODE_PRIVATE)
        return prefs.getString("username", "") ?: ""
    }

    private fun ensureUsernameSet() {
        val current = getSavedUsername()
        if (current.isEmpty()) {
            showUsernameDialog(cancelable = false)
        } else {
            tvUsername.text = "User: $current"
        }
    }

    private fun showUsernameDialog(cancelable: Boolean) {
        val input = EditText(this).apply {
            hint = "Enter your name (e.g. Alex)"
            setText(getSavedUsername())
            setPadding(48, 32, 48, 32)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Set Your Username")
            .setMessage("This name will be shown to other people on the Wi-Fi network.")
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
                    tvUsername.text = "User: $name"
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    // --- Local Device Storage Call Logs (filesDir/call_logs.txt) ---

    private fun saveCallLog(entry: String) {
        try {
            val timestamp = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date())
            val line = "[$timestamp] $entry\n"
            val file = File(filesDir, "call_logs.txt")
            file.appendText(line)
            loadCallLogs()
        } catch (_: Exception) {}
    }

    private fun loadCallLogs() {
        try {
            val file = File(filesDir, "call_logs.txt")
            logsAdapter.clear()
            if (file.exists()) {
                val lines = file.readLines().filter { it.isNotBlank() }.reversed()
                if (lines.isEmpty()) {
                    logsAdapter.add("No calls recorded yet.")
                } else {
                    logsAdapter.addAll(lines)
                }
            } else {
                logsAdapter.add("No calls recorded yet.")
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
        stopRingtone()
        try {
            if (multicastLock?.isHeld == true) multicastLock?.release()
        } catch (_: Exception) {}
        callManager.shutdown()
    }
}