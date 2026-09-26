package com.tradinghud.app.overlay.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun BlockedPanel(reason: String, onDismiss: () -> Unit) {
    Card(modifier = Modifier.width(300.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "🛑 TRADE REJECTED",
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFFC62828),
            )
            Text(text = reason, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("Dismiss")
            }
        }
    }
}
