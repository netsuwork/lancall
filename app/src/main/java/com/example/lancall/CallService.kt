package com.example.lancall

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Owns the AudioCallManager so discovery, signaling and calls live as long as
 * this service, not as long as the Activity. While a call is active the service
 * is promoted to a foreground service (microphone type) so Android keeps the
 * mic and sockets alive when the app is in the background.
 */
class CallService : Service() {

    companion object {
        private const val CHANNEL_CALL = "lancall_active_call"
        private const val CHANNEL_INCOMING = "lancall_incoming"
        private const val NOTIF_CALL = 1
        private const val NOTIF_INCOMING = 2
    }

    inner class LocalBinder : Binder() {
        fun getService(): CallService = this@CallService
    }

    private val binder = LocalBinder()
    val callManager = AudioCallManager()

    @Volatile private var lastPeers: List<PeerDevice> = emptyList()
    @Volatile private var pendingInvite: Triple<String, String, String>? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var inForeground = false

    /** Set by the Activity in onStart/onStop. Used to decide whether to show a notification. */
    @Volatile var appVisible = false

    @Volatile
    var peersListener: ((List<PeerDevice>) -> Unit)? = null
        set(value) {
            field = value
            value?.invoke(lastPeers)
        }

    /** Receives (type, senderIp, senderName). Setting it replays an invite that arrived while no UI existed. */
    @Volatile
    var signalListener: ((String, String, String) -> Unit)? = null
        set(value) {
            field = value
            val pending = pendingInvite
            if (value != null && pending != null) {
                pendingInvite = null
                value(pending.first, pending.second, pending.third)
            }
        }

    override fun onCreate() {
        super.onCreate()
        createChannels()
        acquireMulticastLock()
        callManager.startNetworkServices(
            getUsername = {
                getSharedPreferences("lancall_prefs", Context.MODE_PRIVATE)
                    .getString("username", "") ?: ""
            },
            onPeersUpdated = { peers ->
                lastPeers = peers
                peersListener?.invoke(peers)
            },
            onSignalReceived = { type, ip, name -> dispatchSignal(type, ip, name) }
        )
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        callManager.shutdown()
        try {
            if (multicastLock?.isHeld == true) multicastLock?.release()
        } catch (_: Exception) {}
        notificationManager().cancel(NOTIF_INCOMING)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- signals

    private fun dispatchSignal(type: String, ip: String, name: String) {
        val listener = signalListener

        if (type == "INVITE" && !appVisible) showIncomingNotification(name)
        if (type == "END") notificationManager().cancel(NOTIF_INCOMING)

        if (listener != null) {
            listener(type, ip, name)
            return
        }

        // No Activity is attached.
        when (type) {
            "INVITE" -> pendingInvite = Triple(type, ip, name)
            "END" -> {
                pendingInvite = null
                // The other side hung up while our UI was gone: stop the call here.
                callManager.stopAudioStream { }
                onCallEnded()
            }
        }
    }

    fun cancelIncomingNotification() {
        notificationManager().cancel(NOTIF_INCOMING)
    }

    // ------------------------------------------------------- foreground state

    fun onCallStarted(peerName: String) {
        val notification = buildCallNotification(peerName)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_CALL, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIF_CALL, notification)
            }
            inForeground = true
        } catch (e: Exception) {
            // Usually means RECORD_AUDIO was not granted.
            Log.e("CallService", "Could not start foreground service", e)
        }
    }

    fun onCallEnded() {
        if (inForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            inForeground = false
        }
    }

    // ---------------------------------------------------------- notifications

    private fun notificationManager(): NotificationManager =
        getSystemService(NotificationManager::class.java)

    private fun createChannels() {
        val nm = notificationManager()
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_CALL, "Ongoing call", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_INCOMING, "Incoming calls", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun buildCallNotification(peerName: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_CALL)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle("LAN Call")
            .setContentText("In call with $peerName")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setUsesChronometer(true)
            .setWhen(System.currentTimeMillis())
            .setContentIntent(openAppIntent())
            .build()

    private fun showIncomingNotification(callerName: String) {
        val n = NotificationCompat.Builder(this, CHANNEL_INCOMING)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle("Incoming call")
            .setContentText("$callerName is calling you")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()
        notificationManager().notify(NOTIF_INCOMING, n)
    }

    private fun acquireMulticastLock() {
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("lancall_multicast_lock").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {}
    }
}