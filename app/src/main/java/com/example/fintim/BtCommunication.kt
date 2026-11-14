package com.example.fintim

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.lang.Thread.sleep
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

// Define constants used in the class
private const val TAG = "BtCommunication"

// This is a standard UUID for SPP (Serial Port Profile). You can use it for testing.
private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
const val REQUEST_ENABLE_BT = 1 // Moved here to be accessible

class BtCommunication(private val context: Context) {

    private val bluetoothManager: BluetoothManager =
        context.getSystemService(BluetoothManager::class.java)
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var mmServerSocket: BluetoothSocket? = null
    private var btSerialInputStream: InputStream? = null
    private var btSerialOutputStream: OutputStream? = null
    private var workerThread: Thread? = null

    private val permissionRequestCode = 1001

    // Function to check if all necessary Bluetooth permissions are granted
    fun checkBlePermissions(): Boolean {
        val permissionsToRequest = mutableListOf<String>()

        // Android 12+ (API level 31 and above)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            addPermissionIfNeeded(permissionsToRequest, Manifest.permission.BLUETOOTH_CONNECT)
            addPermissionIfNeeded(permissionsToRequest, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            // For Android versions below Android 12 (Android 10 and 11)
            addPermissionIfNeeded(permissionsToRequest, Manifest.permission.BLUETOOTH_ADMIN)
            addPermissionIfNeeded(permissionsToRequest, Manifest.permission.BLUETOOTH)
            addPermissionIfNeeded(permissionsToRequest, Manifest.permission.ACCESS_FINE_LOCATION)
        }

        // If no permissions need to be requested, return true
        if (permissionsToRequest.isEmpty()) {
            return true
        }

        // Request the missing permissions
        if (context is Activity) {
            ActivityCompat.requestPermissions(
                context,
                permissionsToRequest.toTypedArray(),
                permissionRequestCode
            )
        }
        return false
    }

