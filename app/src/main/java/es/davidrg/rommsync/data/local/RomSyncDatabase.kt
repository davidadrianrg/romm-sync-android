package es.davidrg.rommsync.data.local

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import android.content.Context
import es.davidrg.rommsync.data.local.dao.PlatformDao
import es.davidrg.rommsync.data.local.dao.RomDao
import es.davidrg.rommsync.data.local.entity.DownloadedRomEntity
import es.davidrg.rommsync.data.local.entity.PlatformEntity

@Database(
    entities = [
        PlatformEntity::class,
        DownloadedRomEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class RomSyncDatabase : RoomDatabase() {

    abstract fun platformDao(): PlatformDao
    abstract fun romDao(): RomDao

    companion object {
        @Volatile
        private var INSTANCE: RomSyncDatabase? = null

        fun getDatabase(context: Context): RomSyncDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    RomSyncDatabase::class.java,
                    "romsync.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
