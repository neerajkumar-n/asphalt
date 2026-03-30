package io.asphalt.sdk.internal

import android.util.Log

/**
 * Internal logging wrapper. Debug logs are suppressed when [enabled] is false.
 * The [enabled] flag is set by [io.asphalt.sdk.Asphalt] based on [AsphaltConfig.debugLogging].
 */
internal object AsphaltLog {
    var enabled: Boolean = false
    private const val TAG_PREFIX = "Asphalt/"

    fun d(tag: String, msg: String) {
        if (enabled) Log.d(TAG_PREFIX + tag, msg)
    }

    fun w(tag: String, msg: String) {
        Log.w(TAG_PREFIX + tag, msg)
    }

    fun e(tag: String, msg: String, throwable: Throwable? = null) {
        Log.e(TAG_PREFIX + tag, msg, throwable)
    }
}
