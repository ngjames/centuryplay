package com.airplay.streamer.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Matrix test for [resolveProtocol]: preference (0=Auto, 1=AirPlay 1,
 * 2=AirPlay 2) x port x raopPort.
 */
class ProtocolResolverTest {

    // --- Auto (0) ---

    @Test
    fun `auto with port 7000 resolves to airplay 2`() {
        assertEquals(Protocol.AIRPLAY2, resolveProtocol(0, 7000, null))
    }

    @Test
    fun `auto with port 7000 and no raop port resolves to airplay 2`() {
        assertEquals(Protocol.AIRPLAY2, resolveProtocol(0, 7000, null))
    }

    @Test
    fun `auto with port 7000 and raop port 5000 resolves to airplay 2`() {
        assertEquals(Protocol.AIRPLAY2, resolveProtocol(0, 7000, 5000))
    }

    @Test
    fun `auto with port 5000 resolves to airplay 1`() {
        assertEquals(Protocol.AIRPLAY1, resolveProtocol(0, 5000, null))
    }

    @Test
    fun `auto with port 5000 and raop port 5000 resolves to airplay 1`() {
        assertEquals(Protocol.AIRPLAY1, resolveProtocol(0, 5000, 5000))
    }

    @Test
    fun `auto with non standard port resolves to airplay 1`() {
        assertEquals(Protocol.AIRPLAY1, resolveProtocol(0, 57000, null))
    }

    @Test
    fun `auto with default preference value 0 resolves same as auto`() {
        assertEquals(resolveProtocol(0, 7000, null), resolveProtocol(0, 7000, null))
        assertEquals(Protocol.AIRPLAY1, resolveProtocol(0, 5000, null))
    }

    // --- AirPlay 1 (1) ---

    @Test
    fun `airplay 1 preference with port 7000 resolves to airplay 1`() {
        assertEquals(Protocol.AIRPLAY1, resolveProtocol(1, 7000, null))
    }

    @Test
    fun `airplay 1 preference with raop port resolves to airplay 1`() {
        assertEquals(Protocol.AIRPLAY1, resolveProtocol(1, 7000, 5000))
    }

    @Test
    fun `airplay 1 preference with port 5000 resolves to airplay 1`() {
        assertEquals(Protocol.AIRPLAY1, resolveProtocol(1, 5000, 5000))
    }

    @Test
    fun `airplay 1 preference with any port resolves to airplay 1`() {
        for (port in listOf(0, 1, 5000, 7000, 57000)) {
            assertEquals(Protocol.AIRPLAY1, resolveProtocol(1, port, null))
        }
    }

    // --- AirPlay 2 (2) ---

    @Test
    fun `airplay 2 preference resolves to airplay 2 on port 7000`() {
        assertEquals(Protocol.AIRPLAY2, resolveProtocol(2, 7000, null))
    }

    @Test
    fun `airplay 2 preference with raop port present resolves to airplay 2`() {
        assertEquals(Protocol.AIRPLAY2, resolveProtocol(2, 7000, 5000))
    }

    @Test
    fun `airplay 2 preference with non 7000 port resolves to airplay 2`() {
        assertEquals(Protocol.AIRPLAY2, resolveProtocol(2, 5000, null))
    }
}
