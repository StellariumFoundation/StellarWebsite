package jv.stellariumcaller.stellariumcaller.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jv.stellariumcaller.stellariumcaller.ui.theme.*

@Composable
fun ActiveCallScreen(
    pttPressed: Boolean,
    isRecording: Boolean,
    onPttDown: () -> Unit,
    onPttUp: () -> Unit,
    onEndCall: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(0.3f))

        Text(
            "John Victor",
            color = White,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(12.dp))

        Text(
            "Connected",
            color = Emerald,
            fontSize = 18.sp
        )

        Spacer(Modifier.weight(0.5f))

        Row(
            horizontalArrangement = Arrangement.spacedBy(60.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // PTT Button
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Button(
                    onClick = {},
                    modifier = Modifier
                        .size(96.dp)
                        .then(
                            if (isRecording) Modifier.border(3.dp, EmeraldLight, CircleShape) else Modifier
                        )
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (event.type == PointerEventType.Press) onPttDown() else onPttUp()
                                }
                            }
                        },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRecording) Emerald else EmeraldDark
                    ),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(
                        Icons.Default.Mic,
                        contentDescription = "Push to Talk",
                        tint = White,
                        modifier = Modifier.size(40.dp)
                    )
                }
                Text(
                    if (isRecording) "RECORDING" else if (pttPressed) "INITIALIZING..." else "Hold to Talk",
                    color = if (isRecording) EmeraldLight else GrayLight,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            // End Call Button
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Button(
                    onClick = onEndCall,
                    modifier = Modifier.size(72.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF4444)),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(
                        Icons.Default.CallEnd,
                        contentDescription = "End Call",
                        tint = White,
                        modifier = Modifier.size(32.dp)
                    )
                }
                Text(
                    "End",
                    color = White,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        Spacer(Modifier.weight(0.3f))
    }
}
