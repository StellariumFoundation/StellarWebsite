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
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.*
import java.util.concurrent.TimeUnit

class CallService : Service() {
    private lateinit var client: OkHttpClient
    private var calleeGetCall: Call? = null
    private val channelId = "call_service_channel"
    private val serviceId = 1001
    @Volatile private var connected = false
    @Volatile private var connectedCallee = false

    private var audioPlayer: MediaPlayer? = null
    private var mediaRecorder: MediaRecorder? = null
    private var recordingFile: File? = null
    @Volatile private var recording = false

    private var reconnectAttempts = 0
    private val maxReconnectDelay = 64000L
    @Volatile private var callActive = false
    @Volatile private var showingIncomingUI = false
    @Volatile private var shouldRun = true

    @Volatile private var currentCallId: Long = -1

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val audioStorage by lazy { AudioStorage(this) }
    private val repo by lazy { CallLogRepository.getInstance(this) }

    companion object {
        private const val SERVER_URL = "https://stellarwebsite-ws.onrender.com"

        private val _connectionFlow = MutableStateFlow(false)
        val connectionFlow: StateFlow<Boolean> = _connectionFlow.asStateFlow()

        @JvmStatic
        var instance: CallService? = null

        fun sendAnswer() {
            val svc = instance ?: return
            svc.showingIncomingUI = false
            svc.callActive = true
            svc.stopRingtone()
            svc.restoreNotification()
            if (svc.currentCallId >= 0) {
                svc.repo.updateCallStatus(svc.currentCallId, "answered")
            }
            svc.answerCall()
        }

        fun sendEndCall() {
            val svc = instance ?: return
            svc.showingIncomingUI = false
            svc.callActive = false
            svc.currentCallId = -1
            svc.stopRecording()
            svc.stopPlayback()
            svc.stopRingtone()
            svc.restoreNotification()
            svc.hangup()
        }

        fun isCallActive(): Boolean = instance?.callActive == true

        fun stopRingtoneFromActivity() { instance?.stopRingtone() }

        fun startRecordingPTT() { instance?.startRecording() }

        fun stopRecordingPTT() { instance?.stopAndSendRecording() }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        shouldRun = true
        createNotificationChannel()
        startForeground(serviceId, getServiceNotification())

        client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .connectionSpecs(listOf(ConnectionSpec.COMPATIBLE_TLS, ConnectionSpec.CLEARTEXT))
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        registerNetworkMonitor()
        connectCallee()
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
        hangup()
        calleeGetCall?.cancel()
        calleeGetCall = null
        unregisterNetworkMonitor()
        scope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Stellarium Caller", NotificationManager.IMPORTANCE_HIGH
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
                if (shouldRun && !connectedCallee) scheduleReconnect(0)
            }
            override fun onLost(network: Network) {
                if (connected) {
                    connected = false
                    connectedCallee = false
                    _connectionFlow.value = false
                    startForeground(serviceId, getServiceNotification())
                }
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        connectivityManager.registerNetworkCallback(request, networkCallback!!)
    }

