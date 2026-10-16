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
import java.net.InetAddress

class AudioCallManager {
    companion object {
        const val SAMPLE_RATE = 16000
        const val AUDIO_PORT = 50005
    }

    private var isCalling = false
    private var sendSocket: DatagramSocket? = null
    private var receiveSocket: DatagramSocket? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var aec: AcousticEchoCanceler? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @SuppressLint("MissingPermission")
    fun startCall(targetIp: String, onStateChanged: (Boolean) -> Unit) {
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
            receiveSocket = DatagramSocket(AUDIO_PORT)

            audioRecord?.startRecording()
            audioTrack?.play()

            scope.launch {
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

            scope.launch {
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
            Log.e("AudioCallManager", "Call error", e)
            endCall(onStateChanged)
        }
    }

    fun endCall(onStateChanged: (Boolean) -> Unit) {
        isCalling = false
        scope.coroutineContext.cancelChildren()

        try { audioRecord?.stop(); audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        try { audioTrack?.stop(); audioTrack?.release() } catch (_: Exception) {}
        audioTrack = null
        aec?.release(); aec = null
        sendSocket?.close(); sendSocket = null
        receiveSocket?.close(); receiveSocket = null
        onStateChanged(false)
    }
}