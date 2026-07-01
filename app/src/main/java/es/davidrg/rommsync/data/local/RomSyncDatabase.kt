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
    version = 5,
    exportSchema = true,
)
abstract class RomSyncDatabase : RoomDatabase() {

    abstract fun platformDao(): PlatformDao
    abstract fun romDao(): RomDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_downloaded_roms_platformId ON downloaded_roms(platformId)")
            }
        }

        /**
         * Migration 2 → 3: añade columnas de configuración de sync por plataforma.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE platforms ADD COLUMN emulatorId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE platforms ADD COLUMN savesPathOverride TEXT DEFAULT NULL")
            }
        }

        /**
         * Migration 3 → 4: añade columnas de configuración de sync por juego.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloaded_roms ADD COLUMN savesPathOverride TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE downloaded_roms ADD COLUMN excludedFromSync INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Migration 4 → 5: añade aspect_ratio a plataformas.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE platforms ADD COLUMN aspectRatio TEXT DEFAULT NULL")
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
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
