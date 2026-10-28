package com.momir.android.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.GuardedBy
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds

/**
 * Protocol-free BLE transport: scans for a device that [resolve] recognises, connects,
 * subscribes to what the target's flow control needs, and writes byte streams.
 *
 * Threading: GATT callbacks arrive on binder threads while the UI and print code run on the
 * main and IO threads. The connection [State] lives in a [StateFlow] and only changes through
 * atomic transitions; the other shared fields are `@Volatile`, and the notification queue is
 * guarded by [notifyLock].
 */
class BleTransport<T : BleTarget>(
    private val context: Context,
    private val resolve: (String) -> T?,
) {
    enum class State { DISCONNECTED, CONNECTING, READY, PRINTING }

    companion object {
        private const val TAG = "MomirBle"
        private const val SCAN_TIMEOUT_MS = 12_000L
        private const val CONNECT_RETRY_DELAY_MS = 650L
        private const val MAX_CONNECT_ATTEMPTS = 2
        private const val NOTIFY_SETUP_TIMEOUT_MS = 4_000L
        private const val NOTIFY_SETUP_START_DELAY_MS = 300L
        private const val DISCOVERY_FALLBACK_MS = 1_500L
        private const val WRITE_RETRY_LIMIT = 20
        private const val WRITE_RETRY_DELAY_MS = 10L

        private val CCCD_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        manager.adapter
    }

    private var scanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var scanTimeoutTask: Runnable? = null

    @Volatile
    private var gatt: BluetoothGatt? = null

    @Volatile
    private var gattSpec: GattSpec? = null

    @Volatile
    private var writeChar: BluetoothGattCharacteristic? = null

    @Volatile
    private var negotiatedMtu: Int = 23

    @Volatile
    private var lastGattStatus: Int = 0

    // Characteristics still to subscribe to (CCCD writes must run one at a time).
    private val notifyLock = Any()

    @GuardedBy("notifyLock")
    private val notifyQueue = ArrayDeque<BluetoothGattCharacteristic>()

    @GuardedBy("notifyLock")
    private var onNotifySetupDone: (() -> Unit)? = null

    @Volatile
    private var flow: FlowControl = object : FlowControl {}

    private val _state = MutableStateFlow(State.DISCONNECTED)

    /** Observable connection state; changes only through atomic transitions. */
    val stateFlow: StateFlow<State> = _state.asStateFlow()

    val state: State get() = _state.value

    /** The printer that was resolved for the current/last connection. */
    @Volatile
    var target: T? = null
        private set

    @Volatile
    var deviceName: String? = null
        private set

    @Volatile
    var lastError: String? = null
        private set

    /** Moves to [to] only if the current state is one of [allowedFrom]; returns whether it did. */
    private fun transition(allowedFrom: Set<State>, to: State): Boolean {
        while (true) {
            val current = _state.value
            if (current !in allowedFrom) return false
            if (_state.compareAndSet(current, to)) return true
        }
    }

    fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val scanGranted =
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_SCAN,
                ) == PackageManager.PERMISSION_GRANTED
            val connectGranted =
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT,
                ) == PackageManager.PERMISSION_GRANTED
            scanGranted && connectGranted
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    @RequiresPermission(
        allOf = [
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        ],
    )
    fun connect(onDone: (Boolean) -> Unit) {
        fun fail(msg: String) {
            Log.w(TAG, msg)
            lastError = msg
            _state.value = State.DISCONNECTED
            onDone(false)
        }

        if (!hasBluetoothPermissions()) {
            fail("Bluetooth permissions missing")
            return
        }

        // Atomic: a second connect (or a connect during a print) is rejected without
        // touching the state of the operation that is already running.
        if (!transition(setOf(State.DISCONNECTED, State.READY), State.CONNECTING)) {
            lastError = "Printer is busy (${_state.value})"
            Log.w(TAG, lastError ?: "Printer is busy")
            onDone(false)
            return
        }

        val adapter = bluetoothAdapter ?: run {
            fail("Bluetooth adapter unavailable")
            return
        }
        if (!adapter.isEnabled) {
            fail("Bluetooth is turned off")
            return
        }
        val sc = adapter.bluetoothLeScanner ?: run {
            fail("BLE scanner unavailable")
            return
        }

        // Some OEMs still gate BLE scan delivery behind system Location being enabled.
        if (!isLocationEnabled()) {
            Log.w(TAG, "Location services appear OFF; some devices may suppress BLE scan results")
        }

        lastError = null
        scanner = sc
        scanTimeoutTask?.let { mainHandler.removeCallbacks(it) }
        scanCallback?.let {
            try {
                scanner?.stopScan(it)
            } catch (_: SecurityException) {
            }
        }

        findBondedPrinter(adapter)?.let { bonded ->
            Log.i(TAG, "Using bonded printer fallback: ${bonded.address}")
            connectGatt(bonded, onDone)
            return
        }

        var sawAnyScanResult = false

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                sawAnyScanResult = true
                processScanResult(result, this, onDone)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                if (results.isNotEmpty()) {
                    sawAnyScanResult = true
                }
                for (r in results) {
                    processScanResult(r, this, onDone)
                }
            }

            private fun processScanResult(
                result: ScanResult,
                callback: ScanCallback,
                done: (Boolean) -> Unit,
            ) {
                val d = result.device ?: return
                val deviceName = try {
                    if (
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.BLUETOOTH_CONNECT,
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        d.name
                    } else {
                        null
                    }
                } catch (_: SecurityException) {
                    null
                }
                val advName = result.scanRecord?.deviceName
                val name = deviceName ?: advName

                Log.d(TAG, "Scan hit: name=${name ?: "<none>"}, mac=${d.address}, rssi=${result.rssi}")
                if (name == null) return
                if (resolve(name) != null) {
                    try {
                        scanner?.stopScan(callback)
                    } catch (_: SecurityException) {
                    }
                    scanTimeoutTask?.let { mainHandler.removeCallbacks(it) }
                    scanCallback = null
                    Log.i(TAG, "Printer matched: $name (${d.address})")
                    connectGatt(d, done)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                fail("BLE scan failed with code $errorCode")
            }
        }

        scanCallback = cb
        val timeoutTask = Runnable {
            try {
                scanner?.stopScan(cb)
            } catch (_: SecurityException) {
            }
            scanCallback = null
            if (!sawAnyScanResult) {
                fail("No BLE advertisements received in ${SCAN_TIMEOUT_MS / 1000}s")
            } else {
                fail("No supported printer found within ${SCAN_TIMEOUT_MS / 1000}s")
            }
        }
        scanTimeoutTask = timeoutTask
        mainHandler.postDelayed(timeoutTask, SCAN_TIMEOUT_MS)

        try {
            Log.i(TAG, "Starting BLE scan")
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0L)
                .build()
            sc.startScan(emptyList(), settings, cb)
        } catch (e: SecurityException) {
            scanCallback = null
            mainHandler.removeCallbacks(timeoutTask)
            fail("Missing permission while starting scan. ${e.message}")
        } catch (e: Exception) {
            scanCallback = null
            mainHandler.removeCallbacks(timeoutTask)
            fail("Unable to start BLE scan: ${e.message}")
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun connectGatt(device: BluetoothDevice, onDone: (Boolean) -> Unit) {
        Log.i(TAG, "Connecting GATT to ${device.address}")
        val name = try {
            device.name
        } catch (_: SecurityException) {
            null
        }
        deviceName = name

        val resolved = resolve(name.orEmpty())
        if (resolved == null) {
            lastError = "No driver for printer ${name ?: device.address}"
            Log.w(TAG, lastError ?: "No driver")
            _state.value = State.DISCONNECTED
            onDone(false)
            return
        }
        target = resolved
        val spec = resolved.gatt
        gattSpec = spec
        flow = resolved.newFlowControl()

        lastError = null
        negotiatedMtu = 23
        lastGattStatus = 0
        resetFlow()

        // Callbacks arrive on binder threads and the main handler, so these must be atomic.
        val connectResultDelivered = AtomicBoolean(false)

        safelyCloseGatt(gatt)
        gatt = null

        fun completeConnectOnce(ok: Boolean) {
            if (connectResultDelivered.compareAndSet(false, true)) {
                onDone(ok)
            }
        }

        fun connectAttempt(attempt: Int) {
            Log.i(TAG, "Connecting GATT attempt $attempt/$MAX_CONNECT_ATTEMPTS to ${device.address}")

            // With sequentialSetup, GATT operations run strictly one after another
            // (MTU -> service discovery -> CCCD writes) to avoid a stuck
            // "device busy" state in Android's BluetoothGatt.
            val discoveryStarted = AtomicBoolean(false)
            fun startDiscovery(g: BluetoothGatt) {
                if (!discoveryStarted.compareAndSet(false, true)) return
                try {
                    g.discoverServices()
                } catch (_: Exception) {
                }
            }

            val callback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(
                    g: BluetoothGatt,
                    status: Int,
                    newState: Int,
                ) {
                    Log.d(TAG, "GATT state changed: status=$status newState=$newState")
                    lastGattStatus = status

                    if (newState == BluetoothGatt.STATE_CONNECTED) {
                        if (
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.BLUETOOTH_CONNECT,
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            try {
                                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                            } catch (_: SecurityException) {
                            }
                            try {
                                g.requestMtu(spec.mtuRequest)
                            } catch (_: SecurityException) {
                            }
                            if (spec.sequentialSetup) {
                                // Fallback in case the MTU callback never arrives.
                                mainHandler.postDelayed(
                                    { startDiscovery(g) },
                                    DISCOVERY_FALLBACK_MS,
                                )
                            } else {
                                try {
                                    g.discoverServices()
                                } catch (_: SecurityException) {
                                }
                            }
                        }
                    } else {
                        resetFlow()

                        if (
                            !connectResultDelivered.get() &&
                            status == 133 &&
                            attempt < MAX_CONNECT_ATTEMPTS
                        ) {
                            Log.w(
                                TAG,
                                "GATT 133 on attempt $attempt/$MAX_CONNECT_ATTEMPTS, retrying...",
                            )
                            safelyCloseGatt(g)
                            if (g === gatt) {
                                gatt = null
                            }
                            mainHandler.postDelayed(
                                { connectAttempt(attempt + 1) },
                                CONNECT_RETRY_DELAY_MS,
                            )
                            return
                        }

                        _state.value = State.DISCONNECTED
                        writeChar = null
                        lastError = if (status != BluetoothGatt.GATT_SUCCESS) {
                            "GATT disconnected (status=$status). " +
                                    "Check printer battery/temperature and retry."
                        } else {
                            "GATT disconnected"
                        }
                        safelyCloseGatt(g)
                        if (g === gatt) {
                            gatt = null
                        }
                        completeConnectOnce(false)
                    }
                }

                override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        negotiatedMtu = mtu
                        Log.d(TAG, "MTU negotiated: $mtu")
                    } else {
                        Log.w(TAG, "MTU negotiation failed: status=$status")
                    }
                    if (spec.sequentialSetup) {
                        startDiscovery(g)
                    }
                }

                override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                    Log.d(TAG, "Services discovered: status=$status")
                    val service = g.getService(spec.service)
                    val ch = service?.getCharacteristic(spec.write)
                    writeChar = ch

                    if (ch == null) {
                        _state.value = State.DISCONNECTED
                        lastError = "Printer write characteristic not found"
                        Log.w(TAG, lastError ?: "Printer write characteristic not found")
                        completeConnectOnce(false)
                        return
                    }

                    // Only CONNECTING -> READY: if disconnect() ran in the meantime, keep that.
                    if (!_state.compareAndSet(State.CONNECTING, State.READY)) {
                        Log.w(TAG, "Connection was cancelled while discovering services")
                        completeConnectOnce(false)
                        return
                    }

                    lastError = null
                    Log.i(
                        TAG,
                        "Write characteristic ${spec.write} " +
                                "properties=0x${ch.properties.toString(16)}",
                    )
                    Log.i(TAG, "Printer is ready: ${resolved.name}")

                    // Subscribe to whatever the flow control strategy needs, one CCCD
                    // write at a time, and report "connected" after the last one.
                    val wanted = flow.notifyUuids
                    val toSubscribe = wanted.mapNotNull { service.getCharacteristic(it) }
                    if (toSubscribe.size < wanted.size) {
                        Log.w(TAG, "Some flow-control characteristics are missing; pacing may be timed")
                    }
                    if (toSubscribe.isEmpty()) {
                        completeConnectOnce(true)
                        return
                    }

                    synchronized(notifyLock) {
                        notifyQueue.clear()
                        toSubscribe.forEach { notifyQueue.addLast(it) }
                        onNotifySetupDone = { completeConnectOnce(true) }
                    }
                    Log.i(TAG, "Subscribing to ${toSubscribe.size} notify characteristics")

                    mainHandler.postDelayed(
                        {
                            val pending = synchronized(notifyLock) {
                                onNotifySetupDone.also { onNotifySetupDone = null }
                            }
                            if (pending != null) {
                                Log.w(TAG, "Notify setup timed out; continuing anyway")
                                pending()
                            }
                        },
                        NOTIFY_SETUP_TIMEOUT_MS,
                    )
                    mainHandler.postDelayed(
                        { enableNextNotify(g) },
                        NOTIFY_SETUP_START_DELAY_MS,
                    )
                }

                override fun onDescriptorWrite(
                    g: BluetoothGatt,
                    descriptor: BluetoothGattDescriptor,
                    status: Int,
                ) {
                    if (descriptor.uuid != CCCD_UUID) return
                    val charUuid = descriptor.characteristic.uuid
                    Log.d(TAG, "Notify $charUuid subscribed (status=$status)")
                    flow.onSubscriptionResult(charUuid, status == BluetoothGatt.GATT_SUCCESS)
                    enableNextNotify(g)
                }

                @Suppress("DEPRECATION")
                override fun onCharacteristicChanged(
                    g: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                ) {
                    val value = characteristic.value ?: return
                    flow.onNotification(characteristic.uuid, value)
                }

                override fun onCharacteristicChanged(
                    g: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray,
                ) {
                    flow.onNotification(characteristic.uuid, value)
                }
            }

            @Suppress("DEPRECATION")
            gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(
                    context,
                    false,
                    callback,
                    BluetoothDevice.TRANSPORT_LE,
                )
            } else {
                device.connectGatt(context, false, callback)
            }
        }

        connectAttempt(1)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun writeChunk(
        g: BluetoothGatt,
        ch: BluetoothGattCharacteristic,
        chunk: ByteArray,
    ): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(
                ch,
                chunk,
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
            )
        } else {
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION")
            ch.value = chunk
            @Suppress("DEPRECATION")
            if (g.writeCharacteristic(ch)) BluetoothStatusCodes.SUCCESS else -1
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun sendCommands(commands: ByteArray): Boolean {
        val g = gatt ?: return false
        val ch = writeChar ?: return false
        val spec = gattSpec ?: return false

        // Atomic READY -> PRINTING: a second concurrent job is rejected here.
        if (!transition(setOf(State.READY), State.PRINTING)) return false
        val flow = this.flow

        try {
            val linkPayload = (negotiatedMtu - 3).coerceAtLeast(20)
            val chunkSize = minOf(spec.chunkSize, linkPayload, flow.maxChunkBytes)

            flow.beginJob()

            Log.i(
                TAG,
                "Sending ${commands.size} bytes in chunks of $chunkSize " +
                        "(mtu=$negotiatedMtu, flow=${flow::class.simpleName})",
            )

            val chunks =
                commands.asList()
                    .chunked(chunkSize)
                    .map { it.toByteArray() }

            val sendStart = SystemClock.elapsedRealtime()

            for ((i, chunk) in chunks.withIndex()) {
                if (_state.value == State.DISCONNECTED) {
                    lastError =
                        lastError
                            ?: "Disconnected during BLE transfer (status=$lastGattStatus)"
                    Log.w(TAG, lastError ?: "Disconnected during BLE transfer")
                    return false
                }

                flow.beforeChunk(i)

                // Write the chunk; retry briefly if the GATT stack reports busy.
                var code = writeChunk(g, ch, chunk)
                var tries = 0
                while (code != BluetoothStatusCodes.SUCCESS && tries < WRITE_RETRY_LIMIT) {
                    delay(WRITE_RETRY_DELAY_MS.milliseconds)
                    code = writeChunk(g, ch, chunk)
                    tries++
                }
                if (code != BluetoothStatusCodes.SUCCESS) {
                    lastError =
                        "BLE write failed at chunk ${i + 1}/${chunks.size} " +
                                "(code=$code, status=$lastGattStatus)"
                    Log.w(TAG, lastError ?: "BLE write failed")
                    _state.value = State.DISCONNECTED
                    return false
                }

                flow.afterChunk(i)
            }

            Log.i(
                TAG,
                "All ${chunks.size} chunks written in " +
                        "${SystemClock.elapsedRealtime() - sendStart} ms",
            )

            when (val outcome = flow.finish()) {
                is JobOutcome.Fault -> {
                    lastError = outcome.message
                    Log.w(TAG, outcome.message)
                    _state.compareAndSet(State.PRINTING, State.READY)
                    return false
                }

                JobOutcome.Done -> Unit
            }

            // Only PRINTING -> READY: a disconnect that happened while waiting must not be undone.
            if (!_state.compareAndSet(State.PRINTING, State.READY)) {
                lastError = lastError ?: "Connection lost before the printer confirmed the job"
                Log.w(TAG, lastError ?: "Connection lost")
                return false
            }
            lastError = null
            return true
        } catch (e: Exception) {
            lastError =
                "BLE write threw ${e.javaClass.simpleName}" +
                        (e.message?.let { ": $it" } ?: "")
            Log.e(TAG, lastError ?: "BLE write exception", e)
            _state.value = State.DISCONNECTED
            return false
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun disconnect() {
        scanTimeoutTask?.let { mainHandler.removeCallbacks(it) }
        scanCallback?.let {
            try {
                scanner?.stopScan(it)
            } catch (_: SecurityException) {
            }
        }
        scanCallback = null

        try {
            gatt?.disconnect()
            safelyCloseGatt(gatt)
        } catch (_: SecurityException) {
        }

        gatt = null
        writeChar = null
        resetFlow()
        negotiatedMtu = 23
        _state.value = State.DISCONNECTED
    }

    private fun resetFlow() {
        synchronized(notifyLock) {
            notifyQueue.clear()
            onNotifySetupDone = null
        }
        flow.reset()
    }

    private fun takeNextNotify(): Pair<BluetoothGattCharacteristic?, (() -> Unit)?> =
        synchronized(notifyLock) {
            val ch = notifyQueue.removeFirstOrNull()
            val done = if (ch == null) onNotifySetupDone else null
            if (ch == null) onNotifySetupDone = null
            ch to done
        }

    private fun enableNextNotify(g: BluetoothGatt) {
        while (true) {
            val (ch, done) = takeNextNotify()
            if (ch == null) {
                done?.invoke()
                return
            }
            try {
                if (enableNotify(g, ch)) return // wait for onDescriptorWrite
                Log.w(TAG, "Could not subscribe to ${ch.uuid}; skipping")
            } catch (_: SecurityException) {
                Log.w(TAG, "No Bluetooth permissions given")
            }
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun enableNotify(g: BluetoothGatt, ch: BluetoothGattCharacteristic): Boolean {
        return try {
            if (!g.setCharacteristicNotification(ch, true)) return false
            val cccd = ch.getDescriptor(CCCD_UUID) ?: return false
            val supportsNotify =
                ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
            val value =
                if (supportsNotify) {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                } else {
                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = value
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
        } catch (_: SecurityException) {
            false
        }
    }

    private fun safelyCloseGatt(gattRef: BluetoothGatt?) {
        if (gattRef == null) return
        try {
            gattRef.close()
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }
    }

    private fun findBondedPrinter(adapter: BluetoothAdapter): BluetoothDevice? {
        return try {
            val devices = adapter.bondedDevices ?: emptySet()
            devices.firstOrNull { d ->
                val n = try {
                    if (
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.BLUETOOTH_CONNECT,
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        d.name
                    } else {
                        null
                    }
                } catch (_: SecurityException) {
                    null
                }
                n != null && resolve(n) != null
            }
        } catch (_: SecurityException) {
            null
        }
    }

    private fun isLocationEnabled(): Boolean {
        return try {
            val lm =
                context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                    ?: return true

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                lm.isLocationEnabled
            } else {
                val gps = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                val network = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                gps || network
            }
        } catch (_: Exception) {
            true
        }
    }
}