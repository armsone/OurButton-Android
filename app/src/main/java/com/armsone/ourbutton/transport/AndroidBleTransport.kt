package com.armsone.ourbutton.transport

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.armsone.ourbutton.model.CallEvent
import com.armsone.ourbutton.model.FamilySpace
import java.util.UUID
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Foreground-lifecycle, best-effort BLE dual-role transport.
 *
 * It deliberately does not claim process-death/background delivery. Call [start] and [stop]
 * from a visible lifecycle owner. Android force-stop, Doze, OEM policy and background-start
 * restrictions can suspend both roles unless the product later adopts a foreground service.
 */
@Suppress("DEPRECATION", "MissingPermission")
class AndroidBleTransport(context: Context) : CallTransport {
    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val adapter get() = bluetoothManager.adapter
    private val operations = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var currentStatus: TransportStatus = TransportStatus.Idle
    override val status: TransportStatus get() = currentStatus
    override var onEvent: ((CallEvent) -> Unit)? = null
    override var onStatusChange: ((TransportStatus) -> Unit)? = null
    var onPeerDisconnected: ((UUID, UUID) -> Unit)? = null

    private var started = false
    private var space: FamilySpace? = null
    private var gattServer: BluetoothGattServer? = null
    private var eventCharacteristic: BluetoothGattCharacteristic? = null
    private val centralGatts = mutableMapOf<String, BluetoothGatt>()
    private val subscribedCentrals = mutableMapOf<String, BluetoothDevice>()
    private var scannerStarted = false
    private var advertiserStarted = false
    private var reassembler = BleReassembler()
    private val cachedPresence = mutableMapOf<UUID, CallEvent>()
    private val recentOutgoing = mutableMapOf<UUID, Pair<CallEvent, Long>>()
    private val authenticatedPeerIDs = mutableMapOf<String, Pair<UUID, UUID>>()

    override fun start(space: FamilySpace, displayName: String) {
        operations.execute {
            stopInternal()
            this.space = space
            started = true
            if (!hasRuntimePermissions() || adapter?.isEnabled != true) {
                publishStatus(TransportStatus.Searching)
                return@execute
            }
            startPeripheralRole()
            startCentralRole()
            updateStatus()
        }
    }

    override fun stop() {
        operations.execute { stopInternal() }
    }

    fun refreshConnections() {
        operations.execute {
            if (!started || !hasRuntimePermissions() || adapter?.isEnabled != true) return@execute
            if (!scannerStarted) startCentralRole()
            if (!advertiserStarted || gattServer == null) startPeripheralRole()
            updateStatus()
        }
    }

    override fun send(event: CallEvent) {
        if (!BleCodec.supports(event)) throw TransportError.NoPeers
        val activeSpace = space
        if (!started || activeSpace == null || event.spaceID != activeSpace.id || !hasRuntimePermissions()) {
            throw TransportError.NoPeers
        }
        if (event.kind != CallEvent.Kind.Presence && currentStatus !is TransportStatus.Connected) {
            throw TransportError.NoPeers
        }

        operations.execute {
            if (!started || space?.id != event.spaceID) return@execute
            if (event.kind == CallEvent.Kind.Presence) {
                event.senderID?.let { cachedPresence[it] = event }
            } else {
                recentOutgoing[event.id] = event to (System.currentTimeMillis() + 30_000L)
            }
            transmit(event)
            if (event.kind != CallEvent.Kind.Presence && event.kind != CallEvent.Kind.VoiceMessage) {
                listOf(1L, 2L, 4L, 8L, 12L).forEach { seconds ->
                    operations.schedule({
                        if (started && recentOutgoing[event.id]?.second?.let {
                                it > System.currentTimeMillis()
                            } == true
                        ) transmit(event)
                    }, seconds, TimeUnit.SECONDS)
                }
            }
        }
    }

