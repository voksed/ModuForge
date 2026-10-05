package dev.moduforge.host.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "modules")
data class ModuleEntity(
    @PrimaryKey val id: String,
    val manifestJson: String,
    val state: String,
    val installedAtMs: Long,
    val signer: String? = null,
    @ColumnInfo(defaultValue = "0") val autoStart: Boolean = false,
)

/** @property targets authorized targets, newline-separated. */
@Entity(tableName = "grants", primaryKeys = ["moduleId", "capability"])
data class GrantEntity(
    val moduleId: String,
    val capability: String,
    val grantedAtMs: Long,
    val targets: String,
)

@Entity(tableName = "audit", indices = [Index("moduleId")])
data class AuditEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMs: Long,
    val moduleId: String,
    val type: String,
    val capability: String?,
    val target: String?,
    val detail: String,
)

@Dao
interface ModuleDao {
    @Query("SELECT * FROM modules WHERE id = :id")
    suspend fun find(id: String): ModuleEntity?

    @Query("SELECT * FROM modules WHERE id = :id")
    fun observe(id: String): Flow<ModuleEntity?>

    @Query("SELECT * FROM modules ORDER BY installedAtMs")
    fun observeAll(): Flow<List<ModuleEntity>>

    @Upsert
    suspend fun upsert(entity: ModuleEntity)

    @Query("DELETE FROM modules WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface GrantDao {
    @Query("SELECT * FROM grants WHERE moduleId = :moduleId AND capability = :capability")
    suspend fun find(moduleId: String, capability: String): GrantEntity?

    @Query("SELECT * FROM grants WHERE moduleId = :moduleId")
    fun observe(moduleId: String): Flow<List<GrantEntity>>

    @Query("SELECT * FROM grants")
    fun observeAll(): Flow<List<GrantEntity>>

    @Upsert
    suspend fun upsert(entity: GrantEntity)

    @Query("DELETE FROM grants WHERE moduleId = :moduleId AND capability = :capability")
    suspend fun delete(moduleId: String, capability: String)

    @Query("DELETE FROM grants WHERE moduleId = :moduleId")
    suspend fun deleteAll(moduleId: String)
}

@Dao
interface AuditDao {
    @Insert
    suspend fun insert(entity: AuditEntity)

    @Query("SELECT * FROM audit ORDER BY id DESC LIMIT :limit")
    fun observeAll(limit: Int): Flow<List<AuditEntity>>

    @Query("SELECT * FROM audit WHERE moduleId = :moduleId ORDER BY id DESC LIMIT :limit")
    fun observe(moduleId: String, limit: Int): Flow<List<AuditEntity>>
}

@Database(
    entities = [ModuleEntity::class, GrantEntity::class, AuditEntity::class],
    version = 2,
)
abstract class HostDatabase : RoomDatabase() {

    companion object {
        /** Adds the package signer and the auto-start flag to installed modules. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE modules ADD COLUMN signer TEXT")
                db.execSQL("ALTER TABLE modules ADD COLUMN autoStart INTEGER NOT NULL DEFAULT 0")
            }
        }
    }

    abstract fun moduleDao(): ModuleDao

    abstract fun grantDao(): GrantDao

    abstract fun auditDao(): AuditDao
}
