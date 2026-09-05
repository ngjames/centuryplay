package com.airplay.streamer.util

import java.io.Closeable

/**
 * Canonical byte-formatting and resource helpers shared across the `raop/`
 * (AirPlay 1) and `airplay2/` (AirPlay 2) stacks.
 *
 * Phase 1 refactor: consuming agents switch the four divergent hex-formatting
 * implementations over to the helpers below, matching the exact output
 * formats they replace (see per-function KDoc for the original sites).
 */
object ByteArrayFormat {

    /**
     * Lowercase contiguous hex, two digits per byte. This is the canonical
     * `toHexString()` format used for byte-level logging and IDs; it matches
     * Chacha20Cipher.kt:28 (`joinToString("") { "%02x".format(it) }`) and the
     * per-byte formatting inside RaopClient.hexDump.
     *
     * Example: `ByteArray(2) { 0x0A }` -> `"0a0a"`.
     */
    fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

    /**
     * Uppercase contiguous hex, two digits per byte. Matches the hex-ID
     * format at RaopClient.kt:549-553 (`generateHexId`) and the MAC-like
     * byte formatting at airplay2/util/NetworkUtils.kt:67.
     *
     * Example: `ByteArray(2) { 0x0A }` -> `"0A0A"`.
     */
    fun ByteArray.toHexId(): String = joinToString("") { "%02X".format(it) }

    /**
     * Space-separated lowercase hex dump, with a `" ..."` suffix when the
     * array exceeds [maxBytes]. Matches RaopClient.hexDump (RaopClient.kt:362-365),
     * the format used for protocol-body log dumps.
     *
     * @param maxBytes maximum bytes to render before truncating (default: whole array).
     */
    fun ByteArray.toHexDump(maxBytes: Int = size): String {
        val shown = take(maxBytes).joinToString(" ") { "%02x".format(it) }
        return if (size > maxBytes) "$shown ..." else shown
    }

    /**
     * Close a [Closeable] ignoring every failure. Replaces the repeated
     * `try { x?.close() } catch (e: Exception) {}` pattern, e.g.
     * RaopClient.kt:522-526.
     *
     * @param closeable the resource to close, or `null` to no-op.
     */
    fun closeQuietly(closeable: Closeable?) {
        try {
            closeable?.close()
        } catch (_: Exception) {
            // Ignore: closing is best-effort on the way out.
        }
    }
}
