package io.asphalt.demo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.asphalt.sdk.Asphalt

/**
 * Foreground service that keeps road anomaly detection alive while the user
 * has switched to another app or locked the screen.
 *
 * ## Why a foreground service is required
 *
 * Android enforces that any app requesting location updates in the background
 * must hold a foreground service with `foregroundServiceType="location"`.
 * Without it:
 * - Android 8+: background processes are killed within minutes
 * - Android 10+ (API 29): FusedLocationProvider stops delivering GPS updates
 *   to processes that are not foreground services
 *
 * ## Lifecycle
 *
 * The service is started by [MainActivity] when the user taps Start, and
 * stopped when the user taps Stop. It calls [Asphalt.start] / [Asphalt.stop]
 * so the SDK lifecycle is tied to the service, not to the Activity.
 *
 * - [onStartCommand] → creates notification, calls startForeground, calls Asphalt.start()
 * - [onDestroy]      → calls Asphalt.stop(), removes the foreground notification
 *
 * ## Notification channel
 *
 * Channel ID "asphalt_detection" uses IMPORTANCE_LOW so the notification is
 * visible in the shade but does not make a sound or pop up as a heads-up.
 *
 * ## Required manifest entries (already in AndroidManifest.xml)
 *
 * ```xml
 * <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
 * <uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
 *
 * <service
 *     android:name=".DetectionForegroundService"
 *     android:foregroundServiceType="location"
 *     android:exported="false" />
 * ```
 */
class DetectionForegroundService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification = buildNotification()

        // API 29+ requires specifying the foreground service type when starting
        // a service that uses location. API 34+ (Android 14) also requires the
        // FOREGROUND_SERVICE_LOCATION permission in the manifest.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        Asphalt.start()
        return START_STICKY
    }

    override fun onDestroy() {
        Asphalt.stop()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // -------------------------------------------------------------------------
    // Notification helpers
    // -------------------------------------------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Road Detection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shown while road anomaly detection is running in the background"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Asphalt Active")
            .setContentText("Detecting road anomalies in the background")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    companion object {
        private const val CHANNEL_ID = "asphalt_detection"
        private const val NOTIFICATION_ID = 1
    }
}
