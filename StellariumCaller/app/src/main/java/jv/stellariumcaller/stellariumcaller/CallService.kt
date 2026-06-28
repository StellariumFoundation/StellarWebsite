package jv.stellariumcaller.stellariumcaller

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.TimeUnit

class CallService : Service() {
    private lateinit var client: OkHttpClient
    private var webSocket: WebSocket? = null
    private val channelId = "call_service_channel"
    private val serviceId = 1001
    @Volatile private var connected = false

    private var audioPlayer: MediaPlayer? = null
    private var mediaRecorder: MediaRecorder? = null
    private var recordingFile: File? = null
    @Volatile private var recording = false

    private var reconnectAttempts = 0
    private val maxReconnectDelay = 64000L
    @Volatile private var callActive = false
    @Volatile private var showingIncomingUI = false
    @Volatile private var shouldRun = true
    @Volatile private var reconnecting = false

    @Volatile private var currentCallId: Long = -1

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pingJob: Job? = null

    private val audioStorage by lazy { AudioStorage(this) }
    private val repo by lazy { CallLogRepository.getInstance(this) }

    companion object {
        private const val SERVER_URL = "wss://stellarwebsite-ws.onrender.com/ws"

        private val _connectionFlow = MutableStateFlow(false)
        val connectionFlow: StateFlow<Boolean> = _connectionFlow.asStateFlow()

        @JvmStatic
        var instance: CallService? = null

        fun sendAnswer() {
            val svc = instance
            if (svc == null) return
            svc.showingIncomingUI = false
            svc.stopRingtone()
            svc.callActive = true
            if (svc.currentCallId >= 0) {
                svc.repo.updateCallStatus(svc.currentCallId, "answered")
            }
            svc.restoreNotification()
            svc.webSocket?.send("{\"type\":\"call_accepted\"}")
        }

        fun sendEndCall() {
            val svc = instance ?: return
            svc.showingIncomingUI = false
            svc.callActive = false
            svc.currentCallId = -1
            svc.stopRecording()
            svc.stopPlayback()
            svc.stopRingtone()
            svc.webSocket?.send("{\"type\":\"hangup\"}")
            svc.restoreNotification()
        }

        fun isCallActive(): Boolean = instance?.callActive == true

        fun stopRingtoneFromActivity() {
            instance?.stopRingtone()
        }

        fun startRecordingPTT() {
            instance?.startRecording()
        }

        fun stopRecordingPTT() {
            instance?.stopAndSendRecording()
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
        stopRecording()
        stopPlayback()
        stopRingtone()
        webSocket?.close(1000, "Service stopped")
        webSocket = null
        unregisterNetworkMonitor()
        pingJob?.cancel()
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

    private fun startPing() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (isActive && shouldRun) {
                delay(60_000)
                try {
                    webSocket?.send("{\"type\":\"ping\"}")
                } catch (_: Exception) { }
            }
        }
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
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnecting = false
                reconnectAttempts = 0
                if (this@CallService.webSocket !== webSocket) return
                connected = true
                _connectionFlow.value = true
                startForeground(serviceId, getServiceNotification())
                webSocket.send("{\"type\":\"register\",\"role\":\"android\"}")
                startPing()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleSignalingMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                if (callActive) {
                    playAudio(bytes.toByteArray())
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (this@CallService.webSocket !== webSocket) return
                connected = false
                _connectionFlow.value = false
                pingJob?.cancel()
                startForeground(serviceId, getServiceNotification())
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (this@CallService.webSocket !== webSocket) return
                connected = false
                _connectionFlow.value = false
                pingJob?.cancel()
                startForeground(serviceId, getServiceNotification())
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (this@CallService.webSocket !== webSocket) return
                connected = false
                _connectionFlow.value = false
                pingJob?.cancel()
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
            else minOf(maxReconnectDelay, (Math.pow(2.0, (reconnectAttempts - 1).toDouble()) * 1000).toLong())
            if (!shouldRun) return@launch
            delay(delayMs)
            connectWebSocket()
        }
    }

    private fun handleSignalingMessage(message: String) {
        try {
            val json = org.json.JSONObject(message)
            when (json.getString("type")) {
                "incoming_call" -> {
                    if (showingIncomingUI || callActive) return
                    currentCallId = repo.createCallLog("missed").id
                    showIncomingCallUI()
                }
                "hangup", "call_ended" -> {
                    showingIncomingUI = false
                    callActive = false
                    currentCallId = -1
                    stopRingtone()
                    stopPlayback()
                    stopRecording()
                    restoreNotification()
                }
            }
        } catch (_: Exception) { }
    }

    private fun showIncomingCallUI() {
        showingIncomingUI = true
        setMaxVolume()
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
            .addAction(android.R.drawable.ic_btn_speak_now, "Answer", answerPendingIntent)
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

    private fun setMaxVolume() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVol, 0)
    }

    private var ringtonePlayer: MediaPlayer? = null

    private fun stopRingtone() {
        ringtonePlayer?.apply {
            stop()
            release()
        }
        ringtonePlayer = null
    }

    private fun playAudio(data: ByteArray) {
        try {
            val filePath = audioStorage.saveAudio(data, "received")
            repo.addAudioMessage(currentCallId, "received", filePath)

            stopPlayback()

            MediaPlayer().apply {
                setDataSource(filePath)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setOnCompletionListener {
                    release()
                    if (audioPlayer === this) audioPlayer = null
                }
                setOnErrorListener { _, _, _ ->
                    false
                }
                prepare()
                start()
                audioPlayer = this
            }
        } catch (e: Exception) {
            android.util.Log.e("CallService", "playAudio error: ${e.message}", e)
        }
    }

    private fun stopPlayback() {
        try {
            audioPlayer?.apply {
                if (isPlaying) stop()
                release()
            }
        } catch (_: Exception) { }
        audioPlayer = null
    }

    fun startRecording() {
        if (recording || !callActive) return
        try {
            val audioDir = File(filesDir, "stellarium_audio")
            if (!audioDir.exists()) audioDir.mkdirs()

            recordingFile = File(audioDir, "record_${System.currentTimeMillis()}_${(Math.random() * 100000).toInt()}.webm")
            recordingFile?.createNewFile()

            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(applicationContext)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            mediaRecorder?.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.WEBM)
                setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
                setAudioSamplingRate(48000)
                setOutputFile(recordingFile!!.absolutePath)
                prepare()
                start()
            }
            recording = true
        } catch (e: Exception) {
            android.util.Log.e("CallService", "startRecording error: ${e.message}", e)
            recording = false
            mediaRecorder = null
            recordingFile = null
        }
    }

    fun stopAndSendRecording() {
        if (!recording) return
        recording = false

        try {
            mediaRecorder?.apply {
                try { stop() } catch (_: Exception) { }
                release()
            }
            mediaRecorder = null

            val file = recordingFile
            recordingFile = null

            if (file != null && file.exists() && file.length() > 0) {
                repo.addAudioMessage(currentCallId, "sent", file.absolutePath, file.length())
                val data = file.readBytes()
                webSocket?.send(okio.ByteString.of(*data))
            }
        } catch (e: Exception) {
            android.util.Log.e("CallService", "stopAndSendRecording error: ${e.message}", e)
        }
    }

    private fun stopRecording() {
        recording = false
        try {
            mediaRecorder?.apply {
                try { stop() } catch (_: Exception) { }
                release()
            }
        } catch (_: Exception) { }
        mediaRecorder = null
        recordingFile?.delete()
        recordingFile = null
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
