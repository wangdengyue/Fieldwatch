package app.fieldwatch.radio

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Active GATT probe: connect to a BLE device and read its public profile —
 * full service tree, Device Information (0x180A), Battery (0x180F), and a
 * short listen window on notifiable characteristics. This is the one place
 * besides TrackerTone where Fieldwatch stops being passive: the device can
 * see this phone's address for the duration of the connection.
 *
 * Reads are serialized (Android allows one outstanding GATT operation), with
 * caps so a chatty device cannot hold the radio forever. Connection refusal
 * is reported as intelligence of its own: a device that rejects a connect is
 * usually in use by its owner, bonded elsewhere, or just left range.
 */
class BleProbe(private val context: Context) {

    data class CharReport(
        val uuid: String,
        val name: String?,
        val properties: List<String>,
        /** Hex of the read value; null when unreadable or not read. */
        val valueHex: String?,
        /** UTF-8 decode when the value is printable; battery level shows as a percent. */
        val valueText: String?,
        /** Why a read failed (GATT status or "not read — cap reached"). */
        val readError: String?,
    )

    data class ServiceReport(
        val uuid: String,
        val name: String?,
        val chars: List<CharReport>,
    )

    data class Notification(
        val uuid: String,
        val at: Long,
        val hex: String,
    )

    data class Report(
        val mac: String,
        val startedAt: Long,
        val elapsedMs: Long,
        /** null on success. Refusal/timeout text otherwise — still intelligence. */
        val failure: String?,
        val services: List<ServiceReport>,
        val batteryPercent: Int?,
        val notifications: List<Notification>,
    ) {
        val connected: Boolean get() = failure == null
    }

    @SuppressLint("MissingPermission")
    suspend fun probe(mac: String, listenMs: Long = 8_000L): Report {
        val startedAt = System.currentTimeMillis()
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val remote = runCatching { manager.adapter?.getRemoteDevice(mac) }.getOrNull()
            ?: return Report(mac, startedAt, 0, "Bluetooth is off.", emptyList(), null, emptyList())

        val session = Session(context, remote)
        val report = withTimeoutOrNull(TOTAL_TIMEOUT_MS) {
            try {
                val fail = session.connect()
                if (fail != null) {
                    return@withTimeoutOrNull Report(mac, startedAt, elapsed(startedAt), fail, emptyList(), null, emptyList())
                }
                if (!session.discover()) {
                    return@withTimeoutOrNull Report(
                        mac, startedAt, elapsed(startedAt),
                        "Connected, but service discovery failed.", emptyList(), null, emptyList(),
                    )
                }
                val services = session.readTree()
                session.armNotifications()
                if (session.notifyCount > 0) delay(listenMs)
                session.disconnect()
                val battery = services.asSequence()
                    .flatMap { it.chars }
                    .firstOrNull { it.uuid == "2A19" }
                    ?.valueHex?.takeIf { it.length >= 2 }
                    ?.substring(0, 2)?.toIntOrNull(16)
                Report(
                    mac, startedAt, elapsed(startedAt), null,
                    services, battery, session.notifications.toList(),
                )
            } finally {
                session.close()
            }
        }
        return report ?: Report(
            mac, startedAt, elapsed(startedAt),
            "Timed out — the device may have left range mid-probe.",
            session.servicesSnapshot(), null, session.notifications.toList(),
        )
    }

    private fun elapsed(since: Long) = System.currentTimeMillis() - since

    // ------------------------------------------------------------------
    // GATT session: one continuation for the single outstanding operation.
    // ------------------------------------------------------------------

    private inner class Session(context: Context, remote: android.bluetooth.BluetoothDevice) {
        val notifications = mutableListOf<Notification>()
        var notifyCount = 0
            private set

        private var op: CancellableContinuation<OpResult>? = null
        private var conn: CancellableContinuation<String?>? = null
        private var readBudget = MAX_READS

        private val gatt: BluetoothGatt

        private fun completeOp(r: OpResult) {
            val c = op
            op = null
            if (c != null && c.isActive) c.resume(r)
        }

        private fun completeConn(failure: String?) {
            val c = conn
            conn = null
            if (c != null && c.isActive) c.resume(failure)
        }

        private val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                when {
                    status != BluetoothGatt.GATT_SUCCESS ->
                        completeConn("Connect refused (status $status) — usually in use by its owner, bonded elsewhere, or out of range.")
                    newState == BluetoothProfile.STATE_CONNECTED -> completeConn(null)
                    newState == BluetoothProfile.STATE_DISCONNECTED -> {
                        completeConn("Disconnected during connect.")
                        completeOp(OpResult.Fail("Link dropped."))
                    }
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                completeOp(if (status == BluetoothGatt.GATT_SUCCESS) OpResult.Ok else OpResult.Fail("status $status"))
            }

            @Suppress("DEPRECATION")
            override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
                if (Build.VERSION.SDK_INT < 33) {
                    val v = if (status == BluetoothGatt.GATT_SUCCESS) c.value else null
                    completeOp(if (v != null) OpResult.Value(v) else OpResult.Fail("status $status"))
                }
            }

