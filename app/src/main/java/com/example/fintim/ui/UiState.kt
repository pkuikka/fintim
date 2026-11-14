package com.example.fintim.ui

import com.example.fintim.data.TimerData

data class UiState(
    // Timer data related properties
    val timerData: TimerData = TimerData(),

    // BT communication related properties
    val btDevices: List<String> = emptyList(), // List of BT device names
    val selectedBtDevice: String = "", // The currently selected BT device

    // File handling
    val savedFiles: List<String> = emptyList(), // List of saved files

    // UI helpers
    val selectedRow: Int = -1, // -1 indicates no row is selected.

    // Display settings
    var displaySwipeVelocity: Float = 150F,
    var displaySwipeDistance: Float = 600F,

    // Temporary variables
    val tempS2BatteryVoltageDivisor: Double = 11.58,
)