    private fun startCentralRole() {
        val scanner = runCatching { adapter?.bluetoothLeScanner }.getOrNull() ?: return
        runCatching {
            scanner.startScan(
                listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()),
                ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build(),
                scanCallback,
            )
            scannerStarted = true
        }
    }

    private fun startPeripheralRole() {
        if (!appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) return
        if (!adapter.isMultipleAdvertisementSupported) return
        closeGattServer()
        val characteristic = BluetoothGattCharacteristic(
            EVENT_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE,
        ).apply {
            addDescriptor(
                BluetoothGattDescriptor(
                    CCCD_UUID,
                    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
                ),
            )
        }
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(characteristic)
        eventCharacteristic = characteristic
        gattServer = bluetoothManager.openGattServer(appContext, serverCallback)?.also {
            it.addService(service)
        }

        val advertiser = runCatching { adapter.bluetoothLeAdvertiser }.getOrNull() ?: return
        runCatching {
            advertiser.startAdvertising(
                AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setConnectable(true)
                    .setTimeout(0)
                    .build(),
                AdvertiseData.Builder()
                    .addServiceUuid(ParcelUuid(SERVICE_UUID))
                    .setIncludeDeviceName(false)
                    .build(),
                advertiseCallback,
            )
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            operations.execute { connect(result.device) }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            operations.execute { results.forEach { connect(it.device) } }
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            operations.execute {
                advertiserStarted = true
                updateStatus()
            }
        }

        override fun onStartFailure(errorCode: Int) {
            operations.execute {
                advertiserStarted = false
                updateStatus()
            }
        }
    }

    private fun connect(device: BluetoothDevice) {
        if (!started || centralGatts.containsKey(device.address)) return
        val gatt = if (Build.VERSION.SDK_INT >= 26) {
            device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK)
        } else {
            device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }
        centralGatts[device.address] = gatt
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            operations.execute {
                if (!started || status != BluetoothGatt.GATT_SUCCESS || newState != BluetoothProfile.STATE_CONNECTED) {
                    centralGatts.remove(gatt.device.address)
                    runCatching { gatt.close() }
                    publishPeerDisconnectedIfGone(gatt.device.address)
                } else {
                    centralGatts[gatt.device.address] = gatt
                    runCatching { gatt.requestMtu(185) }
                    runCatching { gatt.discoverServices() }
                }
                updateStatus()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            operations.execute {
                if (status != BluetoothGatt.GATT_SUCCESS) return@execute
                val characteristic = gatt.getService(SERVICE_UUID)?.getCharacteristic(EVENT_UUID) ?: return@execute
                if (!gatt.setCharacteristicNotification(characteristic, true)) return@execute
                val descriptor = characteristic.getDescriptor(CCCD_UUID) ?: return@execute
                if (Build.VERSION.SDK_INT >= 33) {
                    gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(descriptor)
                }
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            operations.execute {
                if (status != BluetoothGatt.GATT_SUCCESS || descriptor.uuid != CCCD_UUID) return@execute
                cachedPresence.values.forEach { transmitToCentralGatt(it, gatt) }
                pruneRecent()
                recentOutgoing.values.forEach { transmitToCentralGatt(it.first, gatt) }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            operations.execute { accept(value, gatt.device.address) }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val value = characteristic.value ?: return
            operations.execute { accept(value, gatt.device.address) }
        }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            operations.execute {
                if (newState != BluetoothProfile.STATE_CONNECTED) {
                    subscribedCentrals.remove(device.address)
                    publishPeerDisconnectedIfGone(device.address)
                }
                updateStatus()
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            operations.execute {
                val enabling = descriptor.uuid == CCCD_UUID &&
                    value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                if (enabling) {
                    subscribedCentrals[device.address] = device
                    cachedPresence.values.forEach { transmit(it, listOf(device)) }
                    pruneRecent()
                    recentOutgoing.values.forEach { transmit(it.first, listOf(device)) }
                } else if (descriptor.uuid == CCCD_UUID) {
                    subscribedCentrals.remove(device.address)
                }
                if (responseNeeded) {
                    gattServer?.sendResponse(
                        device,
                        requestId,
                        if (descriptor.uuid == CCCD_UUID) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
                        offset,
                        value,
                    )
                }
                updateStatus()
            }
        }


        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            operations.execute {
                val accepted = characteristic.uuid == EVENT_UUID && !preparedWrite && offset == 0
                if (accepted) accept(value, device.address)
                if (responseNeeded) {
                    gattServer?.sendResponse(
                        device,
                        requestId,
                        if (accepted) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
                        offset,
                        value,
                    )
                }
            }
        }
    }

    private fun transmit(event: CallEvent, only: List<BluetoothDevice>? = null) {
        val activeSpace = space ?: return
        val characteristic = eventCharacteristic ?: return
        val fragments = runCatching {
            BleCodec.fragments(event, activeSpace.secret, MAX_PAYLOAD_LENGTH)
        }.getOrNull() ?: return
        val routes = if (only != null) {
            BlePeerRoutes(only.mapTo(mutableSetOf()) { it.address }, emptySet())
        } else {
            blePeerRoutes(subscribedCentrals.keys, centralGatts.keys)
        }
        routes.notifyAddresses.mapNotNull(subscribedCentrals::get).forEach { device ->
            val fragmentIntervalMillis = if (event.kind == CallEvent.Kind.VoiceMessage) 20L else 25L
            fragments.forEachIndexed { index, fragment ->
                operations.schedule({
                    val server = gattServer ?: return@schedule
                    if (Build.VERSION.SDK_INT >= 33) {
                        server.notifyCharacteristicChanged(device, characteristic, false, fragment)
                    } else {
                        characteristic.value = fragment
                        server.notifyCharacteristicChanged(device, characteristic, false)
                    }
                }, index * fragmentIntervalMillis, TimeUnit.MILLISECONDS)
            }
        }
        routes.writeAddresses.mapNotNull(centralGatts::get).forEach { gatt ->
            transmitToCentralGatt(event, gatt, fragments)
        }
    }

    private fun transmitToCentralGatt(
        event: CallEvent,
        gatt: BluetoothGatt,
        preparedFragments: List<ByteArray>? = null,
    ) {
        val activeSpace = space ?: return
        val characteristic = gatt.getService(SERVICE_UUID)?.getCharacteristic(EVENT_UUID) ?: return
        val fragments = preparedFragments ?: runCatching {
            BleCodec.fragments(event, activeSpace.secret, MAX_PAYLOAD_LENGTH)
        }.getOrNull() ?: return
        val fragmentIntervalMillis = if (event.kind == CallEvent.Kind.VoiceMessage) 20L else 25L
        fragments.forEachIndexed { index, fragment ->
            operations.schedule({
                if (!started || centralGatts[gatt.device.address] !== gatt) return@schedule
                if (Build.VERSION.SDK_INT >= 33) {
                    gatt.writeCharacteristic(
                        characteristic,
                        fragment,
                        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
                    )
                } else {
                    characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    characteristic.value = fragment
                    gatt.writeCharacteristic(characteristic)
                }
            }, index * fragmentIntervalMillis, TimeUnit.MILLISECONDS)
        }
    }

    private fun accept(fragment: ByteArray, peerId: String) {
        val activeSpace = space ?: return
        val combined = reassembler.append(fragment, peerId) ?: return
        val event = runCatching { BleCodec.open(combined, activeSpace.secret) }.getOrNull() ?: return
        if (event.spaceID != activeSpace.id) return
        event.senderID?.let { authenticatedPeerIDs[peerId] = activeSpace.id to it }
        main.post { onEvent?.invoke(event) }
    }

    private fun publishPeerDisconnectedIfGone(address: String) {
        if (centralGatts.containsKey(address) || subscribedCentrals.containsKey(address)) return
        val identity = authenticatedPeerIDs.remove(address) ?: return
        main.post { onPeerDisconnected?.invoke(identity.first, identity.second) }
    }

    private fun pruneRecent() {
        val now = System.currentTimeMillis()
        recentOutgoing.entries.removeAll { it.value.second <= now }
    }

    private fun updateStatus() {
        if (!started) return publishStatus(TransportStatus.Idle)
        val peerCount = max(subscribedCentrals.size, centralGatts.size)
        publishStatus(if (peerCount > 0) TransportStatus.Connected(peerCount) else TransportStatus.Searching)
    }

    private fun publishStatus(value: TransportStatus) {
        if (currentStatus == value) return
        currentStatus = value
        main.post { onStatusChange?.invoke(value) }
    }

    private fun stopInternal() {
        started = false
        runCatching { adapter?.bluetoothLeScanner }.getOrNull()?.let { scanner ->
            if (scannerStarted) runCatching { scanner.stopScan(scanCallback) }
        }
        scannerStarted = false
        runCatching { adapter?.bluetoothLeAdvertiser }.getOrNull()?.let { advertiser ->
            if (advertiserStarted) runCatching { advertiser.stopAdvertising(advertiseCallback) }
        }
        advertiserStarted = false
        centralGatts.values.forEach { gatt ->
            runCatching { gatt.disconnect() }
            runCatching { gatt.close() }
        }
        centralGatts.clear()
        subscribedCentrals.clear()
        closeGattServer()
        cachedPresence.clear()
        recentOutgoing.clear()
        authenticatedPeerIDs.clear()
        reassembler = BleReassembler()
        space = null
        publishStatus(TransportStatus.Idle)
    }

    private fun closeGattServer() {
        runCatching { gattServer?.clearServices() }
        runCatching { gattServer?.close() }
        gattServer = null
        eventCharacteristic = null
    }

    private fun hasRuntimePermissions(): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
        ).all { ContextCompat.checkSelfPermission(appContext, it) == PackageManager.PERMISSION_GRANTED }
    }

    private companion object {
        val SERVICE_UUID: UUID = UUID.fromString("B0770001-7A4D-4F6B-9D7A-425554544F4E")
        val EVENT_UUID: UUID = UUID.fromString("B0770002-7A4D-4F6B-9D7A-425554544F4E")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
        const val MAX_PAYLOAD_LENGTH = 160
    }
}
