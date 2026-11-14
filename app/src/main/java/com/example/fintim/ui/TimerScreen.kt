package com.example.fintim.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimerScreen(
    uiState: UiState,
    onGridItemChanged: (Int, String) -> Unit,
    onRowSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val displayGridItems = remember(uiState.timerData) {
        fun stepLabel(index: Int): String {
            var label = ""
            when (uiState.timerData.stepValues[index]) {
                0 -> label = "S"
                1 -> label = "R"
                2 -> label = "D"
                3 -> label = "A"
            }
            return label
        }

        val columnHeaders = listOf(
            "",
            "Time",
            uiState.timerData.servo1Label,
            uiState.timerData.servo2Label,
            uiState.timerData.servo3Label,
            uiState.timerData.servo4Label
        )

        val rowHeaders = listOf(
            uiState.timerData.row1Label,
            uiState.timerData.row2Label,
            "1   " + stepLabel(0),
            "2   " + stepLabel(1),
            "3   " + stepLabel(2),
            "4   " + stepLabel(3),
            "5   " + stepLabel(4),
            "6   " + stepLabel(5),
            "7   " + stepLabel(6),
            "8   " + stepLabel(7),
            "9   " + stepLabel(8),
            "10  " + stepLabel(9),
            "11  " + stepLabel(10),
            "12  " + stepLabel(11),
            "13  " + stepLabel(12),
            "14  " + stepLabel(13),
            "15  " + stepLabel(14),
            "16  " + stepLabel(15),
        )

        // The actual numerical data from the device
        val data = mutableListOf<String>()

        // standby rivi
        data.add("") // aika sarake
        data.add(uiState.timerData.servoStandByValues[0].toString())
        data.add(uiState.timerData.servoStandByValues[1].toString())
        data.add(uiState.timerData.servoStandByValues[2].toString())
        data.add(uiState.timerData.servoStandByValues[3].toString())

        // start rivi
        data.add("") // aika sarake
        data.add(uiState.timerData.servoStartValues[0].toString())
        data.add(uiState.timerData.servoStartValues[1].toString())
        data.add(uiState.timerData.servoStartValues[2].toString())
        data.add(uiState.timerData.servoStartValues[3].toString())

        // aika rivit * 16
        for (i in 0 until (uiState.timerData.numberOfDataRows)) {
            val timeValue = if (uiState.timerData.timeValues[i] < com.example.fintim.data.MAX_TIME_TENTHS_LIMIT)
                String.format(Locale.US, "%.2f", uiState.timerData.timeValues[i])
            else
                uiState.timerData.timeValues[i].toInt().toString()
            
            data.add(timeValue)
            data.add(uiState.timerData.servo1Values[i].toString())
            data.add(uiState.timerData.servo2Values[i].toString())
            data.add(uiState.timerData.servo3Values[i].toString())
            data.add(uiState.timerData.servo4Values[i].toString())
        }

        // We will now build the final display list (90 items)
        val combinedList = mutableListOf<String>()
        combinedList.addAll(columnHeaders) // Add the first row (headers)

        // For the remaining 2 + 16 rows...
        for (i in 0 until (2 + uiState.timerData.numberOfDataRows)) {
            // Add the row header for this row
            if (i < rowHeaders.size) {
                combinedList.add(rowHeaders[i])
            } else {
                combinedList.add("") // Fallback if not enough headers
            }
            val dataStartIndex = i * 5
            combinedList.addAll(data.subList(dataStartIndex, dataStartIndex + 5))
        }
        combinedList
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // -------- Status Row --------
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = " ", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "Timer voltage ${String.format(Locale.US, "%.2f", uiState.timerData.batteryVoltage)}V",
                style = MaterialTheme.typography.titleSmall
            )
        }
        // -------- Grid --------
        LazyColumn(
            modifier = Modifier.fillMaxWidth()
        ) {
            val rowCount = displayGridItems.size / 6
            items(rowCount) { rowIndex ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    for (colIndex in 0 until 6) {
                        val index = rowIndex * 6 + colIndex
                        val cellModifier = when (colIndex) {
                            0 -> Modifier.width(80.dp) // Wider column 1
                            1 -> Modifier.width(75.dp) // Wider column 2
                            else -> Modifier.weight(1f) // Remaining columns share space
                        }

                        TimerGridCell(
                            index = index,
                            rowIndex = rowIndex,
                            initialText = displayGridItems[index],
                            onGridItemChanged = onGridItemChanged,
                            onRowSelected = onRowSelected, // Pass the callback here
                            modifier = cellModifier
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun StepSelectionDialog(
    rowIndex: Int,
    onDismiss: () -> Unit,
    onValueSelected: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Select step type for row $rowIndex") },
        text = {
            Column {
                listOf("S", "R", "D", "A").forEach { option ->
                    TextButton(
                        onClick = { onValueSelected(option) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = option)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun TimerGridCell(
    index: Int,
    rowIndex: Int,
    initialText: String,
    onGridItemChanged: (Int, String) -> Unit,
    onRowSelected: (Int) -> Unit, // Add this callback parameter
    modifier: Modifier = Modifier
) {
    var text by remember(initialText) {
        mutableStateOf(initialText)
    }
    val focusManager = LocalFocusManager.current
    var isError by remember { mutableStateOf(false) }
    val colIndex = index % 6
    val isStepCell = colIndex == 0 && rowIndex >= 3
    var showPopup by remember { mutableStateOf(false) }
    var clickedOnce by remember { mutableStateOf(false) }
    // bug Samsung A55/A52
    var hadFocus by remember { mutableStateOf(false) } // Track if the cell had focus

    fun dismissDialog() {
        showPopup = false
        clickedOnce = false
    }

    fun handleStepCellClick() {
        // Trigger the callback to update the ViewModel's state
        onRowSelected(rowIndex)

        if (clickedOnce) {
            showPopup = true
        } else {
            clickedOnce = true
        }
    }

    fun resetClickState() {
        clickedOnce = false
    }

    if (showPopup) {
        StepSelectionDialog(
            rowIndex = rowIndex - 2,
            onDismiss = { dismissDialog() },
            onValueSelected = { newValue ->
                onGridItemChanged(index, newValue)
                dismissDialog()
            }
        )
    }

    Box(modifier = modifier) {
        val maxLength: Int = if (colIndex == 1) 5 else 3
        val minValue = 0
        val maxValue: Int = if (colIndex == 1) 9999 else 999

        OutlinedTextField(
            modifier = Modifier
                .fillMaxWidth()
                .padding(1.dp)
                .height(48.dp)
                .onFocusChanged { focusState ->
                    if (focusState.isFocused) {
                        hadFocus = true
                    } else if (hadFocus) {
                        val sanitizedText = if (colIndex == 1) text.replace(',', '.') else text
                        text = sanitizedText
                        // Only trigger update if it was previously focused and now lost it
                        if (sanitizedText != initialText) {
                            onGridItemChanged(index, sanitizedText)
                        }
                        if (isStepCell) resetClickState()
                        hadFocus = false
                    }
                },
            value = text,
            onValueChange = { newText ->
                if (newText.length <= maxLength) {
                    val sanitizedText = if (colIndex == 1) newText.replace(',', '.') else newText
                    text = newText
                    if (sanitizedText.isNotEmpty()) {
                        if (colIndex == 1) {
                            val doubleVal = sanitizedText.toDoubleOrNull()
                            isError = doubleVal == null || doubleVal < minValue || doubleVal > maxValue
                        } else {
                            val intVal = sanitizedText.toIntOrNull()
                            isError = intVal == null || intVal < minValue || intVal > maxValue
                        }
                    } else {
                        isError = false
                    }
                }
            },
            isError = isError,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
            readOnly = (index < 6) || (colIndex == 0),
            keyboardOptions = KeyboardOptions.Default.copy(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    val sanitizedText = if (colIndex == 1) text.replace(',', '.') else text
                    text = sanitizedText
                    if (!isError) {
                        onGridItemChanged(index, sanitizedText)
                    } else {
                        text = initialText
                    }
                    focusManager.clearFocus()
                }
            )
        )

        if (isStepCell) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable { // This overlay will handle clicks for the step cells
                        handleStepCellClick()
                    }
            )
        }
    }
}
