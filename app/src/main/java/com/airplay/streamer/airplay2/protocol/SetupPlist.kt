package com.airplay.streamer.airplay2.protocol

import com.airplay.streamer.airplay2.TimingMode
import com.airplay.streamer.raop.WireConstants
import com.dd.plist.NSData
import com.dd.plist.NSDictionary

/**
 * Builders for the two AirPlay 2 SETUP request bodies (event channel and
 * audio stream). Pure plist construction with no socket I/O, extracted from
 * `AirPlay2Client.setupEventChannel` / `setupAudioStream` so the wire bodies
 * are independently readable and testable.
 */
object SetupPlist {

    /** Static sender identity advertised to the receiver (transient pairing). */
    const val DEVICE_ID = "AA:BB:CC:DD:EE:FF"

    /** FourCC 'alac' for the (uncompressed) ALAC audio format. */
    const val AUDIO_FORMAT_ALAC = 1633771873

    /**
     * Event-channel SETUP body. Establishes the control session and advertises
     * the sender's timing protocol (PTP or NTP) plus its identity.
     */
    fun eventChannel(sessionUuid: String, timingMode: TimingMode, localAddress: String): NSDictionary =
        NSDictionary().apply {
            put("deviceID", DEVICE_ID)
            put("sessionUUID", sessionUuid)
            put("timingProtocol", if (timingMode == TimingMode.PTP) "PTP" else "NTP")
            put("timingPeerInfo", NSDictionary().apply {
                put("Addresses", arrayOf(localAddress))
                put("ID", DEVICE_ID)
            })
            put("groupUUID", sessionUuid)
            put("groupContainsGroupLeader", false)
            put("isMultiSelectAirPlay", true)
            put("macAddress", DEVICE_ID)
            put("model", "iPhone14,3")
            put("name", "centuryplay")
            put("osBuildVersion", "20F66")
            put("osName", "iPhone OS")
            put("osVersion", "16.5")
            put("senderSupportsRelay", false)
            put("sourceVersion", "690.7.1")
            put("statsCollectionEnabled", false)
        }

    /**
     * Audio-stream SETUP body. [sharedSecret] is the local ChaCha20 key (the
     * receiver echoes ports back; the local secret is authoritative), and
     * [streamConnectionId] is the RTSP CSeq anchoring the stream connection.
     */
    fun audioStream(sharedSecret: ByteArray, streamConnectionId: Long): NSDictionary =
        NSDictionary().apply {
            put("streams", arrayOf(
                NSDictionary().apply {
                    // ALAC (uncompressed) per the AP2 audio SETUP spec:
                    // audioFormat = 'alac' = 1633771873 (NOT the RAOP L16
                    // bitmask 0x100000 - a 'format' key may carry the
                    // bitmask, but 'audioFormat' must be the fourcc), ct=2
                    // (ALAC; ct=1 is PCM), type=96, spf=352.
                    // refs: airplay2-rs AIRPLAY_2_SPEC.md, pyatv raop2.
                    put("type", WireConstants.Rtp.PAYLOAD_TYPE)
                    put("audioFormat", AUDIO_FORMAT_ALAC)
                    put("audioMode", "default")
                    put("ct", 2)
                    put("spf", WireConstants.AudioFormat.SAMPLES_PER_FRAME)
                    put("shk", NSData(sharedSecret))
                    put("isMedia", true)
                    put("latencyMax", 88200)
                    put("latencyMin", 11025)
                    put("supportsDynamicStreamID", true)
                    put("streamConnectionID", streamConnectionId)
                }
            ))
        }
}
