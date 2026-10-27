package com.momir.android.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
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
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.UUID

class BlePrinterManager(private val context: Context) {
    enum class State { DISCONNECTED, CONNECTING, READY, PRINTING }

    /** Wire protocol spoken by the printer. */
    enum class Protocol { PHOMEMO_ESC, TINY }

    data class Profile(
        val name: String,
        val printWidth: Int,
        val initCommands: ByteArray,
        val finalizeCommands: ByteArray,
        val chunkSize: Int,
        val chunksPerBurst: Int,
        val burstDelayMs: Long,
        val renderWidth: Int = 576,
        val rotateForPrint: Boolean = false,
        val useCreditFlowControl: Boolean = false,
        val protocol: Protocol = Protocol.PHOMEMO_ESC,
        val serviceUuid: UUID = SERVICE_UUID,
        val writeUuid: UUID = WRITE_UUID,
        val notifyUuids: List<UUID> = emptyList(),
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Profile

            if (printWidth != other.printWidth) return false
            if (chunkSize != other.chunkSize) return false
            if (chunksPerBurst != other.chunksPerBurst) return false
            if (burstDelayMs != other.burstDelayMs) return false
            if (renderWidth != other.renderWidth) return false
            if (rotateForPrint != other.rotateForPrint) return false
            if (useCreditFlowControl != other.useCreditFlowControl) return false
            if (protocol != other.protocol) return false
            if (serviceUuid != other.serviceUuid) return false
            if (writeUuid != other.writeUuid) return false
            if (notifyUuids != other.notifyUuids) return false
            if (name != other.name) return false
            if (!initCommands.contentEquals(other.initCommands)) return false
            if (!finalizeCommands.contentEquals(other.finalizeCommands)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = printWidth
            result = 31 * result + chunkSize
            result = 31 * result + chunksPerBurst
            result = 31 * result + burstDelayMs.hashCode()
            result = 31 * result + renderWidth
            result = 31 * result + rotateForPrint.hashCode()
            result = 31 * result + useCreditFlowControl.hashCode()
            result = 31 * result + protocol.hashCode()
            result = 31 * result + serviceUuid.hashCode()
            result = 31 * result + writeUuid.hashCode()
            result = 31 * result + notifyUuids.hashCode()
            result = 31 * result + name.hashCode()
            result = 31 * result + initCommands.contentHashCode()
            result = 31 * result + finalizeCommands.contentHashCode()
            return result
        }
    }

