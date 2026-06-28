package jv.stellariumcaller.stellariumcaller

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.AudioRecord
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.audiofx.AcousticEchoCanceler
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import kotlinx.coroutines.*
import okhttp3.*
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import kotlin.math.min

class CallService : Service() {
    private lateinit var client: OkHttpClient
    private var webSocket: WebSocket? = null
    private val channelId = "call_service_channel"
    private val serviceId = 1001
    @Volatile private var connected = false

    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var ringtonePlayer: MediaPlayer? = null
    private var encoder: MediaCodec? = null

    private var exoPlayer: ExoPlayer? = null
    private var dataSource: WebSocketDataSource? = null

    private var reconnectAttempts = 0
    private val maxReconnectDelay = 64000L
    @Volatile private var muted = false
    @Volatile private var callActive = false
    @Volatile private var showingIncomingUI = false
    @Volatile private var capturing = false
    @Volatile private var shouldRun = true
    @Volatile private var reconnecting = false

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    companion object {
        private const val SERVER_URL = "wss://stellarwebsite-ws.onrender.com/ws"
        private const val SAMPLE_RATE = 48000
        private const val CHANNELS = 1
        private const val BITRATE = 96000

        @JvmStatic
        private var instance: CallService? = null

        fun sendAnswer() {
            val svc = instance
            android.util.Log.i("CallService", "sendAnswer called, instance null: ${svc == null}")
            if (svc == null) return
            svc.showingIncomingUI = false
            svc.stopRingtone()
            svc.callActive = true
            svc.restoreNotification()
            svc.startExoPlayer()
            svc.startAudioCapture()
            android.util.Log.i("CallService", "sendAnswer: about to send call_accepted, ws null: ${svc.webSocket == null}")
            svc.webSocket?.send("{\"type\":\"call_accepted\"}")
            android.util.Log.i("CallService", "sendAnswer: call_accepted sent")
        }

        fun sendEndCall() {
            val svc = instance ?: return
            svc.showingIncomingUI = false
            svc.callActive = false
            svc.webSocket?.send("{\"type\":\"hangup\"}")
            svc.stopExoPlayer()
            svc.stopAudioCapture()
            svc.stopRingtone()
            svc.restoreNotification()
        }

        fun isCallActive(): Boolean = instance?.callActive == true

        fun stopRingtoneFromActivity() {
            instance?.stopRingtone()
        }

        fun setMuted(mute: Boolean) {
            instance?.muted = mute
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        shouldRun = true
        createNotificationChannel()
        startForeground(serviceId, getServiceNotification())
        registerNetworkMonitor()
        connectWebSocket()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(serviceId, getServiceNotification())
        return START_STICKY
    }

    override fun onDestroy() {
        shouldRun = false
        showingIncomingUI = false
        instance = null
        stopExoPlayer()
        stopAudioCapture()
        stopRingtone()
        encoder?.stop()
        encoder?.release()
        encoder = null
        webSocket?.close(1000, "Service stopped")
        webSocket = null
        unregisterNetworkMonitor()
        scope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Stellarium Caller",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Active call service for receiving incoming calls"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun getServiceNotification(): Notification {
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Stellarium Caller")
            .setContentText(if (connected) "Connected - Waiting for calls" else "Reconnecting...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun registerNetworkMonitor() {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (shouldRun && (webSocket == null || !isWebSocketOpen())) {
                    scheduleReconnect(0)
                }
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback!!)
    }

    private fun unregisterNetworkMonitor() {
        networkCallback?.let {
            try {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                    .unregisterNetworkCallback(it)
            } catch (_: Exception) {}
            networkCallback = null
        }
    }

    private fun isWebSocketOpen(): Boolean {
        return webSocket?.let {
            try {
                it.send("{\"type\":\"ping\"}")
                true
            } catch (_: Exception) {
                false
            }
        } ?: false
    }

    private fun connectWebSocket() {
        if (!shouldRun) return

        webSocket?.close(1000, "Reconnecting")
        webSocket = null

        client = OkHttpClient.Builder()
            .pingInterval(30, TimeUnit.SECONDS)
            .connectionSpecs(listOf(
                ConnectionSpec.MODERN_TLS,
                ConnectionSpec.CLEARTEXT
            ))
            .retryOnConnectionFailure(true)
            .build()

        val request = Request.Builder()
            .url(SERVER_URL)
            .build()

        val newSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                reconnecting = false
                reconnectAttempts = 0
                if (this@CallService.webSocket !== ws) return
                connected = true
                android.util.Log.i("CallService", "WebSocket connected, registering as android")
                startForeground(serviceId, getServiceNotification())
                ws.send("{\"type\":\"register\",\"role\":\"android\"}")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleSignalingMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                if (callActive) {
                    dataSource?.feedAudioData(bytes.toByteArray())
                }
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                android.util.Log.i("CallService", "WebSocket onClosing: $code $reason")
                if (this@CallService.webSocket !== ws) return
                connected = false
                startForeground(serviceId, getServiceNotification())
                scheduleReconnect()
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                android.util.Log.i("CallService", "WebSocket onClosed: $code $reason")
                if (this@CallService.webSocket !== ws) return
                connected = false
                startForeground(serviceId, getServiceNotification())
                scheduleReconnect()
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                android.util.Log.e("CallService", "WebSocket onFailure: ${t.message}", t)
                if (this@CallService.webSocket !== ws) return
                connected = false
                startForeground(serviceId, getServiceNotification())
                scheduleReconnect()
            }
        })
        webSocket = newSocket
    }

    private fun scheduleReconnect(forcedDelay: Long = -1) {
        if (!shouldRun || reconnecting) return
        reconnecting = true
        reconnectAttempts++
        scope.launch {
            val delayMs = if (forcedDelay >= 0) forcedDelay
            else min(maxReconnectDelay.toDouble(), Math.pow(2.0, (reconnectAttempts - 1).toDouble()) * 1000).toLong()
            if (!shouldRun) return@launch
            delay(delayMs)
            connectWebSocket()
        }
    }

    private fun handleSignalingMessage(message: String) {
        try {
            val json = org.json.JSONObject(message)
            android.util.Log.i("CallService", "Signal received: ${json.getString("type")}")
            when (json.getString("type")) {
                "incoming_call" -> {
                    if (showingIncomingUI || callActive) {
                        android.util.Log.i("CallService", "Ignoring incoming_call (already showing UI or in call)")
                        return
                    }
                    android.util.Log.i("CallService", "Showing incoming call UI")
                    showIncomingCallUI()
                }
                "hangup" -> {
                    android.util.Log.i("CallService", "Hangup received")
                    showingIncomingUI = false
                    callActive = false
                    stopRingtone()
                    stopExoPlayer()
                    stopAudioCapture()
                    restoreNotification()
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("CallService", "Signal parse error: ${e.message}")
        }
    }

    private fun showIncomingCallUI() {
        showingIncomingUI = true
        playRingtone()

        val intent = Intent(this, IncomingCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        try { startActivity(intent) } catch (_: Exception) { }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val answerIntent = Intent(this, CallActionReceiver::class.java).apply {
            action = "ANSWER_CALL"
        }
        val answerPendingIntent = PendingIntent.getBroadcast(
            this, 1, answerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val declineIntent = Intent(this, CallActionReceiver::class.java).apply {
            action = "DECLINE_CALL"
        }
        val declinePendingIntent = PendingIntent.getBroadcast(
            this, 2, declineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Incoming Call")
            .setContentText("Stellarium Foundation")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setFullScreenIntent(pendingIntent, true)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_mic, "Answer", answerPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Decline", declinePendingIntent)
            .build()

        try { startForeground(serviceId, notification) } catch (_: Exception) { }
    }

    private fun restoreNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(1002)
        startForeground(serviceId, getServiceNotification())
    }

    private fun playRingtone() {
        if (ringtonePlayer?.isPlaying == true) return
        val player = MediaPlayer()
        try {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
                    .build()
            )
            player.setDataSource(this, android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE))
            player.isLooping = true
            player.prepare()
            player.start()
            ringtonePlayer = player
        } catch (e: Exception) {
            player.release()
            ringtonePlayer = null
        }
    }

    private fun stopRingtone() {
        ringtonePlayer?.apply {
            stop()
            release()
        }
        ringtonePlayer = null
    }

    private fun startExoPlayer() {
        try {
            dataSource = WebSocketDataSource()
            exoPlayer = ExoPlayer.Builder(this).build()
            val mediaSource = ProgressiveMediaSource.Factory { dataSource!! }
                .createMediaSource(MediaItem.fromUri("live://call"))
            exoPlayer!!.setMediaSource(mediaSource)
            exoPlayer!!.prepare()
            exoPlayer!!.playWhenReady = true
        } catch (_: Exception) { }
    }

    private fun stopExoPlayer() {
        try {
            exoPlayer?.stop()
            exoPlayer?.release()
        } catch (_: Exception) { }
        exoPlayer = null
        try {
            dataSource?.close()
        } catch (_: Exception) { }
        dataSource = null
    }

    private fun startAudioCapture() {
        try {
            val minBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBufferSize <= 0) { stopAudioCapture(); return }
            val bufferSize = minBufferSize * 4

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) { stopAudioCapture(); return }
            if (AcousticEchoCanceler.isAvailable()) {
                try { AcousticEchoCanceler.create(audioRecord!!.audioSessionId)?.enabled = true } catch (_: Exception) { }
            }

            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, SAMPLE_RATE, CHANNELS)
            format.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            format.setInteger(MediaFormat.KEY_COMPLEXITY, 5)
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
            encoder!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder!!.start()

            audioRecord?.startRecording()
            capturing = true

            captureJob = scope.launch {
                try {
                    val pcmBuffer = ShortArray(bufferSize)
                    val info = MediaCodec.BufferInfo()
                    while (isActive) {
                        val read = audioRecord?.read(pcmBuffer, 0, pcmBuffer.size) ?: 0
                        if (read <= 0 || muted) continue

                        val inputIndex = encoder!!.dequeueInputBuffer(10000)
                        if (inputIndex >= 0) {
                            val inputBuf = encoder!!.getInputBuffer(inputIndex)!!
                            inputBuf.clear()
                            val maxShorts = inputBuf.capacity() / 2
                            val toWrite = minOf(read, maxShorts)
                            inputBuf.asShortBuffer().put(pcmBuffer, 0, toWrite)
                            encoder!!.queueInputBuffer(inputIndex, 0, toWrite * 2, System.nanoTime() / 1000, 0)
                        }

                        var outputIndex = encoder!!.dequeueOutputBuffer(info, 10000)
                        while (outputIndex >= 0) {
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                                val outBuf = encoder!!.getOutputBuffer(outputIndex)!!
                                val outData = ByteArray(info.size)
                                outBuf.get(outData)
                                webSocket?.send(okio.ByteString.of(*outData))
                            }
                            encoder!!.releaseOutputBuffer(outputIndex, false)
                            outputIndex = encoder!!.dequeueOutputBuffer(info, 0)
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("CallService", "Audio capture error: ${e.message}", e)
                }
            }
        } catch (e: Exception) {
            stopAudioCapture()
        }
    }

    private fun stopAudioCapture() {
        capturing = false
        captureJob?.cancel()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        try {
            encoder?.stop()
            encoder?.release()
        } catch (_: Exception) { }
        encoder = null
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
