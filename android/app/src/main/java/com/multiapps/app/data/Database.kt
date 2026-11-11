package com.multiapps.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** One clone of an installed app. */
@Entity(tableName = "clones")
data class CloneEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val appLabel: String,
    val name: String,
    val colorIndex: Int = 0,
    /** Proxy used when the user picks "Use its own proxy" at launch. Null = none assigned. */
    val proxyId: Long? = null,
    /** If true, ask "No proxy / Own proxy" every time the clone is opened. */
    val askProxyOnLaunch: Boolean = true,
    val notificationsEnabled: Boolean = true,
    val notificationsMuted: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

/** A SOCKS5 or HTTP proxy. The password is stored encrypted with the Android Keystore. */
@Entity(tableName = "proxies")
data class ProxyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: String, // "SOCKS5" or "HTTP"
    val host: String,
    val port: Int,
    val username: String = "",
    val passwordEnc: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

@Dao
interface CloneDao {
    @Query("SELECT * FROM clones ORDER BY createdAt")
    fun observeAll(): Flow<List<CloneEntity>>

    @Query("SELECT * FROM clones WHERE id = :id")
    fun observe(id: Long): Flow<CloneEntity?>

    @Query("SELECT * FROM clones ORDER BY createdAt")
    suspend fun all(): List<CloneEntity>

    @Query("SELECT COUNT(*) FROM clones WHERE packageName = :packageName")
    suspend fun countFor(packageName: String): Int

    @Insert
    suspend fun insert(clone: CloneEntity): Long

    @Update
    suspend fun update(clone: CloneEntity)

    @Delete
    suspend fun delete(clone: CloneEntity)

    @Query("DELETE FROM clones")
    suspend fun deleteAll()

    @Query("UPDATE clones SET proxyId = NULL WHERE proxyId = :proxyId")
    suspend fun clearProxy(proxyId: Long)
}

@Dao
interface ProxyDao {
    @Query("SELECT * FROM proxies ORDER BY name")
    fun observeAll(): Flow<List<ProxyEntity>>

    @Query("SELECT * FROM proxies ORDER BY name")
    suspend fun all(): List<ProxyEntity>

    @Query("SELECT * FROM proxies WHERE id = :id")
    suspend fun get(id: Long): ProxyEntity?

    @Insert
    suspend fun insert(proxy: ProxyEntity): Long

    @Update
    suspend fun update(proxy: ProxyEntity)

    @Delete
    suspend fun delete(proxy: ProxyEntity)

    @Query("DELETE FROM proxies")
    suspend fun deleteAll()
}

@Database(entities = [CloneEntity::class, ProxyEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun clones(): CloneDao
    abstract fun proxies(): ProxyDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "multi_apps.db").build()
    }
}
