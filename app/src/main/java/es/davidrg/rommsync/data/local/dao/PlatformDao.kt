package es.davidrg.rommsync.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import es.davidrg.rommsync.data.local.entity.PlatformEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlatformDao {

    @Query("SELECT * FROM platforms ORDER BY name ASC")
    fun getAllPlatforms(): Flow<List<PlatformEntity>>

    @Query("SELECT * FROM platforms WHERE visible = 1 ORDER BY name ASC")
    fun getVisiblePlatforms(): Flow<List<PlatformEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(platforms: List<PlatformEntity>)

    @Query("UPDATE platforms SET visible = :visible WHERE id = :id")
    suspend fun updateVisibility(id: Int, visible: Boolean)

    @Query("SELECT COUNT(*) FROM platforms")
    suspend fun count(): Int
}