    companion object {
        private const val TAG = "MomirBle"
        private const val SCAN_TIMEOUT_MS = 12_000L
        private const val CONNECT_RETRY_DELAY_MS = 650L
        private const val MAX_CONNECT_ATTEMPTS = 2
        private const val NOTIFY_SETUP_TIMEOUT_MS = 4_000L
        private const val NOTIFY_SETUP_START_DELAY_MS = 300L
        private const val TINY_DISCOVERY_FALLBACK_MS = 1_500L
        private const val TINY_PAUSE_TIMEOUT_MS = 5_000L
        private const val TINY_DONE_TIMEOUT_MS = 60_000L
        private const val WRITE_RETRY_LIMIT = 20
        private const val WRITE_RETRY_DELAY_MS = 10L

        // Set to false if CCCD writes ever hang again; printing works without them.
        private const val TINY_SUBSCRIBE_NOTIFICATIONS = true

        // Phomemo family (M02/M04)
        private val SERVICE_UUID: UUID =
            UUID.fromString("0000ff00-0000-1000-8000-00805f9b34fb")
        private val WRITE_UUID: UUID =
            UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb")
        private val FLOW_CONTROL_UUID: UUID =
            UUID.fromString("0000ff03-0000-1000-8000-00805f9b34fb")
        private val CCCD_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // TiMini "tiny" family (X6H): AE30 service, AE01 write (no response)
        private val TINY_SERVICE_UUID: UUID =
            UUID.fromString("0000ae30-0000-1000-8000-00805f9b34fb")
        private val TINY_WRITE_UUID: UUID =
            UUID.fromString("0000ae01-0000-1000-8000-00805f9b34fb")
        private val TINY_NOTIFY_UUIDS: List<UUID> = listOf(
            UUID.fromString("0000ae02-0000-1000-8000-00805f9b34fb"),
            UUID.fromString("0000ae04-0000-1000-8000-00805f9b34fb"),
            UUID.fromString("0000ae05-0000-1000-8000-00805f9b34fb"),
        )

        // 51 78 AE 01 01 00 10 70 FF  /  51 78 AE 01 01 00 00 00 FF
        private val TINY_PAUSE = byteArrayOf(
            0x51, 0x78, 0xAE.toByte(), 0x01, 0x01, 0x00, 0x10, 0x70, 0xFF.toByte(),
        )
        private val TINY_RESUME = byteArrayOf(
            0x51, 0x78, 0xAE.toByte(), 0x01, 0x01, 0x00, 0x00, 0x00, 0xFF.toByte(),
        )

        private const val PRO_INITIAL_CREDIT_WINDOW = 4
        private const val PRO_INITIAL_WINDOW_DELAY_MS = 5L
        private const val PRO_CREDIT_DETECT_TIMEOUT_MS = 250L
        private const val PRO_CREDIT_TIMEOUT_MS = 1_000L
        private const val PRO_FALLBACK_CHUNK_DELAY_MS = 20L

        private val NAME_PREFIXES = listOf("M04", "M02", "T02", "Mr.in_", "X6H")
        private val NAME_CONTAINS =
            listOf("M04S", "M04AS", "M02S", "M02 PRO", "M02", "T02", "MR.IN_")

        val PROFILE_M02 = Profile(
            name = "M02",
            printWidth = 384,
            initCommands = byteArrayOf(
                0x1b, 0x40,
                0x1f, 0x11, 0x02, 0x04,
                0x1b, 0x61, 0x01,
                0x1f, 0x11, 0x24, 0x00,
            ),
            finalizeCommands = byteArrayOf(
                0x1b, 0x64, 0x02,
                0x1b, 0x64, 0x02,
                0x1f, 0x11, 0x08,
                0x1f, 0x11, 0x0e,
                0x1f, 0x11, 0x07,
                0x1f, 0x11, 0x09,
            ),
            chunkSize = 512,
            chunksPerBurst = 1,
            burstDelayMs = 50,
            renderWidth = 576,
        )

        val PROFILE_M02S = PROFILE_M02.copy(
            name = "M02S",
            printWidth = 576,
            renderWidth = 576,
            chunksPerBurst = 2,
        )

        // M02 Pro: physically measured community implementations indicate
        // 624 printable dots (78 bytes/line) at 300 DPI. FF03 is used as
        // optional vendor flow control on firmware that exposes it.
        val PROFILE_M02_PRO = Profile(
            name = "M02 PRO",
            printWidth = 624,
            renderWidth = 624,
            initCommands = byteArrayOf(
                0x1b, 0x40,
                0x1f, 0x11, 0x02, 0x04,
            ),
            finalizeCommands = byteArrayOf(
                0x1b, 0x64, 0x02,
                0x1b, 0x64, 0x02,
                0x1f, 0x11, 0x08,
                0x1f, 0x11, 0x0e,
                0x1f, 0x11, 0x07,
                0x1f, 0x11, 0x09,
            ),
            chunkSize = 128,
            chunksPerBurst = 1,
            burstDelayMs = PRO_FALLBACK_CHUNK_DELAY_MS,
            useCreditFlowControl = true,
        )

        val PROFILE_M04S = Profile(
            name = "M04S",
            printWidth = 1232,
            initCommands = byteArrayOf(
                0x1f, 0x11, 0x02, 0x04,
                0x1f, 0x11, 0x37, 0x96.toByte(),
                0x1f, 0x11, 0x0b,
                0x1f, 0x11, 0x35, 0x00,
            ),
            finalizeCommands = byteArrayOf(0x1b, 0x64, 0x02),
            chunkSize = 205,
            chunksPerBurst = 3,
            burstDelayMs = 50,
            renderWidth = 576,
            rotateForPrint = true,
        )

        // TiMini profile "x6h": TINY family, 384 dots, 200 dpi, LSB-first,
        // RLE rows, energy 9500, speed 10, feed padding 12. The job bytes are
        // built by TinyEncoder, so no init/finalize commands are needed here.
        val PROFILE_X6H = Profile(
            name = "X6H",
            printWidth = 384,
            renderWidth = 384,
            initCommands = byteArrayOf(),
            finalizeCommands = byteArrayOf(),
            chunkSize = 180,
            chunksPerBurst = 1,
            burstDelayMs = 4,
            protocol = Protocol.TINY,
            serviceUuid = TINY_SERVICE_UUID,
            writeUuid = TINY_WRITE_UUID,
            notifyUuids = TINY_NOTIFY_UUIDS,
        )
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        manager.adapter
    }