            override fun onCharacteristicRead(
                g: BluetoothGatt,
                c: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int,
            ) {
                completeOp(
                    if (status == BluetoothGatt.GATT_SUCCESS) OpResult.Value(value)
                    else OpResult.Fail("status $status"),
                )
            }

            override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
                completeOp(if (status == BluetoothGatt.GATT_SUCCESS) OpResult.Ok else OpResult.Fail("status $status"))
            }

            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
                if (Build.VERSION.SDK_INT < 33) {
                    val v = c.value ?: return
                    recordNotification(c.uuid, v)
                }
            }

            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
                recordNotification(c.uuid, value)
            }
        }

        init {
            gatt = remote.connectGatt(context, false, cb, 2) // TRANSPORT_LE
        }

        private fun recordNotification(uuid: UUID, value: ByteArray) {
            if (notifications.size >= MAX_NOTIFICATIONS) return
            notifications += Notification(shortId(uuid), System.currentTimeMillis(), value.toHex())
        }

        suspend fun connect(): String? = suspendCancellableCoroutine { cont ->
            conn = cont
            cont.invokeOnCancellation { conn = null }
        }

        suspend fun discover(): Boolean =
            runOp { gatt.discoverServices() } is OpResult.Ok

        private suspend fun runOp(start: () -> Boolean): OpResult {
            return suspendCancellableCoroutine { cont ->
                op = cont
                cont.invokeOnCancellation { op = null }
                val ok = runCatching { start() }.getOrDefault(false)
                if (!ok && cont.isActive) {
                    op = null
                    cont.resume(OpResult.Fail("request rejected by the stack"))
                }
            }
        }

        suspend fun readTree(): List<ServiceReport> {
            val out = ArrayList<ServiceReport>()
            for (service in gatt.services.orEmpty()) {
                val reports = ArrayList<CharReport>()
                for (c in service.characteristics.orEmpty()) {
                    val props = propNames(c.properties)
                    val id = shortId(c.uuid)
                    var valueHex: String? = null
                    var valueText: String? = null
                    var readError: String? = null
                    if (c.properties and BluetoothGattCharacteristic.PROPERTY_READ == 0) {
                        readError = null // simply not readable; leave value null without noise
                    } else if (readBudget <= 0) {
                        readError = "not read — cap reached"
                    } else {
                        readBudget--
                        when (val r = runOp { gatt.readCharacteristic(c) }) {
                            is OpResult.Value -> {
                                valueHex = r.bytes.toHex()
                                valueText = decodeText(id, r.bytes)
                            }
                            is OpResult.Fail -> readError = "read failed (${r.why})"
                            else -> readError = "read failed"
                        }
                    }
                    reports += CharReport(id, charName(c.uuid), props, valueHex, valueText, readError)
                }
                out += ServiceReport(shortId(service.uuid), serviceName(service.uuid), reports)
            }
            return out
        }

        /** Enable notifications/indications on up to [MAX_NOTIFY] characteristics. */
        suspend fun armNotifications() {
            var armed = 0
            outer@ for (service in gatt.services.orEmpty()) {
                for (c in service.characteristics.orEmpty()) {
                    if (armed >= MAX_NOTIFY) break@outer
                    val notifiable = c.properties and (
                        BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                            BluetoothGattCharacteristic.PROPERTY_INDICATE
                        ) != 0
                    if (!notifiable) continue
                    if (!gatt.setCharacteristicNotification(c, true)) continue
                    val cccd = c.getDescriptor(CCCD_UUID) ?: continue
                    val enable = if (c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) {
                        BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                    } else {
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    }
                    val ok = runOp {
                        if (Build.VERSION.SDK_INT >= 33) {
                            gatt.writeDescriptor(cccd, enable) == BluetoothGatt.GATT_SUCCESS
                        } else {
                            @Suppress("DEPRECATION")
                            cccd.value = enable
                            @Suppress("DEPRECATION")
                            gatt.writeDescriptor(cccd)
                        }
                    } is OpResult.Ok
                    if (ok) armed++
                }
            }
            notifyCount = armed
        }

        @SuppressLint("MissingPermission")
        fun disconnect() {
            runCatching { gatt.disconnect() }
        }

        fun close() {
            runCatching { gatt.disconnect(); gatt.close() }
        }

        /** Partial tree for the timeout path. */
        fun servicesSnapshot(): List<ServiceReport> =
            runCatching {
                gatt.services.orEmpty().map { s ->
                    ServiceReport(
                        shortId(s.uuid), serviceName(s.uuid),
                        s.characteristics.orEmpty().map { c ->
                            CharReport(shortId(c.uuid), charName(c.uuid), propNames(c.properties), null, null, null)
                        },
                    )
                }
            }.getOrDefault(emptyList())
    }

    private sealed interface OpResult {
        data object Ok : OpResult
        data class Value(val bytes: ByteArray) : OpResult
        data class Fail(val why: String) : OpResult
    }

    companion object {
        private const val TOTAL_TIMEOUT_MS = 25_000L
        private const val MAX_READS = 40
        private const val MAX_NOTIFY = 8
        private const val MAX_NOTIFICATIONS = 200
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
        private val SIG_BASE = Regex("^0000([0-9A-Fa-f]{4})-0000-1000-8000-00805F9B34FB$", RegexOption.IGNORE_CASE)

        /** "180A"-style short id for SIG-base UUIDs, uppercase full id otherwise. */
        fun shortId(uuid: UUID): String {
            val s = uuid.toString()
            return SIG_BASE.find(s)?.groupValues?.get(1)?.uppercase() ?: s.uppercase()
        }

        fun serviceName(uuid: UUID): String? = SERVICE_NAMES[shortId(uuid)]
        fun charName(uuid: UUID): String? = CHAR_NAMES[shortId(uuid)]

        private fun propNames(props: Int): List<String> {
            val out = ArrayList<String>(4)
            if (props and BluetoothGattCharacteristic.PROPERTY_BROADCAST != 0) out += "broadcast"
            if (props and BluetoothGattCharacteristic.PROPERTY_READ != 0) out += "read"
            if (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) out += "write-no-rsp"
            if (props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) out += "write"
            if (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) out += "notify"
            if (props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) out += "indicate"
            return out
        }

        /** Printable UTF-8 for text-ish values; battery level (2A19) as a percent. */
        private fun decodeText(shortId: String, bytes: ByteArray): String? {
            if (shortId == "2A19" && bytes.isNotEmpty()) return "${bytes[0].toInt() and 0xFF}%"
            if (bytes.isEmpty() || bytes.size > 64) return null
            val printable = bytes.all {
                (it >= 0x20 && it <= 0x7E) || it == 0x09.toByte() || it == 0x0A.toByte() || it == 0x0D.toByte()
            }
            return if (printable) String(bytes, Charsets.UTF_8).trim() else null
        }

        private fun ByteArray.toHex(): String =
            joinToString("") { "%02X".format(it) }

        private val SERVICE_NAMES = mapOf(
            "1800" to "Generic Access",
            "1801" to "Generic Attribute",
            "1802" to "Immediate Alert",
            "1803" to "Link Loss",
            "1804" to "Tx Power",
            "1805" to "Current Time",
            "1808" to "Glucose",
            "180A" to "Device Information",
            "180D" to "Heart Rate",
            "180E" to "Phone Alert Status",
            "180F" to "Battery Service",
            "1810" to "Blood Pressure",
            "1812" to "Human Interface Device",
            "1816" to "Cycling Speed and Cadence",
            "1819" to "Location and Navigation",
            "181A" to "Environmental Sensing",
            "181C" to "User Data",
            "181D" to "Weight Scale",
            "1822" to "Pulse Oximeter",
            "1826" to "Fitness Machine",
            "FD43" to "Apple Find My (offline finding)",
            "FD44" to "Apple Find My",
            "FCB2" to "DULT unwanted-tracking protection",
            "FE59" to "Nordic Secure DFU",
            "FE9F" to "Google Fast Pair (GFPS)",
            "FEAA" to "Eddystone",
            "FE2C" to "Google Fast Pair",
            "7DFC9000-7D1C-4951-86AA-8D9728F8D66C" to "AirTag / Find My accessory",
            "15190001-12F4-C226-88ED-2AC5579F2A85" to "DULT (SmartTag/Chipolo)",
        )

        private val CHAR_NAMES = mapOf(
            "2A00" to "Device Name",
            "2A01" to "Appearance",
            "2A04" to "Peripheral Preferred Connection Parameters",
            "2A05" to "Service Changed",
            "2A06" to "Alert Level",
            "2A19" to "Battery Level",
            "2A23" to "System ID",
            "2A24" to "Model Number",
            "2A25" to "Serial Number",
            "2A26" to "Firmware Revision",
            "2A27" to "Hardware Revision",
            "2A28" to "Software Revision",
            "2A29" to "Manufacturer Name",
            "2A2B" to "Current Time",
            "2A37" to "Heart Rate Measurement",
            "2A38" to "Body Sensor Location",
            "2A46" to "New Alert",
            "2A50" to "PnP ID",
            "2A67" to "Location and Speed",
            "2A6E" to "Temperature",
            "2A6F" to "Humidity",
            "2A7D" to "Descriptor Value Changed",
        )
    }
}