    private fun unregisterNetworkMonitor() {
        networkCallback?.let {
            try {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it)
            } catch (_: Exception) {}
            networkCallback = null
        }
    }

    private fun connectCallee() {
        if (!shouldRun) return

        calleeGetCall?.cancel()
        calleeGetCall = null

        val request = Request.Builder().url("$SERVER_URL/callee").build()
        val call = client.newCall(request)
        calleeGetCall = call

        call.enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                if (!shouldRun || call.isCanceled()) return
                if (!response.isSuccessful) {
                    android.util.Log.w("CallService", "GET /callee failed: ${response.code}")
                    response.close()
                    scheduleReconnect()
                    return
                }

                reconnectAttempts = 0
                connected = true
                connectedCallee = true
                _connectionFlow.value = true
                startForeground(serviceId, getServiceNotification())
                android.util.Log.i("CallService", "Connected to callee stream")

                try {
                    val input = DataInputStream(response.body!!.byteStream())
                    val header = ByteArray(5)
                    while (shouldRun) {
                        try {
                            input.readFully(header)
                            val type = header[0]
                            val length = ((header[1].toInt() and 0xFF) shl 24) or
                                    ((header[2].toInt() and 0xFF) shl 16) or
                                    ((header[3].toInt() and 0xFF) shl 8) or
                                    (header[4].toInt() and 0xFF)
                            val payload = ByteArray(length)
                            input.readFully(payload)

                            when (type) {
                                0.toByte() -> {
                                    val json = String(payload, Charsets.UTF_8)
                                    handleSignalingMessage(json)
                                }
                                1.toByte() -> {
                                    if (callActive) playAudio(payload)
                                }
                            }
                        } catch (e: EOFException) { break }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("CallService", "Callee stream error: ${e.message}", e)
                } finally {
                    response.close()
                }

                connected = false
                connectedCallee = false
                _connectionFlow.value = false
                startForeground(serviceId, getServiceNotification())
                if (shouldRun) scheduleReconnect()
            }

            override fun onFailure(call: Call, e: IOException) {
                if (!shouldRun || call.isCanceled()) return
                android.util.Log.e("CallService", "GET /callee failure: ${e.message}", e)
                if (connected) {
                    connected = false
                    connectedCallee = false
                    _connectionFlow.value = false
                    startForeground(serviceId, getServiceNotification())
                }
                if (shouldRun) scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect(forcedDelay: Long = -1) {
        if (!shouldRun) return
        reconnectAttempts++
        scope.launch {
            val delayMs = if (forcedDelay >= 0) forcedDelay
            else minOf(maxReconnectDelay, (Math.pow(2.0, (reconnectAttempts - 1).toDouble()) * 1000).toLong())
            if (!shouldRun) return@launch
            delay(delayMs)
            connectCallee()
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
        } catch (_: Exception) {}
    }

    private fun showIncomingCallUI() {
        showingIncomingUI = true
        setMaxVolume()
        playRingtone()

        val intent = Intent(this, IncomingCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        try { startActivity(intent) } catch (_: Exception) {}

        val pendingIntent = PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val answerIntent = Intent(this, CallActionReceiver::class.java).apply { action = "ANSWER_CALL" }
        val answerPendingIntent = PendingIntent.getBroadcast(this, 1, answerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val declineIntent = Intent(this, CallActionReceiver::class.java).apply { action = "DECLINE_CALL" }
        val declinePendingIntent = PendingIntent.getBroadcast(this, 2, declineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

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
        try { startForeground(serviceId, notification) } catch (_: Exception) {}
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
            player.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED).build())
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
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC,
            audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
    }

    private var ringtonePlayer: MediaPlayer? = null

    private fun stopRingtone() {
        ringtonePlayer?.apply { stop(); release() }
        ringtonePlayer = null
    }

    private fun playAudio(data: ByteArray) {
        try {
            val filePath = audioStorage.saveAudio(data, "received")
            if (currentCallId >= 0) {
                repo.addAudioMessage(currentCallId, "received", filePath)
            }

            stopPlayback()
            MediaPlayer().apply {
                setDataSource(filePath)
                setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                setOnCompletionListener { release(); if (audioPlayer === this) audioPlayer = null }
                setOnErrorListener { _, _, _ -> false }
                prepare()
                start()
                audioPlayer = this
            }
        } catch (e: Exception) {
            android.util.Log.e("CallService", "playAudio error: ${e.message}", e)
        }
    }

    private fun stopPlayback() {
        try { audioPlayer?.apply { if (isPlaying) stop(); release() } } catch (_: Exception) {}
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
                @Suppress("DEPRECATION") MediaRecorder()
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
            mediaRecorder?.apply { try { stop() } catch (_: Exception) {}; release() }
            mediaRecorder = null
            val file = recordingFile
            recordingFile = null
            if (file != null && file.exists() && file.length() > 0) {
                val data = file.readBytes()
                sendAudio(data)
                if (currentCallId >= 0) {
                    repo.addAudioMessage(currentCallId, "sent", file.absolutePath, file.length())
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("CallService", "stopAndSendRecording error: ${e.message}", e)
        }
    }

    private fun sendAudio(data: ByteArray) {
        scope.launch {
            try {
                val requestBody = data.toRequestBody("application/octet-stream".toMediaTypeOrNull())
                val request = Request.Builder().url("$SERVER_URL/callee").post(requestBody).build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    android.util.Log.w("CallService", "POST /callee failed: ${response.code}")
                }
                response.close()
            } catch (e: Exception) {
                android.util.Log.e("CallService", "sendAudio error: ${e.message}", e)
            }
        }
    }

    private fun hangup() {
        scope.launch {
            try {
                val request = Request.Builder().url("$SERVER_URL/callee").delete().build()
                val response = client.newCall(request).execute()
                response.close()
            } catch (_: Exception) {}
        }
    }

    private fun answerCall() {
        scope.launch {
            try {
                val emptyBody = "".toRequestBody(null)
                val request = Request.Builder().url("$SERVER_URL/callee").post(emptyBody).build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    android.util.Log.w("CallService", "POST /callee (answer) failed: ${response.code}")
                }
                response.close()
            } catch (e: Exception) {
                android.util.Log.e("CallService", "answerCall error: ${e.message}", e)
            }
        }
    }

    private fun stopRecording() {
        recording = false
        try { mediaRecorder?.apply { try { stop() } catch (_: Exception) {}; release() } } catch (_: Exception) {}
        mediaRecorder = null
        recordingFile?.delete()
        recordingFile = null
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
