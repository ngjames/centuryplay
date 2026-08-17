package com.airplay.streamer.raop

import kotlinx.coroutines.isActive
import java.net.DatagramPacket
import java.net.DatagramSocket
import kotlin.coroutines.coroutineContext

/**
 * Shared NTP timestamp responder for the AirPlay timing socket, used by both
 * the RAOP path (was `RaopClient.startTimingResponder`) and the AirPlay 2 path
 * (was `AirPlay2Client.startNtpTimingResponder`).
 *
 * Byte-exact wire format preserved from both call sites:
 *   - response buffer sized to the received datagram length
 *   - request bytes 0..8 copied verbatim into the response
 *   - byte 1 OR'd with `0x53 or 0x80` (reply mode flags)
 *   - request bytes 24..32 copied into response bytes 8..16
 *   - current NTP timestamp written at offsets 16 and 24
 *
 * The seconds field is identical in both stacks; the fraction field has two
 * equivalent-but-different implementations (RAOP uses Double math truncated to
 * Long, AP2 uses Long integer arithmetic). Both truncate to the same quotient
 * for every millisecond input, but each stack keeps its own variant — see
 * [raopFraction] and [ap2Fraction]. The exact values are pinned by
 * `NtpTimestampTest`; do not perturb the math.
 */
object NtpResponder {

    /** Unix ms -> NTP seconds (1900 epoch). RaopClient.kt:668, :715, AirPlay2Client.kt:391. */
    fun ntpSeconds(ms: Long): Long = (ms / 1000) + WireConstants.Ntp.EPOCH_OFFSET

    /**
     * RAOP fraction: Double scale, truncated to Long.
     * RaopClient.kt:669, :715: `((ms % 1000) * 4294967296.0 / 1000.0).toLong()`.
     */
    fun raopFraction(ms: Long): Long =
        ((ms % 1000) * WireConstants.Ntp.FRACTION_SCALE / 1000.0).toLong()

    /**
     * AP2 fraction: Long integer arithmetic.
     * AirPlay2Client.kt:392: `((ms % 1000) * 4294967296L) / 1000`.
     */
    fun ap2Fraction(ms: Long): Long = ((ms % 1000) * 4294967296L) / 1000

    /** Serialize an NTP seconds/fraction pair at offset [o] (big-endian). */
    fun writeNtpTimestamp(b: ByteArray, o: Int, s: Long, f: Long) {
        b[o] = (s shr 24).toByte(); b[o + 1] = (s shr 16).toByte(); b[o + 2] = (s shr 8).toByte(); b[o + 3] = s.toByte()
        b[o + 4] = (f shr 24).toByte(); b[o + 5] = (f shr 16).toByte(); b[o + 6] = (f shr 8).toByte(); b[o + 7] = f.toByte()
    }

    /**
     * Parse an 8-byte NTP seconds/fraction pair at offset [o] (big-endian)
     * and convert it to nanoseconds since the NTP epoch. Shared by the
     * AirPlay 2 NTP responder (was `AirPlay2Client.ntpToNanos(req, o)`).
     */
    fun readNtpTimestampNanos(b: ByteArray, o: Int): Long {
        val sec = ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)
        val frac = ((b[o + 4].toLong() and 0xFF) shl 24) or ((b[o + 5].toLong() and 0xFF) shl 16) or
            ((b[o + 6].toLong() and 0xFF) shl 8) or (b[o + 7].toLong() and 0xFF)
        return ntpToNanos(sec, frac)
    }

    /** NTP seconds/fraction (epoch 1900) as nanoseconds since the NTP epoch. */
    fun ntpToNanos(sec: Long, frac: Long): Long =
        sec * 1_000_000_000L + (frac * 1_000_000_000L) / 0x1_0000_0000L

    /**
     * Build the full timing response for a received request (byte-exact).
     * [req] is the datagram buffer, [packetLength] the received datagram
     * length, [nowMs] the wall-clock reading used for both timestamps.
     */
    fun buildResponse(req: ByteArray, packetLength: Int, nowMs: Long, ap2Style: Boolean = false): ByteArray {
        val resp = ByteArray(packetLength)
        System.arraycopy(req, 0, resp, 0, 8)
        resp[1] = (0x53 or 0x80).toByte()
        System.arraycopy(req, 24, resp, 8, 8)
        val sec = ntpSeconds(nowMs)
        val frac = if (ap2Style) ap2Fraction(nowMs) else raopFraction(nowMs)
        writeNtpTimestamp(resp, 16, sec, frac)
        writeNtpTimestamp(resp, 24, sec, frac)
        return resp
    }

    /**
     * Coroutine variant: answers NTP requests on [socket] until the calling
     * coroutine is cancelled or the socket is closed. Blocks inside
     * `DatagramSocket.receive` between packets (matching the original thread
     * behaviour); closing the socket from another coroutine unblocks it.
     *
     * [onExchange] is invoked once per valid (>= 32 byte) request with the
     * request buffer and the timestamp used in the reply, letting consumers
     * derive clock offsets from the exchange (as the AirPlay 2 stack does).
     */
    suspend fun run(
        socket: DatagramSocket,
        ap2Style: Boolean = false,
        onExchange: ((req: ByteArray, ntpSec: Long, ntpFrac: Long) -> Unit)? = null
    ) {
        val buffer = ByteArray(128)
        val packet = DatagramPacket(buffer, buffer.size)
        try {
            while (coroutineContext.isActive && !socket.isClosed) {
                socket.receive(packet)
                if (packet.length >= 32) {
                    val nowMs = System.currentTimeMillis()
                    val resp = buildResponse(packet.data, packet.length, nowMs, ap2Style)
                    socket.send(DatagramPacket(resp, packet.length, packet.address, packet.port))
                    onExchange?.invoke(packet.data, ntpSeconds(nowMs), if (ap2Style) ap2Fraction(nowMs) else raopFraction(nowMs))
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Socket closed or reset during shutdown; benign.
        }
    }

}