    private fun addPermissionIfNeeded(
        permissionsToRequest: MutableList<String>,
        permission: String
    ) {
        if (ContextCompat.checkSelfPermission(
                context,
                permission
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(permission)
        }
    }

    // Function to get paired Bluetooth devices
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun getPairedDevices(): List<String> {
        // Check permissions before proceeding
        if (!checkBlePermissions()) {
            // If permissions are not granted, return empty list
            return emptyList()
        }

        // Check if Bluetooth is supported and enabled
        if (bluetoothAdapter == null) {
            Log.w("Bluetooth", "Device does not support Bluetooth")
            return emptyList()
        }

        if (!bluetoothAdapter.isEnabled) {
            // Optionally, you can prompt the user to enable Bluetooth if it's disabled
            Log.w("Bluetooth", "Bluetooth is disabled, please enable it.")
            return emptyList()
        }

        // Fetch paired devices
        val pairedDevices = bluetoothAdapter.bondedDevices
        return pairedDevices.map { it.name }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun connectDevice(selectedDevice: String, onConnectionResult: (Boolean) -> Unit) {
        if (bluetoothAdapter == null) {
            toastAndLog("Device doesn't support Bluetooth", logLevel = "Log.e")
            onConnectionResult(false)
            return
        }
        if (!bluetoothAdapter.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            if (context is Activity) {
                if (ActivityCompat.checkSelfPermission(
                        context,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    context.startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
                } else {
                    toastAndLog("BLUETOOTH_CONNECT permission not granted.", logLevel = "Log.w")
                    onConnectionResult(false)
                    return
                }
            }
        }

        scope.launch {
            if (selectedDevice == "") {
                toastAndLog("No BlueTooth device selected.", logLevel = "Log.w")
                onConnectionResult(false)
                return@launch
            }

            // 1. Timeout for bondedDevices lookup (5 seconds)
            val deviceToConnect = withTimeoutOrNull(5000.milliseconds) {
                bluetoothAdapter.bondedDevices.find { it.name == selectedDevice }
            }

            if (deviceToConnect == null) {
                toastAndLog(
                    "ERROR: Device '$selectedDevice' lookup timed out or not found.",
                    logLevel = "Log.e"
                )
                onConnectionResult(false)
                return@launch
            }

            toastAndLog(message = "Connecting to device $selectedDevice...")

            try {
                // 2. Timeout for the actual connection attempt (10 seconds)
                val isConnected = withTimeoutOrNull(10000.milliseconds) {
                    try {
                        val socket: BluetoothSocket? =
                            deviceToConnect.createRfcommSocketToServiceRecord(SPP_UUID)
                        mmServerSocket = socket
                        mmServerSocket?.connect()
                        true
                    } catch (e: IOException) {
                        Log.e(TAG, "Socket connection failed", e)
                        false
                    }
                } ?: false

                if (isConnected) {
                    btSerialOutputStream = mmServerSocket?.outputStream
                    btSerialInputStream = mmServerSocket?.inputStream
                    onConnectionResult(true)
                } else {
                    toastAndLog(
                        "ERROR: Connection to $selectedDevice timed out or failed.",
                        logLevel = "Log.e"
                    )
                    onConnectionResult(false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error during connection", e)
                onConnectionResult(false)
            }
        }
    }

    fun timerCommunication(
        packetToSend: ByteArray? = null,
        batteryType: String,
        s2Divisor: Double = 11.58,
        onDataReceived: (packetBytes: ByteArray, batteryVoltage: Float) -> Unit,
        onConfirmationRequired: ((message: String, onConfirm: () -> Unit, onCancel: () -> Unit) -> Unit)? = null
    ) {
        var stopWorker = false
        var retryCount = 0

        fun getInt16From4AsciiString(index: Int, packetBytes: ByteArray): Int {
            val asciiString = String(packetBytes, StandardCharsets.US_ASCII)
            val asciiIndex = index * 2
            return asciiString.substring(asciiIndex, asciiIndex + 4).toInt(16)
        }

        if (btSerialInputStream == null || btSerialOutputStream == null) {
            toastAndLog("ERROR: BT serial streams are not connected!")
            return
        }
        workerThread = Thread {
            while (!Thread.currentThread().isInterrupted && !stopWorker) {
                sleep(1000)
                try {
                    val bytesAvailable = btSerialInputStream!!.available()

                    when (bytesAvailable) {
                        0 -> {
                            Log.i(TAG, "Bytes available: 0")
                            btSerialOutputStream!!.write("R".toByteArray())
                            btSerialOutputStream!!.flush()
                        }

                        512 -> { // Lue timerin arvot byteinä
                            val packetBytes = ByteArray(bytesAvailable)
                            btSerialInputStream!!.read(packetBytes) // read bytes
                            val buffer = String(packetBytes, charset("utf-8"))
                            Log.i(TAG, "Bytes available: $bytesAvailable, Buffer: $buffer")

                            // Hae timerin patterin jännite aina
                            val batteryVoltage = readBatteryVoltage(batteryType, s2Divisor)

                            // Ilmoita lukemisen onnistuminen ja päivitä ruutu, jos lähetettävää dataa ei ole
                            if (packetToSend == null) {
                                toastAndLog("Timer read.")
                                // We must post it to the main thread to be safe
                                Handler(Looper.getMainLooper()).post {
                                    onDataReceived(packetBytes, batteryVoltage)
                                }
                                cancel()
                                stopWorker = true
                            } else {
                                // Jos lähetettävää dataa, vertaa koneen ID ja jos sama lähetä datat
                                val currentModelId = getInt16From4AsciiString(13, packetBytes)
                                val writeModelId = getInt16From4AsciiString(13, packetToSend)

                                if (currentModelId == writeModelId) {
                                    //toastAndLog("Timer read and model validation OK. Writing...")
                                    Log.i(TAG, "Model validation OK: Model ID $writeModelId. Writing...")
                                    val writeResult = writeData(
                                        packetBytes = packetToSend
                                    )

                                    if (writeResult) {
                                        Handler(Looper.getMainLooper()).post {
                                            // Pass the current packet (containing battery/temp readings) back to UI
                                            onDataReceived(packetToSend, batteryVoltage)
                                        }
                                    }

                                    cancel()
                                    stopWorker = true
                                } else {
                                    val warningMessage = "Warning: Model doesn't match: \n" +
                                            "id: $currentModelId <> $writeModelId"
                                    Log.w(TAG, warningMessage)
                                    Handler(Looper.getMainLooper()).post {
                                        onConfirmationRequired?.invoke(
                                            warningMessage,
                                            {
                                                scope.launch {
                                                    val writeResult = writeData(
                                                        packetBytes = packetToSend
                                                    )
                                                    if (writeResult) {
                                                        Handler(Looper.getMainLooper()).post {
                                                            // Pass the current packet (containing battery/temp readings) back to UI
                                                            onDataReceived(packetToSend, batteryVoltage)
                                                        }
                                                    }
                                                    cancel()
                                                }
                                            },
                                            {
                                                cancel()
                                            }
                                        )
                                    }
                                    // Crucially, we return here to stop the function from proceeding
                                    // to the cancel() call below, keeping the connection open.
                                    return@Thread
                                }
                            }
                        }

                        else -> {
                            Log.i(TAG, "Bytes available: $bytesAvailable")
                        }
                    }
                    retryCount += 1
                    if (retryCount > 20) {  // Abort after 20s of trying
                        val packetBytes = ByteArray(bytesAvailable)
                        btSerialInputStream!!.read(packetBytes) // Empty buffer always
                        toastAndLog(message = "ERROR: Timer communication failed! " +
                                              "Bytes available: $bytesAvailable, " +
                                              "Buffer: ${packetBytes.contentToString()}", logLevel = "Log.e"
                        )
                        cancel()
                        stopWorker = true
                    }
                } catch (_: IOException) {
                    cancel()
                    stopWorker = true
                }
            }
        }
        workerThread?.start()
    }

    fun writeData(packetBytes: ByteArray): Boolean {
        val buffer = String(packetBytes, charset("utf-8"))

        Log.i(TAG, "Writing data bytes: ${packetBytes.size}, buffer $buffer")

        // Aloita kirjoitus komennolla "W" ja lähetä datat heti perään
        btSerialOutputStream!!.write("W".toByteArray())
        btSerialOutputStream!!.write(buffer.toByteArray())
        btSerialOutputStream!!.flush() // tyhjennä puskuri, että kaikki varmasti lähetetty

        // Verifying data (wait max 10s)
        for (i in 1..20) {
            sleep(500)
            val bytesAvailable = btSerialInputStream!!.available()
            Log.i(TAG, "$i, bytes available after write: $bytesAvailable")

            if (bytesAvailable == 512) {
                break
            }
        }
        val bytesAvailableAfterWrite = btSerialInputStream!!.available()
        if (bytesAvailableAfterWrite == 512) {
            val dataAfterWrite = ByteArray(bytesAvailableAfterWrite)
            btSerialInputStream!!.read(dataAfterWrite) // Empty buffer always

            // Varmista, että data on samat lähetetyssä ja luetussa paketissa
            if (!packetBytes.contentEquals(dataAfterWrite)) {
                toastAndLog(
                    "Write verification failed!\nBytes don't match:\n" +
                            "${packetBytes.toString(charset("utf-8"))} != \n" +
                            dataAfterWrite.toString(charset("utf-8")), logLevel = "Log.e"
                )
                return false
            }
            toastAndLog("Write successful!")
            return true
        } else {
            toastAndLog("Write verification failed! Only bytes available: " +
                        "$bytesAvailableAfterWrite", logLevel = "Log.e"
            )
        }
        return false
    }

    private fun readBatteryVoltage(batteryType: String, s2Divisor: Double): Float {
        btSerialOutputStream!!.write("B".toByteArray())
        btSerialOutputStream!!.flush()

        // Read battery voltage data (wait max 5s)
        for (i in 1..5) {
            sleep(500)
            val bytesAvailable = btSerialInputStream!!.available()
            Log.i(TAG, "$i, bytes available for battery: $bytesAvailable")

            if (bytesAvailable == 1) {
                break
            }
        }
        val bytesAvailable = btSerialInputStream!!.available()
        if (bytesAvailable == 1) {
            val batteryVoltageBuffer = ByteArray(bytesAvailable)
            btSerialInputStream!!.read(batteryVoltageBuffer)

            // The protocol sends a single byte value (0x00...0xFC).
            // We need the numeric value of that byte, not its string representation.
            val rawValue = batteryVoltageBuffer[0].toInt() and 0xFF
            val batteryVoltage: Float = when (batteryType) {
                "S3" -> (rawValue.toDouble() / 16.58).toFloat()
                "S2" -> (rawValue.toDouble() / s2Divisor).toFloat()
                else -> -1F
            }

            return batteryVoltage
        } else {
            return 0f
        }
    }


    private fun toastAndLog(message: String, logLevel: String = "Log.i") {
        Handler(Looper.getMainLooper()).post {
            when (logLevel) {
                "Log.e" -> {
                    Log.e(TAG, message)
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }

                "Log.w" -> {
                    Log.w(TAG, message)
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }

                else -> {
                    Log.i(TAG, message)
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun cancel() {
        // Send " " to close thw timer serial communication
        if (btSerialOutputStream != null)
            btSerialOutputStream!!.write(" ".toByteArray())
        try {
            mmServerSocket?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Could not close the connect socket", e)
        }
    }

}
