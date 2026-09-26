package com.tradinghud.app.overlay.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InputPanel(
    onConfirm: (capital: String, marketType: String) -> Unit,
    onCancel: () -> Unit,
) {
    var capital by remember { mutableStateOf("") }
    var market by remember { mutableStateOf("INDIAN_EQUITY") }
    var expanded by remember { mutableStateOf(false) }
    val markets = listOf("INDIAN_EQUITY", "INDIAN_FNO", "CRYPTO")

    Card(modifier = Modifier.width(280.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("AI Trading HUD", style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = capital,
                onValueChange = { capital = it },
                label = { Text("Capital (INR)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded },
            ) {
                OutlinedTextField(
                    value = market,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Market Type") },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    markets.forEach { m ->
                        DropdownMenuItem(
                            text = { Text(m) },
                            onClick = {
                                market = m
                                expanded = false
                            }
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(
                    onClick = { onConfirm(capital, market) },
                    enabled = capital.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) { Text("Analyze") }
            }
        }
    }
}
