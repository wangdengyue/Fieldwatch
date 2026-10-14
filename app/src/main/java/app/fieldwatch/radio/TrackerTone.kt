package app.fieldwatch.radio

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import app.fieldwatch.domain.Sighting
import java.util.UUID
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Ask a tracker to play its sound. This is the one place Fieldwatch stops
 * being passive: it opens a BLE GATT connection to the device and writes the
 * vendor's sound-trigger opcode. The device sees your phone's address.
 *
 * Protocols (from the public DULT spec and community research):
 *  - AirTag:   service 7DFC9000-…, characteristic 7DFC9001-…, write 0xAF
 *  - Find My accessories (0xFD44): characteristic 4F860003-…, write 01 00 03
 *  - DULT (0xFCB2: SmartTag/Chipolo/…): service 15190001-…, char 8E0C0001-…, write 00 03
 */
class TrackerTone(private val context: Context) {

    sealed interface Result {
        data object Playing : Result
        data class Failed(val why: String) : Result
    }

    private enum class Proto(val service: UUID, val characteristic: UUID, val opcode: ByteArray) {
        AIRTAG(
            UUID.fromString("7DFC9000-7D1C-4951-86AA-8D9728F8D66C"),
            UUID.fromString("7DFC9001-7D1C-4951-86AA-8D9728F8D66C"),
            byteArrayOf(0xAF.toByte()),
        ),
        FINDMY(
            UUID.fromString("0000FD44-0000-1000-8000-00805F9B34FB"),
            UUID.fromString("4F860003-943B-49EF-BED4-2F730304427A"),
            byteArrayOf(0x01, 0x00, 0x03),
        ),
        DULT(
            UUID.fromString("15190001-12F4-C226-88ED-2AC5579F2A85"),
            UUID.fromString("8E0C0001-1D68-FB92-BF61-48377421680E"),
            byteArrayOf(0x00, 0x03),
        ),
    }

    companion object {
        /** Tracker-ish radios we know how to ring. */
        fun supports(device: Sighting): Boolean {
            if (device.kind != app.fieldwatch.domain.RadioKind.BLE) return false
            val svcs = device.serviceUuids.map { it.uppercase() }
            if (svcs.any { it.contains("FCB2") || it.contains("FD44") }) return true
            if (device.fleetIds.any { id ->
                    listOf("airtag", "smarttag", "findmy", "find-my", "chipolo", "pebblebee", "tile")
                        .any { key -> id.contains(key) }
                }
            ) return true
            // Apple Find My frame: company 0x004C, payload type 0x10 or 0x12.
            return device.facts.mfgRecords.any {
                it.companyId == 0x004C &&
                    (it.dataHex.startsWith("10", true) || it.dataHex.startsWith("12", true))
            }
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun play(device: Sighting): Result {
        val svcs = device.serviceUuids.map { it.uppercase() }
        val proto = when {
            svcs.any { it.contains("FCB2") } -> Proto.DULT
            svcs.any { it.contains("FD44") } -> Proto.FINDMY
            else -> Proto.AIRTAG
        }
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val remote = runCatching { manager.adapter?.getRemoteDevice(device.mac) }.getOrNull()
            ?: return Result.Failed("Bluetooth is off.")

        return withTimeoutOrNull(12_000L) {
            suspendCancellableCoroutine { cont ->
                var gatt: BluetoothGatt? = null
                fun finish(r: Result) {
                    if (cont.isActive) cont.resume(r)
                    runCatching { gatt?.disconnect(); gatt?.close() }
                }
                val cb = object : BluetoothGattCallback() {
                    override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                        when {
                            status != BluetoothGatt.GATT_SUCCESS ->
                                finish(Result.Failed("Connect failed (status $status)."))
                            newState == BluetoothProfile.STATE_CONNECTED ->
                                runCatching { g.discoverServices() }
                            newState == BluetoothProfile.STATE_DISCONNECTED ->
                                finish(Result.Failed("Disconnected before the sound command."))
                        }
                    }

                    override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                        gatt = g
                        val char = g.getService(proto.service)?.getCharacteristic(proto.characteristic)
                            ?: return finish(Result.Failed("Sound service not on this device."))
                        val ok = if (Build.VERSION.SDK_INT >= 33) {
                            g.writeCharacteristic(
                                char,
                                proto.opcode,
                                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                            ) == BluetoothGatt.GATT_SUCCESS
                        } else {
                            @Suppress("DEPRECATION")
                            char.value = proto.opcode
                            @Suppress("DEPRECATION")
                            g.writeCharacteristic(char)
                        }
                        if (!ok) finish(Result.Failed("Write refused."))
                    }

                    override fun onCharacteristicWrite(
                        g: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic,
                        status: Int,
                    ) {
                        finish(
                            if (status == BluetoothGatt.GATT_SUCCESS) Result.Playing
                            else Result.Failed("Write failed (status $status)."),
                        )
                    }
                }
                gatt = remote.connectGatt(context, false, cb, 2) // TRANSPORT_LE
                cont.invokeOnCancellation { runCatching { gatt?.disconnect(); gatt?.close() } }
            }
        } ?: Result.Failed("Timed out — the tracker may have left range.")
    }
}
