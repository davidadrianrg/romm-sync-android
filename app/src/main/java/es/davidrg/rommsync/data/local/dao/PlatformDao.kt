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

    /**
     * Inserta o actualiza plataformas preservando el valor existente de 'visible'.
     *
     * A diferencia de [upsertAll] (que usa REPLACE y sobrescribe 'visible' con el
     * valor que traiga la entidad —siempre true al venir del servidor—), esta
     * query respeta la preferencia del usuario:
     *  - Si la plataforma ya existe, mantiene su 'visible' actual.
     *  - Si es nueva, la inserta con visible = 1 (COALESCE por defecto).
     */
    @Query(
        """
        INSERT INTO platforms (id, slug, name, romCount, visible)
        VALUES (:id, :slug, :name, :romCount, COALESCE((SELECT visible FROM platforms WHERE id = :id), 1))
        ON CONFLICT(id) DO UPDATE SET slug = :slug, name = :name, romCount = :romCount
        """
    )
    suspend fun upsertPreservingVisibility(
        id: Int,
        slug: String,
        name: String,
        romCount: Int,
    )

    @Query("UPDATE platforms SET visible = :visible WHERE id = :id")
    suspend fun updateVisibility(id: Int, visible: Boolean)

    @Query("UPDATE platforms SET emulatorId = :emulatorId WHERE id = :id")
    suspend fun updateEmulator(id: Int, emulatorId: String?)

    @Query("UPDATE platforms SET savesPathOverride = :path WHERE id = :id")
    suspend fun updateSavesPathOverride(id: Int, path: String?)

    @Query("SELECT COUNT(*) FROM platforms")
    suspend fun count(): Int

    @Query("SELECT * FROM platforms")
    fun getAllPlatformsBlocking(): List<PlatformEntity>
}
