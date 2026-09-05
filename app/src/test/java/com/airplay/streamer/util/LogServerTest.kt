package com.airplay.streamer.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * JVM tests for [LogServer]'s in-memory buffer: timestamp format, the
 * DateTimeFormatter thread-safety swap, and the bounded queue. Needs no
 * socket: log() only touches the buffer and the stubbed android.util.Log.
 */
class LogServerTest {

    /** log() timestamps are always HH:mm:ss.SSS at the start of an entry. */
    @Test
    fun `log entries carry a parseable HH-mm-ss-SSS timestamp`() {
        LogServer.log("hello")
        val entry = LogServer.logs.last()
        assertTrue("entry was: $entry", entry.endsWith("] hello"))
        val timestamp = entry.substringAfter('[').substringBefore(']')
        val formatter = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
        java.time.LocalTime.parse(timestamp, formatter) // throws on format deviation
    }

    @Test
    fun `level-tagged helpers route through the shared buffer`() {
        LogServer.d("Tag", "detail")
        LogServer.e("Tag", "boom")
        assertTrue(LogServer.logs.last()!!.endsWith("E/Tag: boom"))
        assertTrue(LogServer.logs.elementAt(LogServer.logs.size - 2).endsWith("D/Tag: detail"))
    }

    @Test
    fun `buffer stays bounded at the maximum`() {
        LogServer.logs.clear()
        for (i in 1..600) LogServer.log("line-$i")
        assertEquals(500, LogServer.logs.size)
        // Oldest entries are dropped, newest kept.
        assertTrue(LogServer.logs.first()!!.endsWith("line-101"))
        assertTrue(LogServer.logs.last()!!.endsWith("line-600"))
    }

    /** The SimpleDateFormat swap: hammering log() from many threads must keep
     *  every timestamp well-formed and monotonic-format — a corrupting
     *  formatter would produce malformed or interleaved output. */
    @Test
    fun `concurrent log calls never corrupt timestamp formatting`() {
        LogServer.logs.clear()
        val threads = 8
        val perThread = 250
        val pool = Executors.newFixedThreadPool(threads)
        val done = CountDownLatch(threads)
        repeat(threads) { t ->
            pool.execute {
                repeat(perThread) { i -> LogServer.log("t$t-i$i") }
                done.countDown()
            }
        }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        pool.shutdown()

        val regex = Regex("""^\[\d{2}:\d{2}:\d{2}\.\d{3}] t\d+-i\d+$""")
        // 2000 entries pushed against the 500 cap with weakly-consistent
        // size() reads: the bound holds (never above 500) and trimming under
        // contention can land a few below it, so assert a tight band.
        assertTrue(
            "buffer size out of band: ${LogServer.logs.size}",
            LogServer.logs.size in 495..500
        )
        LogServer.logs.forEach { entry ->
            assertTrue("malformed entry: $entry", regex.matches(entry))
        }
    }

    @Test
    fun `empty buffer exposes no entries`() {
        LogServer.logs.clear()
        assertNull(LogServer.logs.peek())
    }
}
