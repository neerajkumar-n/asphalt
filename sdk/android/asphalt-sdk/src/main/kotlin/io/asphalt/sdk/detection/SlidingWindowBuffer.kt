package io.asphalt.sdk.detection

/**
 * A fixed-capacity circular buffer of Float samples with a timestamp per sample.
 *
 * Used to maintain the recent sensor history for:
 * - Computing rolling baselines (median of recent N samples)
 * - Identifying the spike-dip signature of a pothole within a time window
 *
 * Thread-safe through synchronization on this instance. The buffer is accessed
 * from the SensorEventListener thread and read from the detection thread.
 */
class SlidingWindowBuffer(private val capacitySamples: Int = 128) {

    data class Sample(val timestampMs: Long, val value: Float)

    private val buffer = ArrayDeque<Sample>(capacitySamples)

    @Synchronized
    fun add(timestampMs: Long, value: Float) {
        if (buffer.size >= capacitySamples) {
            buffer.removeFirst()
        }
        buffer.addLast(Sample(timestampMs, value))
    }

    @Synchronized
    fun snapshot(): List<Sample> = buffer.toList()

    @Synchronized
    fun snapshotInWindow(windowMs: Long): List<Sample> {
        if (buffer.isEmpty()) return emptyList()
        val cutoff = buffer.last().timestampMs - windowMs
        return buffer.filter { it.timestampMs >= cutoff }
    }

    @Synchronized
    fun rollingMedian(lastN: Int = 32): Float {
        if (buffer.isEmpty()) return 0f
        val values = buffer.takeLast(lastN.coerceAtMost(buffer.size)).map { it.value }
        val sorted = values.sorted()
        return if (sorted.size % 2 == 0) {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2f
        } else {
            sorted[sorted.size / 2]
        }
    }

    @Synchronized
    fun size(): Int = buffer.size

    @Synchronized
    fun clear() = buffer.clear()
}
