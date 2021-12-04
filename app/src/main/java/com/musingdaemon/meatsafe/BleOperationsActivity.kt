/*
 * Copyright 2019 Punch Through Design LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.musingdaemon.meatsafe

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.telephony.SmsManager
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.musingdaemon.meatsafe.ble.ConnectionEventListener
import com.musingdaemon.meatsafe.ble.ConnectionManager
import com.musingdaemon.meatsafe.ble.toHexString
import kotlinx.android.synthetic.main.activity_ble_operations.log_scroll_view
import kotlinx.android.synthetic.main.activity_ble_operations.log_text_view
import org.jetbrains.anko.alert
import timber.log.Timber
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Date
import java.util.Locale
import java.util.UUID


private const val SEND_SMS_PERMISSION_REQUEST_CODE = 0
private const val ENABLE_BLUETOOTH_REQUEST_CODE = 1
private const val LOCATION_PERMISSION_REQUEST_CODE = 2
private const val PERMISSION_REQUEST_BACKGROUND_LOCATION = 3

const val RECEIVE_BROADCAST = "com.musingdaemon.meatsafe.STATUS"
const val SECONDS_BETWEEN_SMS_WARNINGS = 600L
const val TEMP_CELCIUS_WARNING_LEVEL = -15.0
const val SENSORBUG_UUID = "XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX"
const val TEMP_SERVICE_UUID = "XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX"
const val TEMP_CHARACTERISTIC_UUID = "XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX"
const val DESTINATION_PHONE_NUMBER = "XXXXXXXXXX"
const val SENSORBUG_DEVICE_ADDRESS = "XX:XX:XX:XX:XX:XX"

class BleOperationsActivity : AppCompatActivity() {

    /*******************************************
     * Properties
     *******************************************/

    private val bluetoothAdapter: BluetoothAdapter by lazy {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }

    private val bleScanner by lazy {
        bluetoothAdapter.bluetoothLeScanner
    }

    private val scanSettings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        .build()

    private var isScanning = false
    private val scanResults = mutableListOf<ScanResult>()

    private lateinit var device: BluetoothDevice
    private val dateFormatter = SimpleDateFormat("MMM d, HH:mm:ss", Locale.US)
    private var notifyingCharacteristics = mutableListOf<UUID>()

    private var lastSmsTime = Instant.EPOCH

    private var nextStatusUpdateDate = LocalDateTime.of(LocalDate.now().plusDays(1), LocalTime.NOON)

    private val isLocationPermissionGranted
        get() = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)

    private val isBackgroundLocationPermissionGranted
        get() = hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    private val isSMSPermissionGranted
        get() = hasPermission(Manifest.permission.SEND_SMS)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ConnectionManager.registerListener(connectionEventListener)
        setContentView(R.layout.activity_ble_operations)
        val intentFilter = IntentFilter()
        intentFilter.addAction(RECEIVE_BROADCAST)
        applicationContext.registerReceiver(bReceiver, intentFilter)
        if (!isScanning) {
            startBleScan()
        }
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
    }

    override fun onDestroy() {
        ConnectionManager.unregisterListener(connectionEventListener)
        ConnectionManager.teardownConnection(device)
        applicationContext.unregisterReceiver(bReceiver)
        log("Destroy Complete")
        super.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            ENABLE_BLUETOOTH_REQUEST_CODE -> {
                if (resultCode != Activity.RESULT_OK) {
                    promptEnableBluetooth()
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            LOCATION_PERMISSION_REQUEST_CODE -> {
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_DENIED) {
                    requestLocationPermission()
                }
            }

            PERMISSION_REQUEST_BACKGROUND_LOCATION -> {
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_DENIED) {
                    requestBackgroundLocationPermission()
                }
            }

            SEND_SMS_PERMISSION_REQUEST_CODE -> {
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_DENIED) {
                    requestSMSPermission()
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun log(message: String) {
        val formattedMessage = String.format("%s: %s", dateFormatter.format(Date()), message)
        runOnUiThread {
            val currentLogText = if (log_text_view.text.isEmpty()) {
                "Beginning of log."
            } else {
                log_text_view.text
            }
            log_text_view.text = "$currentLogText\n$formattedMessage"
            log_scroll_view.post { log_scroll_view.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private val connectionEventListener by lazy {
        ConnectionEventListener().apply {

            onConnectionSetupComplete = { gatt ->
                log("Connected to device")
                device = gatt.device
                val characteristics = ConnectionManager.servicesOnDevice(device)
                    ?.filter { service -> service.uuid.toString() == TEMP_SERVICE_UUID }
                    ?.flatMap { service ->
                        service.characteristics?.filter { characteristic -> characteristic.uuid.toString() == TEMP_CHARACTERISTIC_UUID }
                            ?: listOf()
                    } ?: listOf()
                ConnectionManager.enableNotifications(device, characteristics[0])
            }

            onDisconnect = {
                sendSMS("WARN: The connection to the main freezer sensor was lost")
                log("Connection lost")
                startBleScan()
            }

            onConnectionFailed = {
                log("Connection failed")
                stopBleScan()
                startBleScan()
            }

            onCharacteristicRead = { _, characteristic ->
                log("Read from ${characteristic.uuid}: ${characteristic.value.toHexString()}")
            }

            onCharacteristicWrite = { _, characteristic ->
                log("Wrote to ${characteristic.uuid}")
            }

            onMtuChanged = { _, mtu ->
                log("MTU updated to $mtu")
            }

            onCharacteristicChanged = { _, characteristic ->
                if (characteristic.uuid == UUID.fromString(SENSORBUG_UUID)) {
                    val temp =
                        Integer.parseInt(characteristic.value.reversedArray().toHexString(), 16)
                            .toShort() * 0.0625
                    if (temp > TEMP_CELCIUS_WARNING_LEVEL && lastSmsTime.plusSeconds(
                            SECONDS_BETWEEN_SMS_WARNINGS
                        ).isBefore(
                            Instant.now()
                        )
                    ) {
                        log("Freezer temp is $temp. Sending SMS warning.")
                        sendSMS("WARN: The temperature of the main freezer is %.2fC".format(temp))
                        lastSmsTime = Instant.now()
                    }
                    if (nextStatusUpdateDate.isBefore(LocalDateTime.now()) && lastSmsTime.plusSeconds(
                            10
                        ).isBefore(
                            Instant.now()
                        )
                    ) {
                        log("Sending status update. Freezer temp is $temp.")
                        sendSMS("STATUS: The temperature of the main freezer is %.2fC".format(temp))
                        nextStatusUpdateDate =
                            LocalDateTime.of(LocalDate.now().plusDays(1), LocalTime.NOON)
                        lastSmsTime = Instant.now()
                    }
                }
            }

            onNotificationsEnabled = { _, characteristic ->
                log("Enabled notifications on ${characteristic.uuid}")
                notifyingCharacteristics.add(characteristic.uuid)
                sendSMS("SUCCESS: The connection to the main freezer sensor was restored")
            }

            onNotificationsDisabled = { _, characteristic ->
                log("Disabled notifications on ${characteristic.uuid}")
                notifyingCharacteristics.remove(characteristic.uuid)
            }
        }
    }

    private fun startBleScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !isLocationPermissionGranted) {
            sendSMS("WARN: Location permission denied")
            requestLocationPermission()
        } else if (!isBackgroundLocationPermissionGranted) {
            requestBackgroundLocationPermission()
        } else {
            scanResults.clear()
            val scanFilters = mutableListOf<ScanFilter>()
            scanFilters.add(ScanFilter.Builder().setDeviceAddress(SENSORBUG_DEVICE_ADDRESS).build())
            bleScanner.startScan(scanFilters, scanSettings, scanCallback)
            isScanning = true
            log("Starting BLE Scan")
        }
    }

    private fun stopBleScan() {
        bleScanner.stopScan(scanCallback)
        isScanning = false
        log("BLE Scan Stopped")
    }

    /*******************************************
     * Callback bodies
     *******************************************/

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            with(result.device) {
                Timber.i("Found BLE device! Name: ${name ?: "Unnamed"}, address: $address")
                log("Found device, connecting...")
                Timber.w("Connecting to $address")
                stopBleScan()
                ConnectionManager.connect(this, this@BleOperationsActivity)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Timber.e("onScanFailed: code $errorCode")
            log("Scan failed")
            stopBleScan()
        }
    }

    private fun requestLocationPermission() {
        if (isLocationPermissionGranted) {
            return
        }
        runOnUiThread {
            alert {
                title = "Location permission required"
                message = "Starting from Android M (6.0), the system requires apps to be granted " +
                    "location access in order to scan for BLE devices."
                isCancelable = false
                positiveButton(android.R.string.ok) {
                    requestPermission(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        LOCATION_PERMISSION_REQUEST_CODE
                    )
                }
            }.show()
        }
    }

    private fun requestBackgroundLocationPermission() {
        if (isBackgroundLocationPermissionGranted) {
            return
        }
        runOnUiThread {
            alert {
                title = "Background Location permission required"
                message = "Starting from Android M (6.0), the system requires apps to be granted " +
                    "background location access in order to scan for BLE devices."
                isCancelable = false
                positiveButton(android.R.string.ok) {
                    requestPermission(
                        Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                        PERMISSION_REQUEST_BACKGROUND_LOCATION
                    )
                }
            }.show()
        }
    }

    private fun requestSMSPermission() {
        if (isSMSPermissionGranted) {
            return
        }
        runOnUiThread {
            alert {
                title = "SMS permission required"
                message = "The system requires apps to be granted SMS access in order to sent SMS."
                isCancelable = false
                positiveButton(android.R.string.ok) {
                    requestPermission(
                        Manifest.permission.SEND_SMS,
                        SEND_SMS_PERMISSION_REQUEST_CODE
                    )
                }
            }.show()
        }
    }

    private fun promptEnableBluetooth() {
        if (!bluetoothAdapter.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivityForResult(enableBtIntent, ENABLE_BLUETOOTH_REQUEST_CODE)
        }
    }

    private fun sendSMS(message: String) {
        if (isSMSPermissionGranted) {
            val smsManager = SmsManager.getDefault()
            smsManager.sendTextMessage(
                DESTINATION_PHONE_NUMBER,
                null,
                message,
                null,
                null
            )
        } else {
            requestSMSPermission()
        }
    }

    private val bReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == RECEIVE_BROADCAST) {
                nextStatusUpdateDate = LocalDateTime.now()
            }
        }
    }

    /*******************************************
     * Extension functions
     *******************************************/

    private fun Context.hasPermission(permissionType: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permissionType) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun Activity.requestPermission(permission: String, requestCode: Int) {
        ActivityCompat.requestPermissions(this, arrayOf(permission), requestCode)
    }

}
