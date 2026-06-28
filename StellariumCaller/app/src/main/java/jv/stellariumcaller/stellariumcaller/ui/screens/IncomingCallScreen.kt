package jv.stellariumcaller.stellariumcaller.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jv.stellariumcaller.stellariumcaller.ui.theme.*

@Composable
fun IncomingCallScreen(
    onAnswer: () -> Unit,
    onDecline: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(1f))

        Text(
            "Incoming Call",
            color = White,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(8.dp))

        Text(
            "Stellarium Foundation",
            color = EmeraldLight,
            fontSize = 18.sp
        )

        Spacer(Modifier.weight(1f))

        Row(
            horizontalArrangement = Arrangement.spacedBy(48.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onDecline,
                modifier = Modifier.size(72.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF4444)),
                contentPadding = PaddingValues(0.dp)
            ) {
                Icon(Icons.Default.CallEnd, contentDescription = "Decline", tint = White)
            }

            Button(
                onClick = onAnswer,
                modifier = Modifier.size(72.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Emerald),
                contentPadding = PaddingValues(0.dp)
            ) {
                Icon(Icons.Default.Call, contentDescription = "Answer", tint = White)
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}
