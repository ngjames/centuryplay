package com.airplay.streamer.airplay2.timing

import kotlinx.coroutines.*
import com.airplay.streamer.airplay2.util.Ap2Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom

/**
 * PTP Master Clock for AirPlay 2
 * 
 * Sends PTP Announce, Sync, and Follow_Up messages to NQPTP on ports 319/320.
 * NQPTP is passive and monitors PTP messages from our IP to establish timing.
 */
class PtpMasterClock(
    private val targetHost: String
) {
    companion object {
        const val PTP_EVENT_PORT = 319
        const val PTP_GENERAL_PORT = 320
        const val SYNC_INTERVAL_MS = 125L // 8 messages per second
        
        // PTP Message Types
        const val MSG_SYNC = 0x00
        const val MSG_FOLLOW_UP = 0x08
        const val MSG_ANNOUNCE = 0x0B
    }
    
    val clockId: Long = SecureRandom().nextLong()
    
    private var socket319: DatagramSocket? = null
    private var socket320: DatagramSocket? = null
    private var running = false
    private var job: Job? = null
    
    private var syncSequenceId = 0
    private var announceSequenceId = 0
    
    /**
     * Start the PTP master clock
     */
    fun start(scope: CoroutineScope) {
        if (running) return
        
        try {
            socket319 = DatagramSocket(PTP_EVENT_PORT).apply { reuseAddress = true }
            socket320 = DatagramSocket(PTP_GENERAL_PORT).apply { reuseAddress = true }
            Ap2Log.log("PtpMasterClock: bound to privileged ports 319/320")
        } catch (e: Exception) {
            Ap2Log.log("PtpMasterClock: failed to bind 319/320 (${e.message}), using ephemeral ports (PTP requires privileged ports on the receiver side)")
            socket319 = DatagramSocket().apply { reuseAddress = true }
            socket320 = DatagramSocket().apply { reuseAddress = true }
        }
        running = true
        
        job = scope.launch(Dispatchers.IO) {
            runPtpLoop()
        }
    }
    
    /**
     * Stop the PTP master clock
     */
    fun stop() {
        running = false
        job?.cancel()
        socket319?.close()
        socket320?.close()
        socket319 = null
        socket320 = null
    }
    
    /**
     * Send a burst of ANNOUNCE messages to wake up NQPTP
     */
    suspend fun sendAnnounceBurst(count: Int = 5, delayMs: Long = 50) {
        val targetAddr = InetAddress.getByName(targetHost)
        repeat(count) { i ->
            val announce = buildAnnounceMessage(1000 + i)
            sendTo320(announce, targetAddr)
            if (i < count - 1) {
                delay(delayMs)
            }
        }
    }
    
    /**
     * Main PTP message loop
     */
    private suspend fun runPtpLoop() {
        val targetAddr = InetAddress.getByName(targetHost)

        // ANNOUNCE must precede SYNC/FOLLOW_UP: nqptp discards SYNC/FOLLOW_UP
        // until the ANNOUNCE establishes our clock identity. Send an ANNOUNCE
        // burst first, then keep the periodic ANNOUNCE every 8th message.
        sendAnnounceBurst(count = 3, delayMs = 30)

        while (running) {
            val nowNs = System.nanoTime()
            
            // Send ANNOUNCE every ~1 second (every 8th message)
            if (syncSequenceId % 8 == 0) {
                val announce = buildAnnounceMessage(announceSequenceId++)
                sendTo320(announce, targetAddr)
            }
            
            // Send SYNC
            val sync = buildSyncMessage(syncSequenceId)
            sendTo319(sync, targetAddr)
            
            // Send FOLLOW_UP with current time
            val followUp = buildFollowUpMessage(syncSequenceId, nowNs)
            sendTo320(followUp, targetAddr)
            
            syncSequenceId++
            
            delay(SYNC_INTERVAL_MS)
        }
    }
    
    /**
     * Build PTP common header (34 bytes)
     */
    private fun buildPtpHeader(msgType: Int, length: Int, seqId: Int, flags: Int, controlField: Int, logMessageInterval: Int): ByteArray {
        val header = ByteBuffer.allocate(34).order(ByteOrder.BIG_ENDIAN)
        
        header.put((0x10 or msgType).toByte()) // transportSpecific (0x1 for 802.1AS) | messageType
        header.put(0x02.toByte()) // versionPTP = 2
        header.putShort(length.toShort()) // messageLength
        header.put(0x00.toByte()) // domainNumber (0 for gPTP)
        header.put(0x00.toByte()) // reserved
        header.putShort(flags.toShort()) // flags
        header.putLong(0L) // correctionField
        header.putInt(0) // reserved
        header.putLong(clockId) // clockIdentity (bytes 20-27)
        header.putShort(1) // sourcePortID
        header.putShort(seqId.toShort()) // sequenceId
        header.put(controlField.toByte()) // controlField
        header.put(logMessageInterval.toByte()) // logMessageInterval
        
        return header.array()
    }
    
    /**
     * Build PTP Announce message (64 bytes).
     *
     * IEEE 1588-2008 / 802.1AS announce body starts at offset 34:
     *  34-35  currentUtcOffset             (2 bytes)
     *  36     reserved                     (1 byte)
     *  37     grandmasterPriority1         (1 byte)
     *  38-41  clockQuality                 (4 bytes: class/accuracy/variance)
     *  42     grandmasterPriority2         (1 byte)
     *  43-50  grandmasterIdentity          (8 bytes)
     *  51-52  stepsRemoved                 (2 bytes)
     *  53     timeSource                   (1 byte)
     *  54-55  grandmasterTimeBaseIndicator (2 bytes, 802.1AS, zero)
     *  56-59  accumulatedSubdomainChangeRate (4 bytes, 802.1AS, zero)
     *  60-63  zero padding
     * Total body 30 bytes: 34 + 30 = 64. ANNOUNCE has no originTimestamp.
     */
    internal fun buildAnnounceMessage(seqId: Int): ByteArray {
        // Announce: controlField=0x05 (Other), logMessageInterval=0x00 (1s)
        val header = buildPtpHeader(MSG_ANNOUNCE, 64, seqId, 0x0008, 0x05, 0x00)
        
        val msg = ByteBuffer.allocate(64).order(ByteOrder.BIG_ENDIAN)
        msg.put(header)
        
        msg.position(34)
        msg.putShort(37) // currentUtcOffset (TAI-UTC offset)
        msg.put(0x00.toByte()) // reserved
        msg.put(248.toByte()) // grandmasterPriority1 (Apple profile)
        msg.putInt(0xF8FEFFFF.toInt()) // clockQuality (class=248, accuracy=0xFE, variance=0xFFFF)
        msg.put(248.toByte()) // grandmasterPriority2
        msg.putLong(clockId) // grandmasterIdentity
        msg.putShort(0) // stepsRemoved
        msg.put(0xA0.toByte()) // timeSource (Internal Oscillator)
        
        // 802.1AS extensions (gmTimeBaseIndicator, accumulatedSubdomainChangeRate)
        // and the trailing padding stay zero: 34 + 30 = 64 bytes total, matching
        // the messageLength field and what nqptp expects.
        return msg.array()
    }
    
    /**
     * Build PTP Sync message (44 bytes)
     */
    internal fun buildSyncMessage(seqId: Int): ByteArray {
        // Sync: twoStepFlag + ptpTimescale (0x0208), controlField=0x00 (Sync),
        // logMessageInterval=0xFD (-3 = 125ms)
        val header = buildPtpHeader(MSG_SYNC, 44, seqId, 0x0208, 0x00, 0xFD)
        
        val msg = ByteBuffer.allocate(44).order(ByteOrder.BIG_ENDIAN)
        msg.put(header)
        // originTimestamp (10 bytes) - zeros for two-step clock
        
        return msg.array()
    }
    
    /**
     * Build PTP Follow_Up message (76 bytes)
     * 
     * Bytes 44-75 carry the IEEE 802.1AS Apple organization extension TLV:
     *  44-45  tlvType                    = 0x0003 (ORGANIZATION_EXTENSION)
     *  46-47  lengthField                = 28 (bytes following this field)
     *  48-50  organizationId             = 00:17:F2 (Apple)
     *  51-53  organizationSubtype        = 00:00:01
     *  54-63  lastGmPhaseChange          = 0 (10 bytes)
     *  64-67  lastGmFreqChange           = 0 (4 bytes)
     *  68-69  gmTimeBaseIndicator        = 0
     *  70-71  scaledLastGmFreqChange     = 0
     * Total TLV 32 bytes: 44 + 32 = 76.
     */
    internal fun buildFollowUpMessage(seqId: Int, originTimestampNs: Long): ByteArray {
        // Follow_Up: ptpTimescale only (0x0008), controlField=0x02 (Follow_Up),
        // logMessageInterval=0xFD (-3 = 125ms)
        val header = buildPtpHeader(MSG_FOLLOW_UP, 76, seqId, 0x0008, 0x02, 0xFD)
        
        val msg = ByteBuffer.allocate(76).order(ByteOrder.BIG_ENDIAN)
        msg.put(header)
        
        // preciseOriginTimestamp (10 bytes)
        val seconds = originTimestampNs / 1_000_000_000L
        val nanoseconds = (originTimestampNs % 1_000_000_000L).toInt()
        
        msg.position(34)
        msg.putShort(((seconds shr 32) and 0xFFFF).toShort()) // secondsHi
        msg.putInt((seconds and 0xFFFFFFFFL).toInt()) // secondsLo
        msg.putInt(nanoseconds) // nanoseconds
        
        // TLV: Organization Extension (Apple) - IEEE 802.1AS layout
        msg.position(44)
        msg.putShort(0x0003) // tlvType: ORGANIZATION_EXTENSION
        msg.putShort(28) // lengthField: 28 bytes following this field
        msg.put(byteArrayOf(0x00, 0x17, 0xF2.toByte())) // organizationId: Apple 00:17:F2
        msg.put(byteArrayOf(0x00, 0x00, 0x01)) // organizationSubtype
        
        // lastGmPhaseChange (10 bytes) - zeros
        msg.position(54)
        msg.putLong(0L)
        msg.putShort(0)
        // lastGmFreqChange (4 bytes) - zeros
        msg.putInt(0)
        // gmTimeBaseIndicator (2 bytes) - 0
        msg.putShort(0)
        // scaledLastGmFreqChange (2 bytes) - 0
        msg.putShort(0)
        
        return msg.array()
    }
    
    private fun sendTo319(data: ByteArray, address: InetAddress) {
        socket319?.send(DatagramPacket(data, data.size, address, PTP_EVENT_PORT))
    }
    
    private fun sendTo320(data: ByteArray, address: InetAddress) {
        socket320?.send(DatagramPacket(data, data.size, address, PTP_GENERAL_PORT))
    }
}
