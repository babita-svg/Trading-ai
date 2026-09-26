package com.tradinghud.app.overlay.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.tradinghud.app.overlay.OverlayState

@Composable
fun VerdictPanel(
    verdict: OverlayState.Verdict,
    onLogResult: (isWin: Boolean, amountInr: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var showLog by remember { mutableStateOf(false) }
    var logAmount by remember { mutableStateOf("") }
    val signalColor = if (verdict.verdict.equals("BUY")) Color(0xFF2E7D32) else Color(0xFFC62828)

    Card(modifier = Modifier.width(300.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = verdict.proposal.signal.name,
                style = MaterialTheme.typography.headlineMedium,
                color = signalColor,
            )
            Divider()
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Column {
                    Text("Entry", style = MaterialTheme.typography.labelSmall)
                    Text("${verdict.proposal.entry}")
                }
                Column {
                    Text("Stop", style = MaterialTheme.typography.labelSmall)
                    Text("${verdict.proposal.stopLoss}")
                }
                Column {
                    Text("Target", style = MaterialTheme.typography.labelSmall)
                    Text("${verdict.proposal.takeProfit}")
                }
            }
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Column {
                    Text("Qty", style = MaterialTheme.typography.labelSmall)
                    Text("${verdict.verdict.quantity}")
                }
                Column {
                    Text("Risk (₹)", style = MaterialTheme.typography.labelSmall)
                    Text("${verdict.verdict.riskInr}")
                }
                Column {
                    Text("R:R", style = MaterialTheme.typography.labelSmall)
                    Text("${verdict.verdict.rewardToRisk}")
                }
            }
            Text(verdict.rationale, style = MaterialTheme.typography.bodySmall)
            Divider()

            if (!showLog) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Dismiss") }
                    Button(onClick = { showLog = true }, modifier = Modifier.weight(1f)) { Text("Log result") }
                }
            } else {
                Text("Result", style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(
                    value = logAmount,
                    onValueChange = { logAmount = it },
                    label = { Text("P&L (₹)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onLogResult(false, logAmount) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFC62828)),
                    ) { Text("Loss") }
                    Button(
                        onClick = { onLogResult(true, logAmount) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                    ) { Text("Win") }
                }
            }
        }
    }
}
