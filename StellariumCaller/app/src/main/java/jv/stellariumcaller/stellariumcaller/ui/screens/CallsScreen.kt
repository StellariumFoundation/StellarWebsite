package jv.stellariumcaller.stellariumcaller.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jv.stellariumcaller.stellariumcaller.CallLog
import jv.stellariumcaller.stellariumcaller.CallLogRepository
import jv.stellariumcaller.stellariumcaller.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun CallsScreen(
    repo: CallLogRepository,
    onCallClick: (Long) -> Unit
) {
    val logs by repo.callLogsFlow.collectAsState()

    if (logs.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize().background(DarkBackground),
            contentAlignment = Alignment.Center
        ) {
            Text("No calls yet", color = GrayDark, fontSize = 16.sp)
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize().background(DarkBackground).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(logs) { log ->
                CallLogItem(log = log, onClick = { onCallClick(log.id) })
            }
        }
    }
}

@Composable
private fun CallLogItem(log: CallLog, onClick: () -> Unit) {
    val dateFormat = remember { SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()) }
    val timeFormat = remember { SimpleDateFormat("hh:mm a", Locale.getDefault()) }
    val date = remember(log.timestamp) { Date(log.timestamp) }

    val statusColor = when (log.status) {
        "answered" -> Emerald
        "missed" -> Red
        else -> GrayMedium
    }
    val statusLabel = when (log.status) {
        "answered" -> "Answered"
        "missed" -> "Missed"
        else -> log.status
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkSurface)
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(statusLabel, color = statusColor, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(timeFormat.format(date), color = GrayMedium, fontSize = 12.sp)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(dateFormat.format(date), color = GrayLight, fontSize = 14.sp)
            Text("${log.audioMessages.size} audio message(s)", color = GrayDark, fontSize = 12.sp)
        }
    }
}
