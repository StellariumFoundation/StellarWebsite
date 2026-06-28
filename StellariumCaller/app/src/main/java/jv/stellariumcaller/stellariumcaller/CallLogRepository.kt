package jv.stellariumcaller.stellariumcaller

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class CallAudioMessage(
    val id: Long,
    val direction: String,
    val filePath: String,
    val timestamp: Long,
    val durationMs: Long = 0
)

data class CallLog(
    val id: Long,
    val timestamp: Long,
    val status: String,
    val audioMessages: MutableList<CallAudioMessage> = mutableListOf()
)

class CallLogRepository private constructor(context: Context) {
    private val dataFile = File(context.filesDir, "stellarium_data/call_logs.json")
    private var callLogs = mutableListOf<CallLog>()
    private var nextCallId = 1L
    private var nextMsgId = 1L
    private val _callLogsFlow = MutableStateFlow<List<CallLog>>(emptyList())

    init {
        dataFile.parentFile?.mkdirs()
        load()
        _callLogsFlow.value = callLogs.toList().reversed()
    }

    val callLogsFlow: StateFlow<List<CallLog>> = _callLogsFlow.asStateFlow()

    fun getCallLogs(): List<CallLog> = callLogs.reversed()

    fun getCallLog(id: Long): CallLog? = callLogs.find { it.id == id }

    fun createCallLog(status: String): CallLog {
        val log = CallLog(
            id = nextCallId++,
            timestamp = System.currentTimeMillis(),
            status = status
        )
        callLogs.add(log)
        save()
        _callLogsFlow.value = callLogs.toList().reversed()
        return log
    }

    fun updateCallStatus(callId: Long, status: String) {
        callLogs.find { it.id == callId }?.let {
            val idx = callLogs.indexOf(it)
            callLogs[idx] = it.copy(status = status)
            save()
            _callLogsFlow.value = callLogs.toList().reversed()
        }
    }

    fun addAudioMessage(
        callId: Long,
        direction: String,
        filePath: String,
        durationMs: Long = 0
    ): CallAudioMessage? {
        val log = callLogs.find { it.id == callId } ?: return null
        val msg = CallAudioMessage(
            id = nextMsgId++,
            direction = direction,
            filePath = filePath,
            timestamp = System.currentTimeMillis(),
            durationMs = durationMs
        )
        log.audioMessages.add(msg)
        save()
        _callLogsFlow.value = callLogs.toList().reversed()
        return msg
    }

    fun clearAll() {
        callLogs.clear()
        nextCallId = 1
        nextMsgId = 1
        save()
        _callLogsFlow.value = emptyList()
    }

    private fun load() {
        if (!dataFile.exists()) return
        try {
            val json = JSONArray(dataFile.readText())
            for (i in 0 until json.length()) {
                val obj = json.getJSONObject(i)
                val log = CallLog(
                    id = obj.getLong("id"),
                    timestamp = obj.getLong("timestamp"),
                    status = obj.getString("status"),
                    audioMessages = mutableListOf()
                )
                if (obj.has("audioMessages")) {
                    val msgs = obj.getJSONArray("audioMessages")
                    for (j in 0 until msgs.length()) {
                        val m = msgs.getJSONObject(j)
                        log.audioMessages.add(CallAudioMessage(
                            id = m.getLong("id"),
                            direction = m.getString("direction"),
                            filePath = m.getString("filePath"),
                            timestamp = m.getLong("timestamp"),
                            durationMs = m.optLong("durationMs", 0)
                        ))
                    }
                }
                callLogs.add(log)
                if (log.id >= nextCallId) nextCallId = log.id + 1
                log.audioMessages.forEach { if (it.id >= nextMsgId) nextMsgId = it.id + 1 }
            }
        } catch (_: Exception) { }
    }

    private fun save() {
        try {
            val arr = JSONArray()
            callLogs.forEach { log ->
                val obj = JSONObject()
                obj.put("id", log.id)
                obj.put("timestamp", log.timestamp)
                obj.put("status", log.status)
                val msgs = JSONArray()
                log.audioMessages.forEach { m ->
                    val mo = JSONObject()
                    mo.put("id", m.id)
                    mo.put("direction", m.direction)
                    mo.put("filePath", m.filePath)
                    mo.put("timestamp", m.timestamp)
                    mo.put("durationMs", m.durationMs)
                    msgs.put(mo)
                }
                obj.put("audioMessages", msgs)
                arr.put(obj)
            }
            dataFile.writeText(arr.toString(2))
        } catch (_: Exception) { }
    }

    companion object {
        @Volatile private var instance: CallLogRepository? = null

        fun getInstance(context: Context): CallLogRepository {
            return instance ?: synchronized(this) {
                instance ?: CallLogRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
