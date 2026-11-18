package com.example.fintim

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.lang.Thread.sleep
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds

class BtCommunication(private val context: Context) {

    companion object {
        const val TAG = "BtCommunication"
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        const val REQUEST_ENABLE_BT = 1
    }

    private val bluetoothManager: BluetoothManager =
        context.getSystemService(BluetoothManager::class.java)
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var mmServerSocket: BluetoothSocket? = null
    private var btSerialInputStream: InputStream? = null
    private var btSerialOutputStream: OutputStream? = null
    private var workerThread: Thread? = null

    // BLE specific variables
    private var bluetoothGatt: BluetoothGatt? = null
    private var bleInputStream: BleInputStream? = null
    private var bleOutputStream: BleOutputStream? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var readCharacteristic: BluetoothGattCharacteristic? = null
    private var writeLatch: java.util.concurrent.CountDownLatch? = null
    private var isBleMode: Boolean = false
    private var pendingConnectionCallback: ((Boolean) -> Unit)? = null

    private val permissionRequestCode = 1001

    // Check if device is a BLE timer (FT100 through FT110)
    private fun isBleDevice(deviceName: String): Boolean {
        val regex = Regex("FT(10[0-9]|110)", RegexOption.IGNORE_CASE)
        return regex.containsMatchIn(deviceName)
    }

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
            return emptyList()
        }

        // Check if Bluetooth is supported and enabled
        if (bluetoothAdapter == null) {
            Log.w(TAG, "Device does not support Bluetooth")
            return emptyList()
        }

        if (!bluetoothAdapter.isEnabled) {
            Log.w(TAG, "Bluetooth is disabled, please enable it.")
            return emptyList()
        }

        // Fetch paired devices
        val pairedDevices = bluetoothAdapter.bondedDevices
        return pairedDevices.map { it.name }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun connectDevice(selectedDevice: String, onConnectionResult: (Boolean) -> Unit) {
        // Always cancel any lingering previous session or worker thread first
        cancel()

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
            if (selectedDevice.isEmpty()) {
                toastAndLog("No Bluetooth device selected.", logLevel = "Log.w")
                onConnectionResult(false)
                return@launch
            }

            // Check if this is the new BLE timer (FT100-FT110)
            if (isBleDevice(selectedDevice)) {
                Log.i(TAG, "Connecting to $selectedDevice using BLE mode.")
                connectBleDevice(selectedDevice, onConnectionResult)
                return@launch
            }

            // Otherwise, use Classic Bluetooth (SPP)
            Log.i(TAG, "Connecting to $selectedDevice using Classic BT mode.")
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
                    isBleMode = false
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

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN])
    private suspend fun connectBleDevice(deviceName: String, onConnectionResult: (Boolean) -> Unit) {
        pendingConnectionCallback = onConnectionResult

        // Find device via bonded list or fresh BLE scan
        val deviceToConnect = findBleDevice(deviceName, 5000)
        if (deviceToConnect == null) {
            toastAndLog("ERROR: BLE Device '$deviceName' not found.", logLevel = "Log.e")
            pendingConnectionCallback?.invoke(false)
            pendingConnectionCallback = null
            return
        }

        Log.i(TAG, "connectBleDevice: found device ${deviceToConnect.address} (${deviceToConnect.name})")
        toastAndLog(message = "Connecting to BLE device $deviceName...")

        var createdGatt: BluetoothGatt? = null
        var serviceDiscoveryInitiated = false

        // Connect GATT with a timeout coroutine (12 seconds)
        val connectedSuccessfully = withTimeoutOrNull(12000.milliseconds) {
            suspendCancellableCoroutine { continuation ->
                val localCallback = object : BluetoothGattCallback() {
                    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                        Log.i(TAG, "onConnectionStateChange: status=$status, newState=$newState")
                        if (newState == BluetoothProfile.STATE_CONNECTED) {
                            Log.i(TAG, "Connected to BLE GATT server. Status: $status")
                            bluetoothGatt = gatt
                            refreshGattCache(gatt)
                            gatt.requestMtu(517)
                            gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)

                            // Fallback timer for service discovery (1.5 seconds)
                            Handler(Looper.getMainLooper()).postDelayed({
                                if (!serviceDiscoveryInitiated) {
                                    serviceDiscoveryInitiated = true
                                    Log.i(TAG, "Initiating service discovery via fallback timer.")
                                    gatt.discoverServices()
                                }
                            }, 1500)
                        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                            Log.i(TAG, "Disconnected from BLE GATT server. Status: $status")
                            if (isBleMode) {
                                toastAndLog("Disconnected: $deviceName", logLevel = "Log.e")
                            }
                            isBleMode = false
                            gatt.close()
                            if (bluetoothGatt == gatt) bluetoothGatt = null
                            if (continuation.isActive) {
                                continuation.resume(false)
                            }
                        }
                    }

                    private fun refreshGattCache(g: BluetoothGatt) {
                        try {
                            val localMethod = g.javaClass.getMethod("refresh")
                            localMethod.invoke(g)
                            Log.i(TAG, "GATT cache refreshed successfully.")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to refresh GATT cache", e)
                        }
                    }

                    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                        Log.i(TAG, "onMtuChanged: mtu=$mtu, status=$status")
                        // Trigger service discovery immediately upon MTU completion (universal for Pixel 5 & Pixel 9)
                        if (!serviceDiscoveryInitiated) {
                            serviceDiscoveryInitiated = true
                            Log.i(TAG, "Initiating service discovery from onMtuChanged.")
                            gatt.discoverServices()
                        }
                    }

                    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                        Log.i(TAG, "onServicesDiscovered: status=$status")
                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            Log.i(TAG, "BLE services discovered successfully. Status: $status")
                            var foundWrite = false
                            var foundRead = false

                            for (service in gatt.services) {
                                for (characteristic in service.characteristics) {
                                    val charUuid = characteristic.uuid.toString()
                                    val props = characteristic.properties

                                    // Specifically target Microchip ISSC Transparent UART bidirectional characteristic (49535343-1e4d-...)
                                    if (charUuid.equals("49535343-1e4d-4bd9-ba61-23c647249616", ignoreCase = true)) {
                                        writeCharacteristic = characteristic
                                        readCharacteristic = characteristic
                                        foundWrite = true
                                        foundRead = true
                                        Log.i(TAG, "     [Assigned Bidirectional ISSC Characteristic: $charUuid]")

                                        gatt.setCharacteristicNotification(characteristic, true)
                                        val descriptor = characteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                                        if (descriptor != null) {
                                            val descriptorValue = if ((props and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                                                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                                            } else {
                                                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                            }

                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                                gatt.writeDescriptor(descriptor, descriptorValue)
                                            } else {
                                                descriptor.value = descriptorValue
                                                gatt.writeDescriptor(descriptor)
                                            }
                                            Log.i(TAG, "     [Writing descriptor for characteristic: $charUuid, value: ${descriptorValue.contentToString()}]")
                                        }
                                        break
                                    }
                                }
                                if (foundWrite) break
                            }

                            // Fallback if specific ISSC characteristic wasn't matched
                            if (!foundWrite || !foundRead) {
                                for (service in gatt.services) {
                                    for (characteristic in service.characteristics) {
                                        val charUuid = characteristic.uuid.toString()
                                        val props = characteristic.properties
                                        if (!foundWrite && (props and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0) {
                                            writeCharacteristic = characteristic
                                            foundWrite = true
                                        }
                                        if (!foundRead && (props and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0) {
                                            readCharacteristic = characteristic
                                            foundRead = true
                                            gatt.setCharacteristicNotification(characteristic, true)
                                            val descriptor = characteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                                            if (descriptor != null) {
                                                val descriptorValue = if ((props and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                                                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                                                } else {
                                                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                                }
                                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                                    gatt.writeDescriptor(descriptor, descriptorValue)
                                                } else {
                                                    descriptor.value = descriptorValue
                                                    gatt.writeDescriptor(descriptor)
                                                }
                                                Log.i(TAG, "     [Writing descriptor fallback for: $charUuid]")
                                            }
                                            break
                                        }
                                    }
                                    if (foundWrite && foundRead) break
                                }
                            }

                            if (foundWrite && foundRead) {
                                isBleMode = true
                                bleInputStream = BleInputStream()
                                bleOutputStream = BleOutputStream { data ->
                                    writeBleData(data)
                                }
                                btSerialInputStream = bleInputStream
                                btSerialOutputStream = bleOutputStream
                                // We wait for onDescriptorWrite before resuming continuation
                            } else {
                                toastAndLog("ERROR: BLE UART characteristics not found on $deviceName", logLevel = "Log.e")
                                gatt.disconnect()
                                if (continuation.isActive) {
                                    continuation.resume(false)
                                }
                            }
                        } else {
                            Log.w(TAG, "onServicesDiscovered received: $status")
                            gatt.disconnect()
                            if (continuation.isActive) {
                                continuation.resume(false)
                            }
                        }
                    }

                    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                        Log.i(TAG, "onDescriptorWrite status: $status for descriptor: ${descriptor.uuid}")
                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            Log.i(TAG, "BLE descriptor write success. Notifications/Indications active.")
                            //toastAndLog("BLE Connected successfully!")
                            if (continuation.isActive) {
                                continuation.resume(true)
                            }
                        } else {
                            Log.e(TAG, "BLE descriptor write failed with status: $status")
                            if (continuation.isActive) {
                                continuation.resume(false)
                            }
                        }
                    }

                    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                        val data = characteristic.value
                        if (data != null && data.isNotEmpty()) {
                            bleInputStream?.offer(data)
                        }
                    }

                    override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            val data = characteristic.value
                            if (data != null && data.isNotEmpty()) {
                                bleInputStream?.offer(data)
                            }
                        }
                    }

                    override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            Log.e(TAG, "BLE characteristic write failed with status: $status")
                        }
                        writeLatch?.countDown()
                    }
                }

                scope.launch(Dispatchers.Main) {
                    if (ActivityCompat.checkSelfPermission(
                            context,
                            Manifest.permission.BLUETOOTH_CONNECT
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        toastAndLog("BLUETOOTH_CONNECT permission not granted.", logLevel = "Log.e")
                        if (continuation.isActive) {
                            continuation.resume(false)
                        }
                        return@launch
                    }
                    Log.i(TAG, "Invoking connectGatt on device ${deviceToConnect.address} with TRANSPORT_LE")
                    // Use TRANSPORT_LE with autoConnect = false
                    createdGatt = deviceToConnect.connectGatt(context, false, localCallback, BluetoothDevice.TRANSPORT_LE)
                }

                continuation.invokeOnCancellation {
                    createdGatt?.disconnect()
                    createdGatt?.close()
                }
            }
        } ?: false

        if (!connectedSuccessfully) {
            toastAndLog("Connection failed: $deviceName off?", logLevel = "Log.e")
            createdGatt?.disconnect()
            createdGatt?.close()
            if (bluetoothGatt == createdGatt) bluetoothGatt = null
            isBleMode = false
        }

        pendingConnectionCallback?.invoke(connectedSuccessfully)
        pendingConnectionCallback = null
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN])
    private suspend fun findBleDevice(deviceName: String, timeoutMs: Long): BluetoothDevice? {
        // 1. Check bonded devices FIRST. If bonded, always use the bonded device reference!
        val bondedDevice = bluetoothAdapter?.bondedDevices?.find {
            try {
                it.name == deviceName || it.name?.contains(deviceName, ignoreCase = true) == true
            } catch (_: SecurityException) {
                false
            }
        }
        if (bondedDevice != null) {
            Log.i(TAG, "Found $deviceName in bonded devices: ${bondedDevice.address}")
            return bondedDevice
        }

        // 2. If not bonded, scan for it
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return null
        var foundDevice: BluetoothDevice? = null
        val latch = java.util.concurrent.CountDownLatch(1)

        val callback = object : android.bluetooth.le.ScanCallback() {
            override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
                val device = result.device
                val name = try {
                    device.name
                } catch (_: SecurityException) {
                    null
                }
                val scanRecordName = result.scanRecord?.deviceName

                if (name == deviceName || name?.contains(deviceName, ignoreCase = true) == true ||
                    scanRecordName == deviceName || scanRecordName?.contains(deviceName, ignoreCase = true) == true ||
                    isBleDevice(name ?: "") || isBleDevice(scanRecordName ?: "")) {
                    foundDevice = device
                    latch.countDown()
                }
            }
        }

        try {
            Log.i(TAG, "Starting BLE scan for $deviceName...")
            scanner.startScan(callback)
            withTimeoutOrNull(timeoutMs) {
                withContext(Dispatchers.IO) {
                    latch.await()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "BLE scan error", e)
        } finally {
            try {
                scanner.stopScan(callback)
                Log.i(TAG, "Stopped BLE scan.")
            } catch (_: Exception) {}
        }

        return foundDevice
    }

    @Suppress("DEPRECATION")
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun writeBleData(data: ByteArray) {
        val gatt = bluetoothGatt ?: return
        val characteristic = writeCharacteristic ?: return

        val chunkSize = 20
        val writeType = if ((characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        characteristic.writeType = writeType

        var offset = 0
        while (offset < data.size) {
            val length = minOf(chunkSize, data.size - offset)
            val chunk = data.copyOfRange(offset, offset + length)

            if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) {
                writeLatch = java.util.concurrent.CountDownLatch(1)
            }

            val success = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(characteristic, chunk, writeType) == 0
            } else {
                characteristic.value = chunk
                gatt.writeCharacteristic(characteristic)
            }

            if (!success) {
                Log.e(TAG, "Failed to initiate write characteristic for chunk at offset $offset")
                break
            }

            if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) {
                val written = writeLatch?.await(500, TimeUnit.MILLISECONDS) ?: true
                if (!written) {
                    Log.w(TAG, "Timeout waiting for onCharacteristicWrite at offset $offset")
                }
            } else {
                try {
                    Thread.sleep(15)
                } catch (_: InterruptedException) {}
            }

            offset += length
        }
        writeLatch = null
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
            toastAndLog("ERROR: BT serial streams are not connected!", logLevel = "Log.e")
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
                            if (bytesAvailable > 512) {
                                val packetBytes = ByteArray(bytesAvailable)
                                btSerialInputStream!!.read(packetBytes) // Empty buffer always
                            }
                        }
                    }
                    retryCount += 1
                    if (retryCount > 20) {  // Abort after 20s of trying
                        val packetBytes = ByteArray(bytesAvailable)
                        if (bytesAvailable > 0) {
                            btSerialInputStream!!.read(packetBytes) // Empty buffer always
                        }
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
        workerThread?.interrupt()
        workerThread = null
        bleInputStream?.clear()
        if (isBleMode) {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                bluetoothGatt?.disconnect()
                bluetoothGatt?.close()
            }
            bluetoothGatt = null
            isBleMode = false
        } else {
            // Send " " to close the timer serial communication
            if (btSerialOutputStream != null) {
                try {
                    btSerialOutputStream!!.write(" ".toByteArray())
                    btSerialOutputStream!!.flush()
                } catch (_: Exception) {}
            }
            try {
                mmServerSocket?.close()
            } catch (e: IOException) {
                Log.e(TAG, "Could not close the connect socket", e)
            }
        }
    }
}

// Helper InputStream and OutputStream classes to bridge BLE GATT characteristics with standard stream I/O
class BleInputStream : InputStream() {
    private val queue = LinkedBlockingQueue<Byte>()

    fun offer(bytes: ByteArray) {
        for (b in bytes) {
            queue.offer(b)
        }
    }

    override fun read(): Int {
        val b = queue.poll(100, TimeUnit.MILLISECONDS)
        return b?.let { it.toInt() and 0xFF } ?: -1
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        val first = queue.poll(100, TimeUnit.MILLISECONDS) ?: return -1
        b[off] = first
        var count = 1
        while (count < len) {
            val next = queue.poll() ?: break
            b[off + count] = next
            count++
        }
        return count
    }

    override fun available(): Int {
        return queue.size
    }



    fun clear() {
        queue.clear()
    }
}

class BleOutputStream(private val writeAction: (ByteArray) -> Unit) : OutputStream() {
    private val buffer = java.io.ByteArrayOutputStream()

    override fun write(b: Int) {
        buffer.write(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        buffer.write(b, off, len)
    }

    override fun flush() {
        val data = buffer.toByteArray()
        buffer.reset()
        if (data.isNotEmpty()) {
            writeAction(data)
        }
    }
}
