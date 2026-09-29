package com.example.ai_vision.device

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** BLE carries only Wi-Fi credentials and the ESP32's TCP address. */
class GlassBleProvisioner(context: Context) {
    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private var gatt: BluetoothGatt? = null
    private var mtu = 23
    private var connected = CompletableDeferred<Unit>()
    private var servicesReady = CompletableDeferred<Unit>()
    private var descriptorReady = CompletableDeferred<Unit>()
    private var writeReady = CompletableDeferred<Unit>()
    private var endpointReady = CompletableDeferred<GlassEndpoint>()
    private val notificationLine = StringBuilder()

    suspend fun provision(credentials: HotspotCredentials): GlassEndpoint {
        checkPermissions()
        val adapter = bluetoothManager.adapter ?: error("Bluetooth is unavailable")
        check(adapter.isEnabled) { "Turn on Bluetooth" }

        try {
            val device = withTimeout(15_000) { scan(adapter) }
            connected = CompletableDeferred()
            servicesReady = CompletableDeferred()
            endpointReady = CompletableDeferred()
            notificationLine.clear()
            gatt = device.connectGatt(appContext, false, callback)
            val activeGatt = gatt ?: error("Cannot open BLE connection")
            withTimeout(10_000) { connected.await() }

            // Default MTU 23 is sufficient: split the credentials into 20-byte
            // writes. On the tested phone, requesting MTU 185 disconnects GATT.
            check(activeGatt.discoverServices()) { "BLE service discovery failed" }
            withTimeout(10_000) { servicesReady.await() }

            val service = activeGatt.getService(GlassProtocol.BLE_SERVICE_UUID) ?: error("Glass BLE service missing")
            val write = service.getCharacteristic(GlassProtocol.BLE_WRITE_UUID) ?: error("Glass BLE write characteristic missing")
            val notify = service.getCharacteristic(GlassProtocol.BLE_NOTIFY_UUID) ?: error("Glass BLE notify characteristic missing")
            check(activeGatt.setCharacteristicNotification(notify, true)) { "Cannot enable BLE notification" }
            val descriptor = notify.getDescriptor(GlassProtocol.BLE_CCCD_UUID) ?: error("BLE notification descriptor missing")
            descriptorReady = CompletableDeferred()
            writeDescriptor(activeGatt, descriptor)
            withTimeout(5_000) { descriptorReady.await() }

            val payload = GlassProtocol.wifiCredentialsLine(credentials.ssid, credentials.password)
            val chunkSize = (mtu - 3).coerceAtLeast(20)
            for (chunk in payload.asList().chunked(chunkSize)) {
                writeReady = CompletableDeferred()
                writeCharacteristic(activeGatt, write, chunk.toByteArray())
                withTimeout(5_000) { writeReady.await() }
            }
            return withTimeout(25_000) { endpointReady.await() }
        } catch (error: SecurityException) {
            close()
            throw SecurityException("Bluetooth permission was revoked", error)
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    fun close() {
        val activeGatt = gatt
        gatt = null
        try { activeGatt?.disconnect() } catch (_: SecurityException) { Log.w("AiVisionBle", "Permission revoked during disconnect") }
        try { activeGatt?.close() } catch (_: SecurityException) { Log.w("AiVisionBle", "Permission revoked during close") }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                connected.complete(Unit)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                fail(IOException("BLE disconnected ($status)"))
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, newMtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) servicesReady.complete(Unit)
            else fail(IOException("BLE service discovery failed ($status)"))
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) descriptorReady.complete(Unit)
            else fail(IOException("BLE notification setup failed ($status)"))
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) writeReady.complete(Unit)
            else fail(IOException("BLE credential write failed ($status)"))
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == GlassProtocol.BLE_NOTIFY_UUID) acceptNotification(value)
        }

        @Deprecated("Required for Android 12 and earlier")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == GlassProtocol.BLE_NOTIFY_UUID) acceptNotification(characteristic.value ?: return)
        }
    }

    private fun acceptNotification(bytes: ByteArray) {
        for (byte in bytes) {
            if (byte == '\n'.code.toByte()) {
                val line = notificationLine.toString().trimEnd('\r')
                notificationLine.clear()
                val endpoint = GlassProtocol.parseWifiConnected(line)
                if (endpoint != null) endpointReady.complete(endpoint)
                else if (line.startsWith("ERROR|")) endpointReady.completeExceptionally(IOException(line))
            } else {
                if (notificationLine.length >= 128) {
                    endpointReady.completeExceptionally(IOException("BLE notification too long"))
                    return
                }
                notificationLine.append(byte.toInt().toChar())
            }
        }
        // The source document shows one complete notify without LF. Accept both forms.
        if (!endpointReady.isCompleted) {
            GlassProtocol.parseWifiConnected(notificationLine.toString())?.let {
                notificationLine.clear()
                endpointReady.complete(it)
            }
        }
    }

    private fun fail(error: Exception) {
        connected.completeExceptionally(error)
        servicesReady.completeExceptionally(error)
        descriptorReady.completeExceptionally(error)
        writeReady.completeExceptionally(error)
        endpointReady.completeExceptionally(error)
    }

    private suspend fun scan(adapter: BluetoothAdapter): BluetoothDevice = suspendCancellableCoroutine { continuation ->
        val scanner = adapter.bluetoothLeScanner ?: run {
            continuation.resumeWithException(IllegalStateException("BLE scanner unavailable"))
            return@suspendCancellableCoroutine
        }
        var resultCount = 0
        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                resultCount++
                val name = result.scanRecord?.deviceName ?: deviceName(result.device)
                val serviceMatch = result.scanRecord?.serviceUuids?.contains(ParcelUuid(GlassProtocol.BLE_SERVICE_UUID)) == true
                val nameMatch = name == GlassProtocol.BLE_NAME
                if (resultCount % 10 == 0 || nameMatch || serviceMatch) {
                    Log.d("AiVisionBle", "scan results=$resultCount nameMatch=$nameMatch serviceMatch=$serviceMatch")
                }
                if ((nameMatch || serviceMatch) && continuation.isActive) {
                    stopScan(scanner, this)
                    continuation.resume(result.device)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                stopScan(scanner, this)
                if (continuation.isActive) continuation.resumeWithException(IOException("BLE scan failed ($errorCode)"))
            }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try { scanner.startScan(null, settings, scanCallback) }
        catch (error: SecurityException) {
            if (continuation.isActive) continuation.resumeWithException(SecurityException("Bluetooth scan permission was revoked", error))
        }
        continuation.invokeOnCancellation {
            Log.d("AiVisionBle", "scan stopped after $resultCount results")
            stopScan(scanner, scanCallback)
        }
    }

    private fun deviceName(device: BluetoothDevice): String? = try { device.name }
        catch (_: SecurityException) { null }

    private fun stopScan(scanner: BluetoothLeScanner, callback: ScanCallback) {
        try { scanner.stopScan(callback) }
        catch (_: SecurityException) { Log.w("AiVisionBle", "Permission revoked during scan cleanup") }
        catch (_: IllegalStateException) { Log.w("AiVisionBle", "Bluetooth disabled during scan cleanup") }
    }

    private fun writeDescriptor(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor) {
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                check(gatt.writeDescriptor(descriptor, value) == android.bluetooth.BluetoothStatusCodes.SUCCESS) {
                    "Cannot write BLE notification descriptor"
                }
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = value
                @Suppress("DEPRECATION")
                check(gatt.writeDescriptor(descriptor)) { "Cannot write BLE notification descriptor" }
            }
        } catch (error: SecurityException) {
            throw SecurityException("Bluetooth permission was revoked while enabling notifications", error)
        }
    }

    private fun writeCharacteristic(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                check(gatt.writeCharacteristic(characteristic, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == android.bluetooth.BluetoothStatusCodes.SUCCESS) {
                    "Cannot write BLE credentials"
                }
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = value
                @Suppress("DEPRECATION")
                check(gatt.writeCharacteristic(characteristic)) { "Cannot write BLE credentials" }
            }
        } catch (error: SecurityException) {
            throw SecurityException("Bluetooth permission was revoked while provisioning", error)
        }
    }

    private fun checkPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (permissions.any { appContext.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            throw SecurityException("Bluetooth permission is missing")
        }
    }

}
