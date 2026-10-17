package com.example.lancall

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.util.Log
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketAddress
import java.util.concurrent.ConcurrentHashMap

data class PeerDevice(
    val ip: String,
    val username: String,
    val lastSeen: Long
)

class AudioCallManager {
    companion object {
        const val SAMPLE_RATE = 16000
        const val AUDIO_PORT = 50005
        const val DISCOVERY_PORT = 50006
        const val SIGNAL_PORT = 50007
    }

    private var isCalling = false
    private var sendSocket: DatagramSocket? = null
    private var receiveSocket: DatagramSocket? = null
    private var discoverySocket: DatagramSocket? = null
    private var signalSocket: DatagramSocket? = null

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var aec: AcousticEchoCanceler? = null

    private val callScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val networkScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val peers = ConcurrentHashMap<String, PeerDevice>()

    fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (!networkInterface.isUp || networkInterface.isLoopback) continue
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress ?: "0.0.0.0"
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("AudioCallManager", "Error getting local IP", e)
        }
        return "0.0.0.0"
    }

    private fun getBroadcastAddresses(): List<InetAddress> {
        val list = mutableListOf<InetAddress>()
        try {
            list.add(InetAddress.getByName("255.255.255.255"))
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (!networkInterface.isUp || networkInterface.isLoopback) continue
                for (interfaceAddress in networkInterface.interfaceAddresses) {
                    val broadcast = interfaceAddress.broadcast
                    if (broadcast != null && !list.contains(broadcast)) {
                        list.add(broadcast)
                    }
                }
            }
        } catch (_: Exception) {}
        return list
    }

    fun startNetworkServices(
        getUsername: () -> String,
        onPeersUpdated: (List<PeerDevice>) -> Unit,
        onSignalReceived: (type: String, senderIp: String, senderName: String) -> Unit
    ) {
        networkScope.launch {
            try {
                discoverySocket = DatagramSocket(null as SocketAddress?).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(DISCOVERY_PORT))
                }
                val buffer = ByteArray(512)
                while (isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    discoverySocket?.receive(packet)
                    val msg = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    val senderIp = packet.address?.hostAddress ?: continue
                    val myIp = getLocalIpAddress()

                    if (senderIp != myIp && msg.startsWith("PRESENCE|")) {
                        val peerName = msg.substringAfter("PRESENCE|").trim().ifEmpty { "Device" }
                        peers[senderIp] = PeerDevice(senderIp, peerName, System.currentTimeMillis())
                    }
                }
            } catch (e: Exception) {
                Log.e("AudioCallManager", "Discovery receiver error", e)
            }
        }

        networkScope.launch {
            val sendSock = DatagramSocket().apply { broadcast = true }
            while (isActive) {
                try {
                    val name = getUsername().ifEmpty { "User" }
                    val data = "PRESENCE|$name".toByteArray(Charsets.UTF_8)
                    for (bcast in getBroadcastAddresses()) {
                        val packet = DatagramPacket(data, data.size, bcast, DISCOVERY_PORT)
                        sendSock.send(packet)
                    }

                    val now = System.currentTimeMillis()
                    val iterator = peers.entries.iterator()
                    while (iterator.hasNext()) {
                        val entry = iterator.next()
                        if (now - entry.value.lastSeen > 8000) {
                            iterator.remove()
                        }
                    }

                    onPeersUpdated(peers.values.sortedBy { it.username.lowercase() })
                } catch (_: Exception) {}
                delay(2000)
            }
        }

        networkScope.launch {
            try {
                signalSocket = DatagramSocket(null as SocketAddress?).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(SIGNAL_PORT))
                }
                val buffer = ByteArray(512)
                while (isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    signalSocket?.receive(packet)
                    val msg = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    val senderIp = packet.address?.hostAddress ?: continue
                    val parts = msg.split("|", limit = 2)
                    if (parts.size == 2) {
                        onSignalReceived(parts[0], senderIp, parts[1])
                    }
                }
            } catch (e: Exception) {
                Log.e("AudioCallManager", "Signal receiver error", e)
            }
        }
    }

    fun sendSignal(targetIp: String, type: String, myUsername: String) {
        networkScope.launch {
            try {
                val msg = "$type|$myUsername"
                val data = msg.toByteArray(Charsets.UTF_8)
                val address = InetAddress.getByName(targetIp)
                val packet = DatagramPacket(data, data.size, address, SIGNAL_PORT)
                DatagramSocket().use { sock ->
                    sock.send(packet)
                }
            } catch (e: Exception) {
                Log.e("AudioCallManager", "Failed to send signal $type", e)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startAudioStream(targetIp: String, onStateChanged: (Boolean) -> Unit) {
        if (isCalling) return
        isCalling = true
        onStateChanged(true)

        val channelIn = AudioFormat.CHANNEL_IN_MONO
        val channelOut = AudioFormat.CHANNEL_OUT_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT

        val minRecBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, channelIn, encoding)
        val minTrackBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, channelOut, encoding)
        val bufferSize = maxOf(minRecBuf, minTrackBuf, 1024)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                channelIn,
                encoding,
                bufferSize
            )

            if (AcousticEchoCanceler.isAvailable()) {
                audioRecord?.audioSessionId?.let { id ->
                    aec = AcousticEchoCanceler.create(id)?.apply { enabled = true }
                }
            }

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(encoding)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(channelOut)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .build()

            sendSocket = DatagramSocket()
            receiveSocket = DatagramSocket(null as SocketAddress?).apply {
                reuseAddress = true
                bind(InetSocketAddress(AUDIO_PORT))
            }

            audioRecord?.startRecording()
            audioTrack?.play()

            callScope.launch {
                val targetAddress = InetAddress.getByName(targetIp)
                val buffer = ByteArray(bufferSize)
                while (isCalling && isActive) {
                    val readBytes = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (readBytes > 0) {
                        val packet = DatagramPacket(buffer, readBytes, targetAddress, AUDIO_PORT)
                        sendSocket?.send(packet)
                    }
                }
            }

            callScope.launch {
                val buffer = ByteArray(bufferSize)
                while (isCalling && isActive) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        receiveSocket?.receive(packet)
                        audioTrack?.write(packet.data, packet.offset, packet.length)
                    } catch (e: Exception) {
                        if (!isCalling) break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("AudioCallManager", "Audio stream error", e)
            stopAudioStream(onStateChanged)
        }
    }

    fun stopAudioStream(onStateChanged: ((Boolean) -> Unit)? = null) {
        isCalling = false
        callScope.coroutineContext.cancelChildren()

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null

        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null

        aec?.release()
        aec = null

        sendSocket?.close()
        sendSocket = null

        receiveSocket?.close()
        receiveSocket = null

        onStateChanged?.invoke(false)
    }

    fun shutdown() {
        stopAudioStream()
        networkScope.coroutineContext.cancelChildren()
        discoverySocket?.close()
        signalSocket?.close()
    }
}