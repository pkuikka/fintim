package com.example.fintim.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.fintim.R
import com.example.fintim.data.TimerData

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServoSetupScreen(
    uiState: UiState,
    onUpdateTimerData: (TimerData.() -> TimerData) -> Unit,
    onUpdateCheckBoxes: (servo: Int, isSet: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(dimensionResource(R.dimen.padding_small)),
    ) {
        Row {
            Column(modifier = modifier.width(80.dp)) {
                Text(
                    text = " Servo\n name",
                    style = typography.bodyMedium
                )
            }
            Column(modifier = modifier.width(80.dp)) {
                Text(
                    text = "Trim",
                    style = typography.bodyMedium
                )
            }
            Column(modifier = modifier.width(80.dp)) {
                Text(
                    text = "Min",
                    style = typography.bodyMedium
                )
            }
            Column(modifier = modifier.width(80.dp)) {
                Text(
                    text = "Max",
                    style = typography.bodyMedium
                )
            }
            Column(modifier = modifier.width(80.dp)) {
                Text(
                    text = "Rever\nsed",
                    style = typography.bodyMedium
                )
            }
        }

        // -------- Servo1 to 4 settings --------
        (0 until 4).forEach { i ->
            val name = when (i) {
                0 -> uiState.timerData.servo1Label
                1 -> uiState.timerData.servo2Label
                2 -> uiState.timerData.servo3Label
                3 -> uiState.timerData.servo4Label
                else -> ""
            }
            val trimPos = uiState.timerData.servoTrimPosition.getOrNull(i).toString()
            val onDoneActionTrimPos = { newValue: String ->
                val intValue = newValue.toIntOrNull() ?: 0
                onUpdateTimerData {
                    copy(
                        servoTrimPosition = this.servoTrimPosition.toMutableList()
                            .apply { set(i, intValue) })
                }
            }
            val minPos = uiState.timerData.servoMinPosition.getOrNull(i).toString()
            val onDoneActionMinPos = { newValue: String ->
                val intValue = newValue.toIntOrNull() ?: 0
                onUpdateTimerData {
                    copy(
                        servoMinPosition = this.servoMinPosition.toMutableList()
                            .apply { set(i, intValue) })
                }
            }
            val maxPos = uiState.timerData.servoMaxPosition.getOrNull(i).toString()
            val onDoneActionMaxPos = { newValue: String ->
                val intValue = newValue.toIntOrNull() ?: 0
                onUpdateTimerData {
                    copy(
                        servoMaxPosition = this.servoMaxPosition.toMutableList().apply { set(i, intValue) })
                }
            }
            val reversed = uiState.timerData.servoReversed.getOrNull(i) == 1

            ServoDataRow(
                name = name,
                trimPos = trimPos,
                minPos = minPos,
                maxPos = maxPos,
                onDoneActionTrimPos = onDoneActionTrimPos,
                onDoneActionMinPos = onDoneActionMinPos,
                onDoneActionMaxPos = onDoneActionMaxPos,
                reversedValue = reversed,
                reversedValueChange = { isChecked ->
                    onUpdateCheckBoxes(i, isChecked)
                }
            )
        }

        // Lisä servo asetukset
        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
        Column(
            modifier = Modifier
                .padding(dimensionResource(R.dimen.padding_small)),
        ) {
            RowWithField(
                "Limiter stop, jump to line #",
                "${uiState.timerData.limiterStop}",
                onDoneAction = { newValue ->
                    onUpdateTimerData { copy(limiterStop = newValue.toIntOrNull() ?: 0) }
                }
            )
            RowWithField(
                "Prop stop delay",
                "${uiState.timerData.propStopDelay}",
                onDoneAction = { newValue ->
                    onUpdateTimerData { copy(propStopDelay = newValue.toDoubleOrNull() ?: 0.0) }
                }
            )
            RowWithField(
                "Prop stop servo 5 release",
                "${uiState.timerData.propStopServo5Release}",
                onDoneAction = { newValue ->
                    onUpdateTimerData {
                        copy(
                            propStopServo5Release = newValue.toIntOrNull() ?: 0
                        )
                    }
                }
            )
            RowWithField(
                "Prop stop servo 5 stop",
                "${uiState.timerData.propStopServo5Stop}",
                onDoneAction = { newValue ->
                    onUpdateTimerData {
                        copy(
                            propStopServo5Stop = newValue.toIntOrNull() ?: 0
                        )
                    }
                }
            )
            RowWithCheckBox(
                "Flash enabled",
                value = uiState.timerData.flashEnabled == 1,
                onValueChange = { isChecked ->
                    onUpdateTimerData {
                        copy(flashEnabled = if (isChecked) 1 else 0)
                    }
                }
            )
        }
    }
}

@Composable
fun ServoDataRow(
    name: String,
    trimPos: String,
    minPos: String,
    maxPos: String,
    onDoneActionTrimPos: (String) -> Unit,
    onDoneActionMinPos: (String) -> Unit,
    onDoneActionMaxPos: (String) -> Unit,
    reversedValue: Boolean,
    reversedValueChange: (Boolean) -> Unit
) {
    // FIX the click problems on release builds for servo rows 1-3
    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CommonField(
            value = name,
            onDoneAction = { }
        )

        CommonField(
            value = trimPos,
            onDoneAction = onDoneActionTrimPos
        )

        CommonField(
            value = minPos,
            onDoneAction = onDoneActionMinPos
        )

        CommonField(
            value = maxPos,
            onDoneAction = onDoneActionMaxPos
        )

        Checkbox(
            checked = reversedValue,
            onCheckedChange = {
                reversedValueChange(it)
            }
        )
    }
}

@Preview(showBackground = true)
@Composable
fun ServoSetupScreenPreview() {
    // Call the stateless composable with fake data and empty lambdas
    val uiState = UiState(
        timerData = TimerData(
            modelName = "Test Model",
        )
    )
    ServoSetupScreen(
        uiState = uiState,
        onUpdateCheckBoxes = { _, _ -> },
        onUpdateTimerData = { _ -> }
    )
}
