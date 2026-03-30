package io.asphalt.sdk.storage

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import io.asphalt.sdk.model.AnomalyType
import io.asphalt.sdk.model.DeviceMeta
import io.asphalt.sdk.model.RoadEvent
import io.asphalt.sdk.model.SensorSummary

/**
 * Room database for offline-first event storage.
 *
 * ## Offline-first design
 *
 * Events are always written to the local database first. Upload is a
 * background operation managed by WorkManager. If upload fails, events
 * remain in the database and are retried on the next flush cycle.
 *
 * Events are marked [uploaded = true] after a confirmed server response
 * and are pruned in a subsequent cleanup pass. This approach means we
 * never lose an event due to a network failure at the moment of detection.
 *
 * ## Schema versioning
 *
 * Version 1 is the initial schema. Migrations should be added as
 * AutoMigration annotations or explicit Migration objects in future versions.
 */
@Database(entities = [RoadEventEntity::class], version = 1, exportSchema = true)
abstract class EventDatabase : RoomDatabase() {

    abstract fun eventDao(): EventDao

    companion object {
        @Volatile private var instance: EventDatabase? = null

        fun getInstance(context: Context): EventDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    EventDatabase::class.java,
                    "asphalt_events.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
        }
    }
}

@Entity(tableName = "road_events")
data class RoadEventEntity(
    @PrimaryKey val eventId: String,
    val timestampMs: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val intensity: Float,
    val speedKmh: Float,
    val anomalyType: String,
    // SensorSummary fields flattened for simpler schema
    val accelPeakZ: Float,
    val accelBaselineZ: Float,
    val accelDeltaZ: Float,
    val gyroPeakMagnitude: Float,
    val sampleCount: Int,
    val windowDurationMs: Long,
    // DeviceMeta fields
    val platform: String,
    val sdkInt: Int,
    val manufacturer: String,
    val model: String,
    val sensorVendor: String,
    val sdkVersion: String,
    val sessionId: String,
    // Upload state
    val uploaded: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface EventDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: RoadEventEntity)

    @Query("SELECT * FROM road_events WHERE uploaded = 0 ORDER BY timestampMs ASC LIMIT :limit")
    suspend fun getPendingEvents(limit: Int = 500): List<RoadEventEntity>

    @Query("UPDATE road_events SET uploaded = 1 WHERE eventId IN (:ids)")
    suspend fun markUploaded(ids: List<String>)

    @Query("DELETE FROM road_events WHERE uploaded = 1 AND createdAt < :beforeMs")
    suspend fun pruneUploaded(beforeMs: Long)

    @Query("SELECT COUNT(*) FROM road_events WHERE uploaded = 0")
    suspend fun pendingCount(): Int

    @Transaction
    suspend fun markUploadedAndPrune(ids: List<String>) {
        markUploaded(ids)
        // Prune events uploaded more than 24 hours ago to keep the DB small
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        pruneUploaded(cutoff)
    }
}

// Conversion helpers

fun RoadEvent.toEntity(): RoadEventEntity = RoadEventEntity(
    eventId = eventId,
    timestampMs = timestampMs,
    latitude = latitude,
    longitude = longitude,
    accuracyMeters = accuracyMeters,
    intensity = intensity,
    speedKmh = speedKmh,
    anomalyType = anomalyType.value,
    accelPeakZ = sensorSummary.accelPeakZ,
    accelBaselineZ = sensorSummary.accelBaselineZ,
    accelDeltaZ = sensorSummary.accelDeltaZ,
    gyroPeakMagnitude = sensorSummary.gyroPeakMagnitude,
    sampleCount = sensorSummary.sampleCount,
    windowDurationMs = sensorSummary.windowDurationMs,
    platform = deviceMeta.platform,
    sdkInt = deviceMeta.sdkInt,
    manufacturer = deviceMeta.manufacturer,
    model = deviceMeta.model,
    sensorVendor = deviceMeta.sensorVendor,
    sdkVersion = sdkVersion,
    sessionId = sessionId
)

fun RoadEventEntity.toModel(): RoadEvent = RoadEvent(
    eventId = eventId,
    timestampMs = timestampMs,
    latitude = latitude,
    longitude = longitude,
    accuracyMeters = accuracyMeters,
    intensity = intensity,
    speedKmh = speedKmh,
    anomalyType = AnomalyType.values().firstOrNull { it.value == anomalyType } ?: AnomalyType.UNKNOWN,
    sensorSummary = SensorSummary(accelPeakZ, accelBaselineZ, accelDeltaZ, gyroPeakMagnitude, sampleCount, windowDurationMs),
    deviceMeta = DeviceMeta(platform, sdkInt, manufacturer, model, sensorVendor),
    sdkVersion = sdkVersion,
    sessionId = sessionId
)
