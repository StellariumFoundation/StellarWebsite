package jv.stellariumcaller.stellariumcaller.ui.screens

import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jv.stellariumcaller.stellariumcaller.CallAudioMessage
import jv.stellariumcaller.stellariumcaller.CallLogRepository
import jv.stellariumcaller.stellariumcaller.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun CallDetailScreen(
    callId: Long,
    repo: CallLogRepository,
    onBack: () -> Unit
) {
    val log = remember(callId) { repo.getCallLog(callId) }
    var playingIndex by remember { mutableIntStateOf(-1) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            player?.release()
        }
    }

    fun playAudio(msg: CallAudioMessage, index: Int) {
        player?.release()
        try {
            MediaPlayer().apply {
                setDataSource(msg.filePath)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setOnCompletionListener {
                    playingIndex = -1
                    release()
                    player = null
                }
                setOnErrorListener { _, _, _ ->
                    playingIndex = -1
                    false
                }
                prepare()
                start()
                player = this
                playingIndex = index
            }
        } catch (_: Exception) { }
    }

    Column(modifier = Modifier.fillMaxSize().background(DarkBackground)) {
        // Header
        Box(
            modifier = Modifier.fillMaxWidth().background(DarkSurface).padding(12.dp)
        ) {
            if (log != null) {
                val dateFormat = remember { SimpleDateFormat("MMM dd, hh:mm a", Locale.getDefault()) }
                val status = when (log.status) {
                    "answered" -> "Answered"
                    "missed" -> "Missed"
                    else -> log.status
                }
                Text(
                    "Call $status - ${dateFormat.format(Date(log.timestamp))}",
                    color = White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            } else {
                Text("Call not found", color = White, fontSize = 16.sp)
            }
        }

        if (log == null || log.audioMessages.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("No audio messages in this call", color = GrayDark, fontSize = 14.sp)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(log.audioMessages.withIndex().toList()) { (index, msg) ->
                    AudioMessageBubble(
                        msg = msg,
                        isPlaying = index == playingIndex,
                        onPlay = { playAudio(msg, index) }
                    )
                }
            }
        }
    }
}

@Composable
private fun AudioMessageBubble(
    msg: CallAudioMessage,
    isPlaying: Boolean,
    onPlay: () -> Unit
) {
    val timeFormat = remember { SimpleDateFormat("hh:mm a", Locale.getDefault()) }
    val isSent = msg.direction == "sent"
    val bubbleColor = if (isSent) EmeraldDark else BlueDark
    val alignment = if (isSent) Alignment.End else Alignment.Start

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isSent) Alignment.End else Alignment.Start
    ) {
        Row(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(bubbleColor)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onPlay,
                modifier = Modifier.size(36.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                contentPadding = PaddingValues(0.dp)
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Playing" else "Play",
                    tint = White
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                if (isPlaying) "Playing..." else "Tap to play",
                color = White,
                fontSize = 13.sp
            )
            Spacer(Modifier.width(8.dp))
            Text(
                timeFormat.format(Date(msg.timestamp)),
                color = GrayMedium,
                fontSize = 11.sp
            )
        }
    }
}
