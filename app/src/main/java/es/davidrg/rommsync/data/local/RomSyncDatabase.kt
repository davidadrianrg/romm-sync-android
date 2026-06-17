package es.davidrg.rommsync.data.local

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    version = 2,
    exportSchema = true,
)
abstract class RomSyncDatabase : RoomDatabase() {

    abstract fun platformDao(): PlatformDao
    abstract fun romDao(): RomDao

    companion object {
        /**
         * Migration 1 → 2: añade un índice sobre `platformId` en `downloaded_roms`
         * para acelerar las queries filtradas por plataforma
         * (p. ej. [RomDao.getDownloadedRomsForPlatform]).
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_downloaded_roms_platformId ON downloaded_roms(platformId)")
            }
        }

        @Volatile
        private var INSTANCE: RomSyncDatabase? = null

        fun getDatabase(context: Context): RomSyncDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    RomSyncDatabase::class.java,
                    "romsync.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    // Solo destructivo en downgrade: nunca en upgrade, donde
                    // tenemos migraciones explícitas que preservan los datos.
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
