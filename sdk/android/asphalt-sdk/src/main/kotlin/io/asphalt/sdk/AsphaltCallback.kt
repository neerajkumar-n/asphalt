package io.asphalt.sdk

import io.asphalt.sdk.model.RoadEvent

/**
 * Optional callback interface for receiving real-time detection events.
 *
 * Implement this to display live feedback in your UI (e.g. show a marker on
 * a map immediately when a pothole is detected, before it is uploaded).
 *
 * All callbacks are invoked on the main thread.
 */
interface AsphaltCallback {

    /**
     * Called when the SDK detects a road anomaly.
     *
     * This fires before the event is persisted or uploaded. If you need
     * to modify or suppress the event, return false. Return true to allow
     * normal processing.
     */
    fun onEventDetected(event: RoadEvent): Boolean = true

    /**
     * Called when the SDK transitions between active and idle states.
     *
     * The SDK becomes active when vehicle speed exceeds [AsphaltConfig.minSpeedKmh]
     * and goes idle when speed drops below it or when GPS is lost.
     */
    fun onStateChanged(active: Boolean) {}

    /**
     * Called when a batch upload succeeds.
     */
    fun onBatchUploaded(eventCount: Int) {}

    /**
     * Called when a batch upload fails. The batch is retained locally for retry.
     */
    fun onUploadFailed(error: Throwable) {}
}