    private var scanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var scanTimeoutTask: Runnable? = null
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var negotiatedMtu: Int = 23
    private var lastGattStatus: Int = 0

    private val flowCredits = Channel<Unit>(Channel.UNLIMITED)

    @Volatile
    private var flowControlSubscribed = false

    // TINY family: notification setup queue, flow control and completion state
    private val notifyQueue = ArrayDeque<BluetoothGattCharacteristic>()
    private var onNotifySetupDone: (() -> Unit)? = null

    @Volatile
    private var tinyPaused = false

    @Volatile
    private var tinyJobDone = false

    @Volatile
    private var tinyStatus: ByteArray? = null

    private val tinyRx = ByteArrayOutputStream()

    var state: State = State.DISCONNECTED
        private set

    var profile: Profile = PROFILE_M02S
        private set

    var deviceName: String? = null
        private set

    var lastError: String? = null
        private set

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
            state = State.DISCONNECTED
            onDone(false)
        }

        if (!hasBluetoothPermissions()) {
            fail("Bluetooth permissions missing")
            return
        }
        if (state == State.CONNECTING || state == State.PRINTING) {
            fail("Printer is busy ($state)")
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
        state = State.CONNECTING
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
                if (matchesPrinterName(name)) {
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
            fail("Missing permission while starting scan")
        } catch (e: Exception) {
            scanCallback = null
            mainHandler.removeCallbacks(timeoutTask)
            fail("Unable to start BLE scan: ${e.message}")
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun connectGatt(device: BluetoothDevice, onDone: (Boolean) -> Unit) {
        Log.i(TAG, "Connecting GATT to ${device.address}")
        deviceName = try {
            device.name
        } catch (_: SecurityException) {
            null
        }
        profile = detectProfile(deviceName.orEmpty())
        lastError = null
        negotiatedMtu = 23
        lastGattStatus = 0
        flowControlSubscribed = false
        drainFlowCredits()
        resetTinyState()

        var connectResultDelivered = false

        safelyCloseGatt(gatt)
        gatt = null

        fun completeConnectOnce(ok: Boolean) {
            if (!connectResultDelivered) {
                connectResultDelivered = true
                onDone(ok)
            }
        }

        fun connectAttempt(attempt: Int) {
            Log.i(TAG, "Connecting GATT attempt $attempt/$MAX_CONNECT_ATTEMPTS to ${device.address}")

            // TINY: GATT operations run strictly one after another
            // (MTU -> service discovery -> CCCD writes) to avoid a stuck
            // "device busy" state in Android's BluetoothGatt.
            var discoveryStarted = false
            fun startDiscovery(g: BluetoothGatt) {
                if (discoveryStarted) return
                discoveryStarted = true
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
                            if (profile.protocol == Protocol.TINY) {
                                try {
                                    g.requestMtu(512)
                                } catch (_: SecurityException) {
                                }
                                // Fallback in case the MTU callback never arrives.
                                mainHandler.postDelayed(
                                    { startDiscovery(g) },
                                    TINY_DISCOVERY_FALLBACK_MS,
                                )
                            } else {
                                try {
                                    g.requestMtu(247)
                                } catch (_: SecurityException) {
                                }
                                try {
                                    g.discoverServices()
                                } catch (_: SecurityException) {
                                }
                            }
                        }
                    } else {
                        flowControlSubscribed = false
                        drainFlowCredits()
                        resetTinyState()

                        if (
                            !connectResultDelivered &&
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

                        state = State.DISCONNECTED
                        writeChar = null
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            lastError =
                                "GATT disconnected (status=$status). " +
                                        "Check printer battery/temperature and retry."
                        } else {
                            lastError = "GATT disconnected"
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
                    if (profile.protocol == Protocol.TINY) {
                        startDiscovery(g)
                    }
                }

                override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                    Log.d(TAG, "Services discovered: status=$status")
                    val service: BluetoothGattService? = g.getService(profile.serviceUuid)
                    writeChar = service?.getCharacteristic(profile.writeUuid)
                    state = if (writeChar != null) State.READY else State.DISCONNECTED

                    if (writeChar == null) {
                        lastError = "Printer write characteristic not found"
                        Log.w(TAG, lastError ?: "Printer write characteristic not found")
                    } else {
                        lastError = null

                        val writeProperties = writeChar?.properties ?: 0
                        Log.i(
                            TAG,
                            "Write characteristic ${profile.writeUuid} " +
                                    "properties=0x${writeProperties.toString(16)}",
                        )
                        Log.i(
                            TAG,
                            "Printer is ready: profile=${profile.name} " +
                                    "width=${profile.printWidth} renderWidth=${profile.renderWidth}",
                        )

                        if (profile.useCreditFlowControl) {
                            val flowChar = service?.getCharacteristic(FLOW_CONTROL_UUID)
                            if (flowChar == null) {
                                Log.w(
                                    TAG,
                                    "M02 Pro FF03 characteristic not found; using timed BLE pacing",
                                )
                            } else {
                                val requested =
                                    enableFlowControlNotifications(g, flowChar)
                                Log.i(
                                    TAG,
                                    "M02 Pro FF03 notification setup requested=$requested",
                                )
                            }
                        }
                    }

                    if (writeChar != null && profile.protocol == Protocol.TINY) {
                        // CCCD writes must be serialized, so finish connecting only
                        // after the last subscription completes (or times out).
                        notifyQueue.clear()
                        if (TINY_SUBSCRIBE_NOTIFICATIONS) {
                            profile.notifyUuids
                                .mapNotNull { service?.getCharacteristic(it) }
                                .forEach { notifyQueue.addLast(it) }
                        }
                        Log.i(TAG, "Subscribing to ${notifyQueue.size} TINY notify characteristics")

                        onNotifySetupDone = { completeConnectOnce(true) }
                        mainHandler.postDelayed(
                            {
                                val pending = onNotifySetupDone
                                onNotifySetupDone = null
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
                    } else {
                        completeConnectOnce(writeChar != null)
                    }
                }

                override fun onDescriptorWrite(
                    g: BluetoothGatt,
                    descriptor: BluetoothGattDescriptor,
                    status: Int,
                ) {
                    if (descriptor.uuid != CCCD_UUID) return
                    val charUuid = descriptor.characteristic.uuid

                    if (charUuid == FLOW_CONTROL_UUID) {
                        flowControlSubscribed = status == BluetoothGatt.GATT_SUCCESS
                        if (flowControlSubscribed) {
                            Log.i(TAG, "M02 Pro FF03 notifications enabled")
                        } else {
                            Log.w(
                                TAG,
                                "M02 Pro FF03 notification setup failed " +
                                        "(status=$status); using timed pacing",
                            )
                        }
                    } else if (
                        profile.protocol == Protocol.TINY &&
                        charUuid in profile.notifyUuids
                    ) {
                        Log.d(TAG, "TINY notify $charUuid subscribed (status=$status)")
                        enableNextNotify(g)
                    }
                }

                @Suppress("DEPRECATION")
                override fun onCharacteristicChanged(
                    g: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                ) {
                    val value = characteristic.value ?: return
                    handleNotification(characteristic.uuid, value)
                }

                override fun onCharacteristicChanged(
                    g: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray,
                ) {
                    handleNotification(characteristic.uuid, value)
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
        if (state != State.READY) return false

        state = State.PRINTING
        val tiny = profile.protocol == Protocol.TINY

        try {
            val linkPayload = (negotiatedMtu - 3).coerceAtLeast(20)
            val conservative =
                profile.name == "M02" ||
                        profile.name == "M02S"

            val effectiveChunkSize =
                if (conservative) {
                    minOf(profile.chunkSize, linkPayload, 205)
                } else {
                    minOf(profile.chunkSize, linkPayload)
                }

            val effectiveChunksPerBurst =
                if (conservative) 1 else profile.chunksPerBurst
            val effectiveBurstDelayMs =
                if (conservative) {
                    maxOf(profile.burstDelayMs, 70L)
                } else {
                    profile.burstDelayMs
                }
            // TINY printers pace themselves via pause/resume, so no extra delay.
            val interChunkDelayMs =
                when {
                    tiny -> 0L
                    conservative -> 12L
                    else -> 8L
                }

            val creditCapable =
                profile.useCreditFlowControl && flowControlSubscribed

            if (profile.useCreditFlowControl) {
                drainFlowCredits()
            }
            if (tiny) {
                tinyJobDone = false
                tinyStatus = null
                synchronized(tinyRx) { tinyRx.reset() }
            }

            var creditModeConfirmed = false
            var creditFallback =
                profile.useCreditFlowControl && !creditCapable

            Log.i(
                TAG,
                "Sending ${commands.size} bytes in chunks of $effectiveChunkSize " +
                        "(mtu=$negotiatedMtu, burst=$effectiveChunksPerBurst, " +
                        "creditFlow=$creditCapable, tiny=$tiny)",
            )

            val chunks =
                commands.asList()
                    .chunked(effectiveChunkSize)
                    .map { it.toByteArray() }

            val sendStart = SystemClock.elapsedRealtime()

            for ((i, chunk) in chunks.withIndex()) {
                if (state == State.DISCONNECTED) {
                    lastError =
                        lastError
                            ?: "Disconnected during BLE transfer (status=$lastGattStatus)"
                    Log.w(TAG, lastError ?: "Disconnected during BLE transfer")
                    return false
                }

                // TINY printers ask the phone to pause/resume via notifications.
                if (tiny && tinyPaused) {
                    val waitStart = SystemClock.elapsedRealtime()
                    Log.d(TAG, "TINY paused before chunk ${i + 1}/${chunks.size}; waiting")
                    val resumed = withTimeoutOrNull(TINY_PAUSE_TIMEOUT_MS) {
                        while (tinyPaused) delay(5)
                        true
                    } ?: false
                    Log.d(
                        TAG,
                        "TINY wait ended after ${SystemClock.elapsedRealtime() - waitStart} ms " +
                                "(resumed=$resumed)",
                    )
                    if (!resumed) {
                        Log.w(TAG, "No TINY resume within ${TINY_PAUSE_TIMEOUT_MS}ms; continuing")
                        tinyPaused = false
                    }
                }

                if (
                    profile.useCreditFlowControl &&
                    creditCapable &&
                    !creditFallback &&
                    i >= PRO_INITIAL_CREDIT_WINDOW
                ) {
                    val timeoutMs =
                        if (creditModeConfirmed) {
                            PRO_CREDIT_TIMEOUT_MS
                        } else {
                            PRO_CREDIT_DETECT_TIMEOUT_MS
                        }

                    val receivedCredit =
                        withTimeoutOrNull(timeoutMs) {
                            flowCredits.receive()
                            true
                        } ?: false

                    if (receivedCredit) {
                        if (!creditModeConfirmed) {
                            Log.i(TAG, "M02 Pro FF03 credit flow confirmed")
                        }
                        creditModeConfirmed = true
                    } else {
                        creditFallback = true
                        Log.w(
                            TAG,
                            "No M02 Pro FF03 credit received within ${timeoutMs}ms; " +
                                    "falling back to ${PRO_FALLBACK_CHUNK_DELAY_MS}ms pacing",
                        )
                    }
                }

                // Write the chunk; retry briefly if the GATT stack reports busy.
                var code = writeChunk(g, ch, chunk)
                var tries = 0
                while (code != BluetoothStatusCodes.SUCCESS && tries < WRITE_RETRY_LIMIT) {
                    delay(WRITE_RETRY_DELAY_MS)
                    code = writeChunk(g, ch, chunk)
                    tries++
                }
                if (code != BluetoothStatusCodes.SUCCESS) {
                    lastError =
                        "BLE write failed at chunk ${i + 1}/${chunks.size} " +
                                "(code=$code, status=$lastGattStatus)"
                    Log.w(TAG, lastError ?: "BLE write failed")
                    state = State.DISCONNECTED
                    return false
                }

                if (profile.useCreditFlowControl) {
                    when {
                        creditFallback -> {
                            delay(PRO_FALLBACK_CHUNK_DELAY_MS)
                        }

                        i < PRO_INITIAL_CREDIT_WINDOW - 1 -> {
                            delay(PRO_INITIAL_WINDOW_DELAY_MS)
                        }

                        else -> {
                            // In confirmed credit mode, the next iteration waits
                            // for FF03 instead of sleeping here.
                        }
                    }
                } else {
                    if ((i + 1) % effectiveChunksPerBurst == 0) {
                        delay(effectiveBurstDelayMs)
                    }
                    if (interChunkDelayMs > 0) {
                        delay(interChunkDelayMs)
                    }
                }
            }

            Log.i(
                TAG,
                "All ${chunks.size} chunks written in " +
                        "${SystemClock.elapsedRealtime() - sendStart} ms",
            )

            if (tiny && TINY_SUBSCRIBE_NOTIFICATIONS) {
                // The printer answers the final A3 state query when the job is done.
                val done = withTimeoutOrNull(TINY_DONE_TIMEOUT_MS) {
                    while (!tinyJobDone) delay(50)
                    true
                } ?: false
                Log.i(
                    TAG,
                    if (done) "Printer reported job done" else "No completion reply; continuing",
                )

                // A non-zero first status byte is treated as a failed print.
                // Calibrate this against real faults (cover open, no paper).
                val status = tinyStatus
                if (done && status != null && status.isNotEmpty() && status[0] != 0.toByte()) {
                    val hex = status.joinToString(" ") { "%02x".format(it) }
                    lastError = "Printer needs attention (status $hex). Check paper and cover, then print again."
                    Log.w(TAG, lastError ?: "TINY status fault")
                    state = State.READY
                    return false
                }
            } else {
                delay(2000)
            }

            state = State.READY
            lastError = null
            return true
        } catch (e: Exception) {
            lastError =
                "BLE write threw ${e.javaClass.simpleName}" +
                        (e.message?.let { ": $it" } ?: "")
            Log.e(TAG, lastError ?: "BLE write exception", e)
            state = State.DISCONNECTED
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
        flowControlSubscribed = false
        drainFlowCredits()
        resetTinyState()
        negotiatedMtu = 23
        state = State.DISCONNECTED
    }

    // ---- TINY helpers -------------------------------------------------------

    private fun resetTinyState() {
        notifyQueue.clear()
        onNotifySetupDone = null
        tinyPaused = false
        tinyJobDone = false
        tinyStatus = null
        synchronized(tinyRx) { tinyRx.reset() }
    }

    private fun enableNextNotify(g: BluetoothGatt) {
        while (true) {
            val ch = notifyQueue.removeFirstOrNull()
            if (ch == null) {
                val done = onNotifySetupDone
                onNotifySetupDone = null
                done?.invoke()
                return
            }
            if (enableNotify(g, ch)) return // wait for onDescriptorWrite
            Log.w(TAG, "Could not subscribe to ${ch.uuid}; skipping")
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

    private fun handleNotification(uuid: UUID, value: ByteArray) {
        when {
            uuid == FLOW_CONTROL_UUID -> handleFlowControlNotification(uuid, value)
            profile.protocol == Protocol.TINY -> onTinyNotify(uuid, value)
        }
    }

    private fun onTinyNotify(uuid: UUID, value: ByteArray) {
        Log.d(TAG, "TINY notify $uuid: ${value.joinToString("") { "%02x".format(it) }}")

        // Reply to the final A3 state query: the printer has processed the job.
        if (
            value.size >= 6 &&
            value[0] == 0x51.toByte() &&
            value[1] == 0x78.toByte() &&
            value[2] == 0xA3.toByte()
        ) {
            val len = (value[4].toInt() and 0xFF) or ((value[5].toInt() and 0xFF) shl 8)
            if (value.size >= 6 + len) {
                val payload = value.copyOfRange(6, 6 + len)
                tinyStatus = payload
                Log.i(
                    TAG,
                    "TINY status payload: " +
                            payload.joinToString(" ") { "%02x".format(it) },
                )
            }
            tinyJobDone = true
        }

        synchronized(tinyRx) {
            tinyRx.write(value)
            val buf = tinyRx.toByteArray()
            val pause = buf.lastIndexOfSub(TINY_PAUSE)
            val resume = buf.lastIndexOfSub(TINY_RESUME)
            if (pause >= 0 || resume >= 0) {
                tinyPaused = pause > resume
                Log.d(TAG, "TINY flow control: paused=$tinyPaused")
                tinyRx.reset()
            } else if (tinyRx.size() > 256) {
                tinyRx.reset()
            }
        }
    }

    private fun ByteArray.lastIndexOfSub(sub: ByteArray): Int {
        if (sub.isEmpty() || size < sub.size) return -1
        for (start in size - sub.size downTo 0) {
            var match = true
            for (j in sub.indices) {
                if (this[start + j] != sub[j]) {
                    match = false
                    break
                }
            }
            if (match) return start
        }
        return -1
    }

    // ---- Original helpers (unchanged) ----------------------------------------

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun enableFlowControlNotifications(
        g: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
    ): Boolean {
        if (
            characteristic.properties and
            BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0
        ) {
            Log.w(TAG, "FF03 exists but does not advertise NOTIFY")
            return false
        }

        val localEnabled =
            try {
                g.setCharacteristicNotification(characteristic, true)
            } catch (_: SecurityException) {
                false
            }
        if (!localEnabled) {
            Log.w(TAG, "Unable to enable local FF03 notifications")
            return false
        }

        val descriptor =
            characteristic.getDescriptor(CCCD_UUID)
                ?: run {
                    Log.w(TAG, "FF03 CCCD 0x2902 not found")
                    return false
                }

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(
                    descriptor,
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE,
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(descriptor)
            }
        } catch (_: SecurityException) {
            false
        }
    }

    private fun handleFlowControlNotification(
        uuid: UUID,
        value: ByteArray,
    ) {
        if (uuid != FLOW_CONTROL_UUID) return

        if (
            value.size >= 2 &&
            value[0] == 0x01.toByte() &&
            value[1] == 0x01.toByte()
        ) {
            flowCredits.trySend(Unit)
            return
        }

        Log.d(
            TAG,
            "Unexpected FF03 notification: " +
                    value.joinToString(" ") {
                        "%02x".format(it.toInt() and 0xff)
                    },
        )
    }

    private fun drainFlowCredits() {
        while (flowCredits.tryReceive().isSuccess) {
            // Drain queued credits from a previous print/job.
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

    private fun matchesPrinterName(name: String): Boolean {
        val upper = name.uppercase()
        return NAME_PREFIXES.any { upper.startsWith(it.uppercase()) } ||
                NAME_CONTAINS.any { upper.contains(it) }
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
                n != null && matchesPrinterName(n)
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

    private fun detectProfile(name: String): Profile {
        val upper = name.uppercase()
        return when {
            upper.startsWith("X6H") -> PROFILE_X6H
            upper.contains("M04") -> PROFILE_M04S
            upper.contains("M02 PRO") -> PROFILE_M02_PRO
            upper.contains("M02S") -> PROFILE_M02S
            upper.contains("M02") || upper.contains("T02") -> PROFILE_M02
            else -> PROFILE_M02S
        }
    }
}