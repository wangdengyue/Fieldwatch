package app.fieldwatch.analysis.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DeviceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(device: DeviceEntity)

    @Query("SELECT * FROM analysis_device WHERE deviceKey = :key")
    suspend fun find(key: String): DeviceEntity?

    @Query("SELECT * FROM analysis_device WHERE deviceKey IN (:keys)")
    suspend fun findByKeys(keys: List<String>): List<DeviceEntity>

    @Query("SELECT * FROM analysis_device ORDER BY lastSeen DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<DeviceEntity>

    @Query("SELECT COUNT(*) FROM analysis_device")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM analysis_device WHERE rotClass = 'ROTATING'")
    suspend fun rotatingCount(): Int

    @Query("DELETE FROM analysis_device WHERE lastSeen < :before")
    suspend fun pruneBefore(before: Long): Int
}

@Dao
interface PresenceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(span: PresenceEntity): Long

    @Query("SELECT * FROM analysis_presence WHERE deviceKey = :key AND endTs = 0 ORDER BY startTs DESC LIMIT 1")
    suspend fun openSpan(key: String): PresenceEntity?

    @Query("SELECT * FROM analysis_presence WHERE endTs = 0 AND deviceKey IN (:keys)")
    suspend fun openSpansFor(keys: List<String>): List<PresenceEntity>

    @Query("SELECT * FROM analysis_presence WHERE deviceKey = :key ORDER BY startTs DESC LIMIT :limit")
    suspend fun spansFor(key: String, limit: Int): List<PresenceEntity>

    @Query("SELECT COUNT(DISTINCT geohash) FROM analysis_presence WHERE deviceKey = :key AND geohash != ''")
    suspend fun placeCount(key: String): Int

    @Query("SELECT COUNT(*) FROM analysis_presence")
    suspend fun count(): Int

    @Query("DELETE FROM analysis_presence WHERE endTs != 0 AND endTs < :before")
    suspend fun pruneBefore(before: Long): Int
}

@Dao
interface BaselineDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(bucket: BaselineEntity)

    @Query("SELECT * FROM analysis_baseline WHERE `key` = :key")
    suspend fun find(key: String): BaselineEntity?

    @Query("SELECT COUNT(*) FROM analysis_baseline")
    suspend fun count(): Int
}

@Dao
interface AlertDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(alert: AlertEntity): Long

    @Query("SELECT COUNT(*) FROM analysis_alert")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM analysis_alert WHERE `read` = 0")
    suspend fun unreadCount(): Int

    @Query("SELECT * FROM analysis_alert ORDER BY ts DESC LIMIT :limit")
    suspend fun latest(limit: Int): List<AlertEntity>

    @Query("UPDATE analysis_alert SET `read` = 1 WHERE id = :id")
    suspend fun markRead(id: Long)

    @Query("DELETE FROM analysis_alert WHERE ts < :before")
    suspend fun pruneBefore(before: Long): Int
}
