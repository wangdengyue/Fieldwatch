package app.fieldwatch.analysis

import app.fieldwatch.domain.Sighting
import java.security.MessageDigest

/**
 * Content fingerprint of a radio. The MAC rotates, the content does not:
 * two advertisements with the same stableKey are the same model of thing,
 * often the same physical radio mid-rotation.
 *
 * Structure: kind + OUI-or-companyIds + advertised name + service UUIDs +
 * manufacturer-data shape (company, payload length, type byte) + vendor IEs.
 */
object Fingerprint {

    fun stableKey(s: Sighting): String = sha1(structural(s)).take(16)

    /** Canonical string the hash is computed over. Exposed for tests. */
    fun structural(s: Sighting): String {
        val ids = s.facts.mfgRecords.map { it.companyId }.toSortedSet()
        val ouiPart = when {
            // A randomized address is locally administered: its "OUI" is dice
            // roll, not a vendor — never fingerprint on it.
            !s.randomized && s.oui.isNotBlank() -> "oui:${s.oui.uppercase()}"
            ids.isNotEmpty() -> "cid:" + ids.joinToString("+") { "%04X".format(it) }
            s.manufacturerId != null -> "cid:%04X".format(s.manufacturerId)
            else -> "nooui"
        }
        val namePart = s.name.trim().ifBlank { "-" }
        val svcPart = s.serviceUuids
            .map { shortUuid(it) }
            .filter { it.isNotBlank() }
            .toSortedSet()
            .joinToString("+")
            .ifBlank { "-" }
        val mfgPart = mfgShape(s)
        val iePart = s.vendorIeOuis
            .map { it.uppercase() }
            .toSortedSet()
            .joinToString("+")
            .ifBlank { "-" }
        return listOf(s.kind.name, ouiPart, namePart, svcPart, mfgPart, iePart).joinToString("|")
    }

    /** company:length:typeByte per record — the payload *shape*, not the bytes. */
    private fun mfgShape(s: Sighting): String {
        val records = s.facts.mfgRecords.map { rec ->
            val hex = rec.dataHex.filter { it.isLetterOrDigit() }
            val len = hex.length / 2
            val type = if (hex.length >= 2) hex.take(2).uppercase() else "--"
            "%04X:%d:%s".format(rec.companyId, len, type)
        }
        if (records.isEmpty() && s.manufacturerId != null) {
            val hex = s.manufacturerDataHex.filter { it.isLetterOrDigit() }
            val len = hex.length / 2
            val type = if (hex.length >= 2) hex.take(2).uppercase() else "--"
            return "%04X:%d:%s".format(s.manufacturerId, len, type)
        }
        return records.toSortedSet().joinToString("+").ifBlank { "-" }
    }

    private fun shortUuid(uuid: String): String {
        val hex = uuid.filter { it.isLetterOrDigit() }.uppercase()
        return when {
            hex.length == 4 -> hex
            hex.length == 32 && hex.startsWith("0000") -> hex.substring(4, 8)
            else -> hex
        }
    }

    private fun sha1(text: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
