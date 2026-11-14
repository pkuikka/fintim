package com.example.fintim.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.fintim.R
import com.example.fintim.data.MAX_DATA_SETS
import com.example.fintim.data.TimerData
import java.util.Locale


fun modelTypeAsString(modelType: Int): String {
    when (modelType) {
        1 -> return "F1Q"
        2 -> return "F1B"
    }
    return "Unknown"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    uiState: UiState,
    onUpdateTimerData: (TimerData.() -> TimerData) -> Unit,
    onCopyClick: (sourceSet: Int, destinationSet: Int) -> Unit,
    onUpdateS2Divisor: (Double) -> Unit
) {
    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(dimensionResource(R.dimen.padding_small)),
    ) {
        RowWithText("Timer version ${uiState.timerData.timerVersion}")
        RowWithText("Model type ${modelTypeAsString(uiState.timerData.modelType)}")
        RowWithField(
            "Number of data lines",
            "${uiState.timerData.numberOfDataRows}",
            onDoneAction = { newValue ->
                onUpdateTimerData { copy(numberOfDataRows = newValue.toIntOrNull() ?: 0) }
            }
        )
        RowWithText("Maximum data lines ${uiState.timerData.maxDataRows}")

        // Battery type selection
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            var isExpanded by remember { mutableStateOf(false) }

            Text(
                text = "Battery type",
                style = typography.bodyMedium
            )

            ExposedDropdownMenuBox(
                expanded = isExpanded,
                onExpandedChange = { isExpanded = it },
                modifier = Modifier.width(90.dp)
            ) {
                OutlinedTextField(
                    value = uiState.timerData.batteryType,
                    onValueChange = {},
                    readOnly = true,
                    textStyle = typography.bodySmall,
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = isExpanded)
                    },
                    singleLine = true,
                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                    modifier = Modifier
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        .height(48.dp)
                )

                ExposedDropdownMenu(
                    expanded = isExpanded,
                    onDismissRequest = { isExpanded = false }
                ) {
                    listOf("S2", "S3").forEach { option ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = option,
                                    style = typography.bodySmall
                                )
                            },
                            onClick = {
                                onUpdateTimerData { copy(batteryType = option) }
                                isExpanded = false
                            }
                        )
                    }
                }
            }
        }

        // Battery type selection
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {

            //Temporary S2 voltage divisor
            if (uiState.timerData.batteryType == "S2") {
                Text(
                    text = "S2 voltage divisor",
                    style = typography.bodyMedium
                )
                CommonField(
                    value = uiState.tempS2BatteryVoltageDivisor.toString(),
                    width = 100.dp,
                    onDoneAction = { newValue ->
                        newValue.toDoubleOrNull()?.let { onUpdateS2Divisor(it) }
                    }
                )
                Text(
                    text = "gives ${String.format(Locale.US, "%.3f", uiState.timerData.batteryVoltage / uiState.tempS2BatteryVoltageDivisor)}V",
                    style = typography.bodyMedium
                )
            }
        }

        // --- Data set management settings ---
        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
        Text(
            text = "Data set management",
            style = typography.titleMedium
        )
        Spacer(modifier = Modifier.height(dimensionResource(R.dimen.padding_small)))

        // --- Copy 1 set to another
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            var isSetExpanded by remember { mutableStateOf(false) }
            var destinationSet by remember { mutableStateOf<Int?>(null) }

            Text(
                modifier = Modifier.width(185.dp),
                text = "Current set ${uiState.timerData.modelSet} ${uiState.timerData.setNames[uiState.timerData.modelSet]} -->",
                style = typography.bodyMedium
            )

            ExposedDropdownMenuBox(
                expanded = isSetExpanded,
                onExpandedChange = { isSetExpanded = it },
                modifier = Modifier.width(90.dp) // Give it a specific width
            ) {
                // This is the TextField part of the dropdown
                OutlinedTextField(
                    value = destinationSet?.toString() ?: "", // Show selected value or empty
                    onValueChange = {}, // onValueChange is not needed for a read-only dropdown
                    readOnly = true,
                    textStyle = typography.bodySmall,
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = isSetExpanded)
                    },
                    singleLine = true,
                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                    modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable)
                )

                // This is the actual dropdown menu with the list of items
                ExposedDropdownMenu(
                    expanded = isSetExpanded,
                    onDismissRequest = { isSetExpanded = false }
                ) {
                    (0 until MAX_DATA_SETS).forEach { selectionIndex ->
                        if (selectionIndex == uiState.timerData.modelSet) {
                            // skip current
                        } else {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "$selectionIndex ${uiState.timerData.setNames[selectionIndex]}",
                                        style = typography.bodySmall
                                    )
                                },
                                onClick = {
                                    destinationSet = selectionIndex
                                    isSetExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            // Copy Button
            Button(
                onClick = {
                    val currentSet = uiState.timerData.modelSet
                    val dest = destinationSet
                    // 4. Pass both source and destination to the onCopyClick lambda
                    if (dest != null) {
                        onCopyClick(currentSet, dest)
                    }
                },
                // Enable the button only when a destination has been selected
                enabled = destinationSet != null
            ) {
                Text(text = stringResource(R.string.button_copy), fontSize = 16.sp)
            }
        }

        // --- Apply set names
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            var isSetExpanded by remember { mutableStateOf(false) }
            var nameDestinationSet by remember { mutableIntStateOf(0) }

            Text(
                text = "Set",
                style = typography.bodyMedium
            )

            ExposedDropdownMenuBox(
                expanded = isSetExpanded,
                onExpandedChange = { isSetExpanded = it },
                modifier = Modifier.width(90.dp) // Give it a specific width
            ) {
                // This is the TextField part of the dropdown
                OutlinedTextField(
                    value = nameDestinationSet.toString(),
                    onValueChange = {}, // onValueChange is not needed for a read-only dropdown
                    readOnly = true,
                    textStyle = typography.bodySmall,
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = isSetExpanded)
                    },
                    singleLine = true,
                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                    modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable)
                )

                // This is the actual dropdown menu with the list of items
                ExposedDropdownMenu(
                    expanded = isSetExpanded,
                    onDismissRequest = { isSetExpanded = false }
                ) {
                    (0 until MAX_DATA_SETS).forEach { selectionIndex ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "$selectionIndex ${uiState.timerData.setNames[selectionIndex]}",
                                    style = typography.bodySmall
                                )
                            },
                            onClick = {
                                nameDestinationSet = selectionIndex
                                isSetExpanded = false
                            }
                        )
                    }
                }
            }
            Text(
                text = "name to",
                style = typography.bodyMedium
            )
            CommonField(
                value = uiState.timerData.setNames[nameDestinationSet],
                onDoneAction = { newValue ->
                    onUpdateTimerData {
                        // Create a mutable copy of the list to update the specific index
                        val updatedList = setNames.toMutableList()
                        if (nameDestinationSet in updatedList.indices) {
                            updatedList[nameDestinationSet] = newValue
                        }
                        copy(setNames = updatedList)
                    }
                },
                keyboardType = KeyboardType.Text,
                width = 150.dp
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun SettingsScreenPreview() {
    // Call the stateless composable with fake data and empty lambdas
    val uiState = UiState(
        timerData = TimerData(
            modelName = "Test Model",
        )
    )
    SettingsScreen(
        uiState = uiState,
        onUpdateTimerData = { _ -> },
        onCopyClick = { _, _ -> },
        onUpdateS2Divisor = {}
    )
}
