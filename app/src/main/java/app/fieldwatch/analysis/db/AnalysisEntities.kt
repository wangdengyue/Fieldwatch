package app.fieldwatch.analysis.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Rotation class of a tracked radio. */
enum class RotClass {
    /** Public / factory MAC that does not change. */
    FIXED,

    /** Locally-administered MAC that keeps rotating. */
    ROTATING,

    /** Rotating stack currently holding one address (e.g. DULT separated mode). */
    SEMI_FIXED,

    UNKNOWN,
}

/**
 * Long-lived logical radio. For FIXED/SEMI_FIXED radios [deviceKey] is mac-based;
 * for ROTATING groups it is stableKey-based (M3).
 */
@Entity(
    tableName = "analysis_device",
    indices = [Index("stableKey"), Index("lastSeen")],
)
data class DeviceEntity(
    @PrimaryKey val deviceKey: String,
    val kind: String,
    val oui: String,
    val vendor: String?,
    val name: String,
    /** Comma-joined fleet ids at last match. */
    val fleets: String,
    val rotClass: String,
    val stableKey: String,
    val firstSeen: Long,
    val lastSeen: Long,
    val hitCount: Long,
    val sessionCount: Int,
    val lastMac: String,
    /** Recent MACs of a rotating group, comma-joined, newest first. Empty for MAC-keyed rows. */
    val recentMacs: String = "",
)

/** One continuous presence span of a logical radio (closed after a silence gap). */
@Entity(
    tableName = "analysis_presence",
    indices = [Index("deviceKey"), Index("startTs"), Index("geohash")],
)
data class PresenceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceKey: String,
    val startTs: Long,
    /** 0 while the span is open. */
    val endTs: Long,
    val rssiAvg: Int,
    val rssiMin: Int,
    val rssiMax: Int,
    val samples: Int,
    /** geohash-7 of the phone at hear-time; empty when no GPS. */
    val geohash: String,
    val lat: Double?,
    val lon: Double?,
)

/** Learned RF baseline for one (place, hour-of-day) bucket. */
@Entity(tableName = "analysis_baseline")
data class BaselineEntity(
    /** "$geohash|$hourOfDay". */
    @PrimaryKey val key: String,
    val geohash: String,
    val hourOfDay: Int,
    /** Sessions observed in this bucket. Alerts arm at >= 3. */
    val visits: Int,
    val deviceCountMean: Double,
    /** Welford M2 for device count variance. */
    val deviceCountM2: Double,
    /** Comma-joined OUIs seen often enough to be normal here. */
    val ouis: String,
    /** Comma-joined fleet kinds (classes) normal here. */
    val fleetKinds: String,
    val updatedAt: Long,
)

/** One analysis alert (new device, new OUI, rotating cluster, ...). */
@Entity(
    tableName = "analysis_alert",
    indices = [Index("ts"), Index("deviceKey")],
)
data class AlertEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    /** NEW_DEVICE | NEW_OUI | ROTATING_CLUSTER | KNOWN_DEVICE_NEW_PLACE */
    val type: String,
    val deviceKey: String?,
    val title: String,
    val detail: String,
    val geohash: String,
    val read: Boolean = false,
)
