package com.fleetracker

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

/** Kaydedilen tek bir GPS noktası. */
@Entity(tableName = "locations")
data class LocationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val lat: Double,
    val lon: Double,
    val timestamp: Long
)

@Dao
interface LocationDao {

    @Insert
    suspend fun insert(e: LocationEntity)

    /** Belirli zaman aralığındaki noktaları zamana göre sıralı getirir. */
    @Query("SELECT * FROM locations WHERE timestamp BETWEEN :from AND :to ORDER BY timestamp ASC")
    suspend fun getBetween(from: Long, to: Long): List<LocationEntity>

    @Query("SELECT COUNT(*) FROM locations")
    suspend fun count(): Int

    @Query("DELETE FROM locations")
    suspend fun clear()
}

@Database(entities = [LocationEntity::class], version = 1)
abstract class AppDatabase : RoomDatabase() {

    abstract fun dao(): LocationDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "fleetracker.db"
                ).build().also { instance = it }
            }
    }
}

