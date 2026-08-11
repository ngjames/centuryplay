package com.airplay.streamer.airplay2.timing

import org.junit.Assert.assertEquals
import org.junit.Test

class NtpTimingTest {

    @Test
    fun `offsetFromTimingExchange symmetric exchange`() {
        // receiver send 1000, we receive 1100, we send 1100, receiver receive 1000 -> 100
        assertEquals(100L, NtpTiming.offsetFromTimingExchange(1000L, 1100L, 1100L, 1000L))
    }

    @Test
    fun `offsetFromTimingExchange asymmetric delays`() {
        assertEquals(50L, NtpTiming.offsetFromTimingExchange(1000L, 1050L, 1200L, 1150L))
    }

    @Test
    fun `offsetFromTimingExchange zero when clocks aligned`() {
        assertEquals(0L, NtpTiming.offsetFromTimingExchange(1000L, 1000L, 1000L, 1000L))
    }

    @Test
    fun `offsetFromTimingExchange negative offset`() {
        // ((900-1000) + (900-1000)) / 2 = -100
        assertEquals(-100L, NtpTiming.offsetFromTimingExchange(1000L, 900L, 900L, 1000L))
    }

    @Test
    fun `stampAnchor advances 352 samples per frame`() {
        assertEquals(0L, NtpTiming.stampAnchor(0L, 0L, 0L, 0L))
        assertEquals(123904L, NtpTiming.stampAnchor(1_000_000L, 0L, 0L, 352L)) // 352*352
        assertEquals(247808L, NtpTiming.stampAnchor(1_000_000L, 500_000L, 123904L, 352L))
    }

    @Test
    fun `stampAnchor rtp timestamp is base plus frames times 352 regardless of offset`() {
        assertEquals(123904L, NtpTiming.stampAnchor(1_000_000L, -500_000L, 0L, 352L))
        assertEquals(123904L, NtpTiming.stampAnchor(9_999_999L, 500_000L, 0L, 352L))
    }
}
