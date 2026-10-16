package com.example.lancall

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.text.format.Formatter
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val callManager = AudioCallManager()
    private val RECORD_REQ_CODE = 101

    private lateinit var tvMyIp: TextView
    private lateinit var etTargetIp: EditText
    private lateinit var tvStatus: TextView
    private lateinit var btnCall: Button
    private lateinit var btnEndCall: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvMyIp = findViewById(R.id.tvMyIp)
        etTargetIp = findViewById(R.id.etTargetIp)
        tvStatus = findViewById(R.id.tvStatus)
        btnCall = findViewById(R.id.btnCall)
        btnEndCall = findViewById(R.id.btnEndCall)

        checkAudioPermissions()
        refreshLocalIp()

        btnCall.setOnClickListener {
            val targetIp = etTargetIp.text.toString().trim()
            if (targetIp.isEmpty()) {
                Toast.makeText(this, "Enter target IP", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            callManager.startCall(targetIp) { inCall ->
                runOnUiThread { updateUi(inCall) }
            }
        }

        btnEndCall.setOnClickListener {
            callManager.endCall { inCall ->
                runOnUiThread { updateUi(inCall) }
            }
        }
    }

    private fun refreshLocalIp() {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ipAddress = Formatter.formatIpAddress(wifiManager.connectionInfo.ipAddress)
        tvMyIp.text = "My IP: $ipAddress"
    }

    private fun updateUi(inCall: Boolean) {
        btnCall.isEnabled = !inCall
        btnEndCall.isEnabled = inCall
        etTargetIp.isEnabled = !inCall
        tvStatus.text = if (inCall) "Status: Active Call" else "Status: Ready"
    }

    private fun checkAudioPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), RECORD_REQ_CODE)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        callManager.endCall {}
    }
}