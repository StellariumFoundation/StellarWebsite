package jv.stellariumcaller.stellariumcaller

import android.content.Context
import java.io.File
import java.util.UUID

class AudioStorage(private val context: Context) {
    private val audioDir = File(context.filesDir, "stellarium_audio")

    init {
        audioDir.mkdirs()
    }

    fun saveAudio(data: ByteArray, prefix: String): String {
        val fileName = "${prefix}_${System.currentTimeMillis()}_${UUID.randomUUID()}.webm"
        val file = File(audioDir, fileName)
        file.writeBytes(data)
        return file.absolutePath
    }

    fun getFile(path: String): File = File(path)

    fun deleteAll(): Long {
        var total = 0L
        audioDir.listFiles()?.forEach {
            total += it.length()
            it.delete()
        }
        return total
    }

    fun getTotalSize(): Long {
        return audioDir.listFiles()?.sumOf { it.length() } ?: 0
    }

    fun getFileCount(): Int {
        return audioDir.listFiles()?.size ?: 0
    }

    fun getAudioDir(): File = audioDir
}
