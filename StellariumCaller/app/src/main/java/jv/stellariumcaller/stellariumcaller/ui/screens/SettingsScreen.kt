package jv.stellariumcaller.stellariumcaller.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import jv.stellariumcaller.stellariumcaller.AudioStorage
import jv.stellariumcaller.stellariumcaller.CallLogRepository
import jv.stellariumcaller.stellariumcaller.CallService
import jv.stellariumcaller.stellariumcaller.ui.theme.*
import java.text.DecimalFormat

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val audioStorage = remember { AudioStorage(context) }
    val repo = remember { CallLogRepository.getInstance(context) }

    val isConnected by CallService.connectionFlow.collectAsState()
    var cacheInfo by remember { mutableStateOf("") }

    fun refreshCacheInfo() {
        val fmt = DecimalFormat("#.##")
        val count = audioStorage.getFileCount()
        val size = audioStorage.getTotalSize()
        cacheInfo = when {
            size < 1024 -> "Audio cache: $count files, $size B"
            size < 1024 * 1024 -> "Audio cache: $count files, ${fmt.format(size / 1024.0)} KB"
            else -> "Audio cache: $count files, ${fmt.format(size / (1024.0 * 1024.0))} MB"
        }
    }

    LaunchedEffect(Unit) { refreshCacheInfo() }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Toast.makeText(context, "Notification permission granted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Notification permission denied", Toast.LENGTH_LONG).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Text("Call Service", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)

        Text(
            if (isConnected) "Status: Connected" else "Status: Disconnected",
            color = GrayMedium,
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 12.dp)
        )

        Button(
            onClick = {
                if (isConnected) {
                    context.stopService(Intent(context, CallService::class.java))
                } else {
                    val intent = Intent(context, CallService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = DarkSurface)
        ) {
            Text(
                if (isConnected) "Stop Call Service" else "Start Call Service",
                color = White,
                fontWeight = FontWeight.Bold
            )
        }

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 24.dp),
            color = androidx.compose.ui.graphics.Color(0xFF333333)
        )

        Text("Permissions", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)

        Button(
            onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED
                    ) {
                        notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        Toast.makeText(context, "Notification permission already granted", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(context, "Notification permission is automatic on your version", Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = DarkSurface)
        ) {
            Text("Grant Notification Permission", color = White, fontWeight = FontWeight.Bold)
        }

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 24.dp),
            color = androidx.compose.ui.graphics.Color(0xFF333333)
        )

        Text("Storage", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)

        Text(
            cacheInfo,
            color = GrayMedium,
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 12.dp)
        )

        Button(
            onClick = {
                audioStorage.deleteAll()
                repo.clearAll()
                Toast.makeText(context, "Audio cache and call logs cleared", Toast.LENGTH_SHORT).show()
                refreshCacheInfo()
            },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color(0xFF3D0000))
        ) {
            Text("Clear Audio Cache", color = Red, fontWeight = FontWeight.Bold)
        }
    }
}
