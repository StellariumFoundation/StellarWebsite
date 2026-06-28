package jv.stellariumcaller.stellariumcaller

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import jv.stellariumcaller.stellariumcaller.ui.screens.ActiveCallScreen
import jv.stellariumcaller.stellariumcaller.ui.theme.DarkBackground
import jv.stellariumcaller.stellariumcaller.ui.theme.StellariumCallerTheme

class ActiveCallActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        if (!CallService.isCallActive()) {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            StellariumCallerTheme {
                var pttPressed by remember { mutableStateOf(false) }
                var isRecording by remember { mutableStateOf(false) }
                val audioManager = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
                audioManager.setStreamVolume(android.media.AudioManager.STREAM_VOICE_CALL, audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_VOICE_CALL), 0)

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(DarkBackground)
                ) {
                    ActiveCallScreen(
                        pttPressed = pttPressed,
                        isRecording = isRecording,
                        onPttDown = {
                            pttPressed = true
                            CallService.startRecordingPTT()
                            isRecording = true
                        },
                        onPttUp = {
                            pttPressed = false
                            isRecording = false
                            CallService.stopRecordingPTT()
                        },
                        onEndCall = {
                            CallService.sendEndCall()
                            finish()
                        }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        if (CallService.isCallActive()) {
            CallService.sendEndCall()
        }
        super.onDestroy()
    }
}
