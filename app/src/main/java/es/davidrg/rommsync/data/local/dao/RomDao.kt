package es.davidrg.rommsync.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import es.davidrg.rommsync.data.local.entity.DownloadedRomEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RomDao {

    @Query("SELECT * FROM downloaded_roms WHERE platformId = :platformId")
    fun getDownloadedRomsForPlatform(platformId: Int): Flow<List<DownloadedRomEntity>>

    @Query("SELECT * FROM downloaded_roms WHERE romId = :romId")
    suspend fun getDownloadedRom(romId: Int): DownloadedRomEntity?

    @Query("SELECT romId FROM downloaded_roms")
    fun getAllDownloadedRomIds(): Flow<List<Int>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDownloadedRom(rom: DownloadedRomEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM downloaded_roms WHERE romId = :romId)")
    suspend fun isDownloaded(romId: Int): Boolean

    @Query("DELETE FROM downloaded_roms WHERE romId = :romId")
    suspend fun deleteDownloadedRom(romId: Int)

    @Query("SELECT COUNT(*) FROM downloaded_roms")
    fun totalDownloaded(): Flow<Int>
}
