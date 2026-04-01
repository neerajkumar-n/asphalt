package io.asphalt.demo

import android.content.Context

/**
 * Manages user consent for road data collection under India's Digital Personal
 * Data Protection Act 2023 (DPDP Act).
 *
 * Although GPS coordinates attached to road anomaly events are not directly
 * personal data (they describe road conditions, not the user's identity),
 * the DPDP Act's broad definition of "digital personal data" and the
 * requirement for explicit consent for any sensor/location usage makes a
 * consent gate the safest practice for production deployments in India.
 *
 * ## What we collect
 * - Accelerometer and gyroscope readings (only while driving above 15 km/h)
 * - GPS coordinates (only when an anomaly is detected, not continuously)
 * - Anonymous device metadata: manufacturer, model, Android SDK version,
 *   sensor vendor
 *
 * ## What we do NOT collect
 * - No user accounts, names, or contact information
 * - No device identifiers (IMEI, advertising ID, etc.)
 * - No continuous location tracking
 * - No raw sensor data (only post-detection summary statistics)
 *
 * Consent is stored in SharedPreferences and persists across app restarts.
 * To withdraw consent, the user can clear app data or uninstall.
 */
object ConsentManager {

    private const val PREFS_NAME = "asphalt_prefs"
    private const val KEY_CONSENT = "data_collection_consent"

    /**
     * Returns true if the user has previously accepted data collection.
     * Returns false on first launch or if consent was not given.
     */
    fun hasConsent(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_CONSENT, false)

    /**
     * Persists the user's consent decision.
     *
     * Call with [accepted] = true when the user taps Accept in the consent dialog.
     * Call with [accepted] = false if the user declines (stored so we don't ask again
     * on every launch, but SDK will remain inactive).
     */
    fun setConsent(context: Context, accepted: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_CONSENT, accepted)
            .apply()
    }
}
