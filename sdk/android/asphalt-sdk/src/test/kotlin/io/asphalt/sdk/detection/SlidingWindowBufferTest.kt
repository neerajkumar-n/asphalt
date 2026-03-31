package io.asphalt.sdk.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlidingWindowBufferTest {

    @Test
    fun `add evicts oldest sample when capacity is reached`() {
        val buf = SlidingWindowBuffer(capacitySamples = 4)
        for (i in 1..5) buf.add(i.toLong(), i.toFloat())

        val snapshot = buf.snapshot()
        assertEquals(4, snapshot.size)
        assertEquals(2f, snapshot.first().value)
        assertEquals(5f, snapshot.last().value)
    }

    @Test
    fun `rollingMedian returns median of last N samples`() {
        val buf = SlidingWindowBuffer(capacitySamples = 64)
        listOf(9f, 10f, 11f, 12f, 13f).forEachIndexed { i, v -> buf.add(i.toLong(), v) }
        // Sorted: [9, 10, 11, 12, 13] -> median = 11
        assertEquals(11f, buf.rollingMedian(lastN = 5))
    }

    @Test
    fun `rollingMedian even count returns average of two middle values`() {
        val buf = SlidingWindowBuffer(capacitySamples = 64)
        listOf(9f, 10f, 11f, 12f).forEachIndexed { i, v -> buf.add(i.toLong(), v) }
        // Sorted: [9, 10, 11, 12] -> median = (10 + 11) / 2 = 10.5
        assertEquals(10.5f, buf.rollingMedian(lastN = 4))
    }

    @Test
    fun `snapshotInWindow returns only samples within the window`() {
        val buf = SlidingWindowBuffer(capacitySamples = 64)
        buf.add(0L, 1f)
        buf.add(100L, 2f)
        buf.add(200L, 3f)
        buf.add(1000L, 4f)
        buf.add(1100L, 5f)

        // Window of 300ms from last sample (1100ms): should include 1000, 1100
        val window = buf.snapshotInWindow(300L)
        assertEquals(2, window.size)
        assertEquals(4f, window.first().value)
        assertEquals(5f, window.last().value)
    }

    @Test
    fun `clear empties the buffer`() {
        val buf = SlidingWindowBuffer(capacitySamples = 16)
        repeat(10) { buf.add(it.toLong(), it.toFloat()) }
        buf.clear()
        assertTrue(buf.snapshot().isEmpty())
        assertEquals(0, buf.size())
    }
}
