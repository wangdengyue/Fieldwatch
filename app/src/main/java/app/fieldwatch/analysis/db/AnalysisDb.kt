package app.fieldwatch.analysis.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        DeviceEntity::class,
        PresenceEntity::class,
        BaselineEntity::class,
        AlertEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AnalysisDb : RoomDatabase() {
    abstract fun devices(): DeviceDao
    abstract fun presence(): PresenceDao
    abstract fun baseline(): BaselineDao
    abstract fun alerts(): AlertDao

    companion object {
        @Volatile private var instance: AnalysisDb? = null

        fun get(context: Context): AnalysisDb =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AnalysisDb::class.java,
                    "fieldwatch-analysis.db",
                ).build().also { instance = it }
            }
    }
}
