package jv.stellariumcaller.stellariumcaller

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import jv.stellariumcaller.stellariumcaller.ui.screens.IncomingCallScreen
import jv.stellariumcaller.stellariumcaller.ui.theme.StellariumCallerTheme

class IncomingCallActivity : ComponentActivity() {
    private var pendingAnswer = false
    private var callHandled = false

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted && pendingAnswer) {
            proceedAnswer()
        } else if (pendingAnswer) {
            Toast.makeText(this, "Microphone permission required for calls", Toast.LENGTH_SHORT).show()
            CallService.sendEndCall()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setTurnScreenOn(true)
            setShowWhenLocked(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            StellariumCallerTheme {
                IncomingCallScreen(
                    onAnswer = {
                        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                            == PackageManager.PERMISSION_GRANTED
                        ) {
                            proceedAnswer()
                        } else {
                            pendingAnswer = true
                            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onDecline = {
                        pendingAnswer = false
                        callHandled = true
                        CallService.sendEndCall()
                        finish()
                    }
                )
            }
        }
    }

    private fun proceedAnswer() {
        pendingAnswer = false
        callHandled = true
        CallService.sendAnswer()
        val intent = Intent(this, ActiveCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(intent)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!callHandled) {
            CallService.sendEndCall()
        }
    }
}
