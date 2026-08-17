package com.airplay.streamer.raop

/**
 * Centralized on-the-wire constants shared across the `raop/` (AirPlay 1) and
 * `airplay2/` (AirPlay 2) stacks.
 *
 * Every value in this object is VERIFIED against the existing source files it
 * was extracted from (see the per-constant KDoc for the original definition
 * sites). Do not change a value here without changing the corresponding
 * definitions at those sites, and vice versa.
 *
 * Phase 1 refactor: consuming agents switch the duplicated `const val` /
 * literal definitions in RaopClient, AirPlay2Client, RtpStreamer, RtspClient,
 * AudioCaptureService, NtpTiming, PtpMasterClock, ProtocolResolver and
 * HapSession over to these constants.
 */
object WireConstants {

    /**
     * Audio capture/streaming format, shared by the RAOP path
     * (RaopClient.kt, AudioCaptureService.kt) and the AirPlay 2 path
     * (RtpStreamer.kt, NtpTiming.kt). L16/44100/2, 352 frames per packet.
     */
    object AudioFormat {
        /** RaopClient.kt:36, RtpStreamer.kt:72, AudioCaptureService.kt:47. */
        const val SAMPLE_RATE = 44100

        /** RaopClient.kt:37. */
        const val CHANNELS = 2

        /** RaopClient.kt:38. */
        const val BITS_PER_SAMPLE = 16

        /** RaopClient.kt:39, AudioCaptureService.kt:50. */
        const val FRAMES_PER_PACKET = 352

        /**
         * RtpStreamer.kt:71, NtpTiming.kt:12. Same value as
         * [FRAMES_PER_PACKET]; both names exist in the codebase.
         */
        const val SAMPLES_PER_FRAME = 352

        /** AudioCaptureService.kt:51 (16-bit stereo = 4 bytes). */
        const val BYTES_PER_FRAME = 4

        /**
         * AudioCaptureService.kt:52 `BUFFER_SIZE = FRAMES_PER_PACKET * BYTES_PER_FRAME`,
         * RaopClient.kt:442 packet-size literal `1408`.
         */
        const val BYTES_PER_PACKET = 1408
    }

    /**
     * Network ports, shared by the RAOP path (RaopClient.kt, MainActivity.kt,
     * SettingsActivity.kt, AirPlayDiscovery.kt), the AirPlay 2 path
     * (AirPlay2Client.kt, RtspClient.kt, ProtocolResolver.kt) and the PTP
     * timing path (PtpMasterClock.kt).
     */
    object Ports {
        /** RAOP/RTSP default port. MainActivity.kt:123, SettingsActivity.kt:206. */
        const val RAOP = 5000

        /** AirPlay 2 control port. AirPlay2Client.kt:43, RtspClient.kt:21, ProtocolResolver.kt:29. */
        const val AIRPLAY2 = 7000

        /** PtpMasterClock.kt:22. */
        const val PTP_EVENT = 319

        /** PtpMasterClock.kt:23. */
        const val PTP_GENERAL = 320
    }

    /**
     * RTP wire-format constants, shared by the RAOP RTP sender
     * (RaopClient.kt:620-631) and the AirPlay 2 RTP sender
     * (RtpStreamer.kt:228-235).
     */
    object Rtp {
        /** RtpStreamer.kt:73. */
        const val SSRC = 0x55667788

        /** RaopClient.kt:596 SDP `RTP/AVP 96`, RtpStreamer.kt:232 `0x60`. */
        const val PAYLOAD_TYPE = 96

        /** RTP V=2 header byte. RaopClient.kt:622 `0x80`, RtpStreamer.kt:231 `0x80`. */
        const val VERSION_BYTE = 0x80
    }

    /**
     * NTP timestamp constants used for AirPlay timing/sync packets
     * (RaopClient.kt:668-669, 715).
     */
    object Ntp {
        /** Seconds between 1900-01-01 (NTP epoch) and 1970-01-01 (Unix epoch). RaopClient.kt:668, 715. */
        const val EPOCH_OFFSET = 2208988800L

        /** Scale used to convert sub-second time to the 32-bit NTP fraction. RaopClient.kt:669, 715. */
        const val FRACTION_SCALE = 4294967296.0
    }

    /**
     * HAP (HomeKit Accessory Protocol) frame/tag sizes for encrypted RTSP
     * traffic in the AirPlay 2 path (HapSession.kt:14-15). The same 16-byte
     * tag size applies to ChaCha20-Poly1305 audio encryption in RtpStreamer.kt.
     */
    object Hap {
        /** HapSession.kt:14. */
        const val FRAME_LENGTH = 1024

        /** HapSession.kt:15. */
        const val AUTH_TAG_LENGTH = 16
    }

    /**
     * Timing/health-monitor intervals and RTSP socket timeouts. These values
     * intentionally differ between the RAOP path (RaopClient.kt) and the
     * capture service (AudioCaptureService.kt) — both are preserved with
     * descriptive names.
     */
    object Timing {
        /** RaopClient.kt:61 — RAOP health monitor sleeps 3s between probes. */
        const val RAOP_HEALTH_CHECK_INTERVAL_MS = 3000L

        /** AudioCaptureService.kt:55 — capture-service health check interval. */
        const val CAPTURE_SERVICE_HEALTH_CHECK_INTERVAL_MS = 10_000L

        /** RaopClient.kt:126, 738 — RTSP soTimeout for the streaming session and post-health-probe restore. */
        const val RTSP_SO_TIMEOUT_MS = 10000

        /** RaopClient.kt:110 — RTSP soTimeout on the first (OPTIONS) connection. */
        const val RTSP_SO_TIMEOUT_CONNECTION1_MS = 5000

        /** RaopClient.kt:516 — RTSP soTimeout set before TEARDOWN. */
        const val RTSP_SO_TIMEOUT_TEARDOWN_MS = 2000

        /** RaopClient.kt:734 — RTSP soTimeout while probing the health of the stream. */
        const val RTSP_SO_TIMEOUT_HEALTH_PROBE_MS = 100

        /** PtpMasterClock.kt:24 — PTP sync/announce interval (~8 messages/second). */
        const val PTP_SYNC_INTERVAL_MS = 125L
    }

    /**
     * AirPlay 2 stream pacing, shared by the AP2 RTP sender
     * (RtpStreamer.kt:71-78) and its timing path.
     */
    object Streaming {
        /** RtpStreamer.kt:74 — receiver latency in frames (~1.75s at 44.1kHz). */
        const val LATENCY_FRAMES = 77175

        /** RtpStreamer.kt:78 — anchor packets sent every 125 packets (~1s). */
        const val ANCHOR_INTERVAL_FRAMES = 125

        /** RtpStreamer.kt:76 `DEFAULT_PREBUFFER_FRAMES` — prebuffer flush threshold (~1s). */
        const val PREBUFFER_FRAMES = 125

        /** RtpStreamer.kt:77 `DEFAULT_PREBUFFER_TIMEOUT_MS` — force-flush if prebuffer never fills. */
        const val PREBUFFER_TIMEOUT_MS = 2000L
    }
}
