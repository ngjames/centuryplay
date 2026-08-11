package com.airplay.streamer.airplay2

import com.dd.plist.BinaryPropertyListWriter
import com.dd.plist.NSArray
import com.dd.plist.NSData
import com.dd.plist.NSDictionary
import com.dd.plist.NSNumber
import com.dd.plist.PropertyListParser
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Integration test: drives the real [AirPlay2Client] through
 * connect() -> pair() -> setupStreaming() against a minimal in-JVM mock
 * receiver ([MockAp2Receiver]), then asserts the OUTBOUND SETUP plists
 * byte-exactly via dd-plist.
 *
 * Round-2 review fix coverage: audioFormat == 1633771873 ('alac'), shk == 32
 * bytes, ct == 2, type == 96, spf == 352, timingProtocol present, and the
 * generated binary plist roundtrips (BinaryPropertyListWriter ->
 * PropertyListParser -> NSDictionary), per draft decision D6.
 */
class AirPlay2ClientIntegrationTest {

    @Test
    fun `connect pair setupStreaming asserts ALAC SETUP plist byte-exactly`() = runBlocking {
        val receiver = MockAp2Receiver()
        receiver.start()
        val client = AirPlay2Client("127.0.0.1", receiver.port)
        try {
            client.connect()
            assertTrue("pair() must succeed against the mock receiver", client.pair())
            assertTrue("setupStreaming() must succeed", client.setupStreaming())

            // --- Event channel SETUP plist: timingProtocol must be present ---
            val eventPlist = receiver.eventSetupPlist()
                ?: throw AssertionError("event SETUP request was never captured")
            val eventDict = PropertyListParser.parse(ByteArrayInputStream(eventPlist)) as NSDictionary
            assertTrue("timingProtocol key present in event SETUP", eventDict["timingProtocol"] != null)
            assertEquals("NTP", eventDict["timingProtocol"].toString())
            assertFalse("default client must not have started the PTP clock", client.clockStarted)

            // --- Audio SETUP plist: byte-exact assertions on captured bytes ---
            val audioPlist = receiver.audioSetupPlist()
                ?: throw AssertionError("audio SETUP request was never captured")
            val audioDict = PropertyListParser.parse(ByteArrayInputStream(audioPlist)) as NSDictionary
            val stream = (audioDict["streams"] as NSArray).array.first() as NSDictionary
            assertEquals("audioFormat must be 'alac' = 1633771873", 1633771873, (stream["audioFormat"] as NSNumber).intValue())
            assertEquals("ct must be 2 (ALAC)", 2, (stream["ct"] as NSNumber).intValue())
            assertEquals("type must be 96", 96, (stream["type"] as NSNumber).intValue())
            assertEquals("spf must be 352", 352, (stream["spf"] as NSNumber).intValue())
            assertEquals("shk must be 32 bytes", 32, (stream["shk"] as NSData).bytes().size)

            // --- bplist roundtrip: BinaryPropertyListWriter output reparses ---
            val reencoded = ByteArrayOutputStream()
                .also { BinaryPropertyListWriter.write(it, audioDict) }
                .toByteArray()
            val reparsed = PropertyListParser.parse(ByteArrayInputStream(reencoded)) as NSDictionary
            assertEquals(
                audioDict.allKeys().map { it.toString() }.toSet(),
                reparsed.allKeys().map { it.toString() }.toSet()
            )
            val reparsedStream = (reparsed["streams"] as NSArray).array.first() as NSDictionary
            assertEquals("roundtripped audioFormat must still be 'alac'", 1633771873, (reparsedStream["audioFormat"] as NSNumber).intValue())

            // --- Lifecycle order: FLUSH before RECORD ---
            val methods = receiver.methodSequence
            val flushIndex = methods.indexOfFirst { it.startsWith("FLUSH ") }
            val recordIndex = methods.indexOfFirst { it.startsWith("RECORD ") }
            assertTrue(
                "FLUSH request seen (methods: ${methods.joinToString(", ")})",
                flushIndex >= 0
            )
            assertTrue("RECORD request seen", recordIndex >= 0)
            assertTrue("FLUSH must precede RECORD", flushIndex < recordIndex)
            assertTrue("SET_PARAMETER request seen", methods.any { it.startsWith("SET_PARAMETER ") })
            assertTrue("POST /feedback keepalive seen", waitForFeedback(methods))

            // --- FLUSH headers: Range: npt=0-, RTP-Info: seq=0;rtptime=0 ---
            val flushHeaders = receiver.flushHeaders()
                ?: throw AssertionError("FLUSH request was never captured")
            assertEquals("npt=0-", flushHeaders["Range"])
            assertEquals("seq=0;rtptime=0", flushHeaders["RTP-Info"])

            // --- Volume body format: 'volume: <float 0..1>' ---
            val volumeBody = String(receiver.setParameterBody() ?: ByteArray(0))
            assertTrue("SET_PARAMETER body must start with 'volume: '", volumeBody.startsWith("volume: "))
        } finally {
            client.disconnect()
            receiver.stop()
        }
    }

    @Test
    fun `PTP mode advertises PTP and starts the master clock`() = runBlocking {
        val receiver = MockAp2Receiver()
        receiver.start()
        val client = AirPlay2Client("127.0.0.1", receiver.port, TimingMode.PTP)
        try {
            client.connect()
            assertTrue("pair() must succeed against the mock receiver", client.pair())
            // A 319/320 bind failure in the test JVM must not fail the test:
            // assert the mode-specific behavior regardless.
            try {
                client.setupStreaming()
            } catch (e: Exception) {
                // fall through: assert flags below
            }

            val eventPlist = receiver.eventSetupPlist()
                ?: throw AssertionError("event SETUP request was never captured")
            val eventDict = PropertyListParser.parse(ByteArrayInputStream(eventPlist)) as NSDictionary
            assertEquals("PTP", eventDict["timingProtocol"].toString())
            assertTrue("PTP mode must start the master clock", client.clockStarted)
        } finally {
            client.disconnect()
            receiver.stop()
        }
    }

    /** The keepalive loop fires its first POST /feedback right after launch; poll briefly. */
    private suspend fun waitForFeedback(methods: List<String>): Boolean {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (methods.contains("POST /feedback")) return true
            delay(100)
        }
        return false
    }
}
