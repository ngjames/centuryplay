package com.airplay.streamer.airplay2.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Test-only sine-wave audio generators, moved out of production
 * [AlacEncoder] (F2 review: test helpers belong in the test source set).
 */
internal object TestAudioFrames {

    /** Generate a sine-wave PCM frame (16-bit LE stereo, 1408 bytes). */
    fun generateSineFrame(frequency: Double = 440.0, frameIndex: Int = 0): ByteArray {
        val buffer = ByteBuffer.allocate(AlacEncoder.BYTES_PER_FRAME).order(ByteOrder.LITTLE_ENDIAN)

        val samplesOffset = frameIndex * AlacEncoder.SAMPLES_PER_FRAME

        for (i in 0 until AlacEncoder.SAMPLES_PER_FRAME) {
            val t = (samplesOffset + i).toDouble() / AlacEncoder.SAMPLE_RATE
            val sample = (Math.sin(2.0 * Math.PI * frequency * t) * 32767 * 0.5).toInt().toShort()

            buffer.putShort(sample)
            buffer.putShort(sample)
        }

        return buffer.array()
    }

    /** Generate encoded ALAC frames for `durationSeconds` of a sine tone. */
    fun generateTestFrames(durationSeconds: Double, frequency: Double = 440.0): List<ByteArray> {
        val totalSamples = (durationSeconds * AlacEncoder.SAMPLE_RATE).toInt()
        val totalFrames = (totalSamples + AlacEncoder.SAMPLES_PER_FRAME - 1) / AlacEncoder.SAMPLES_PER_FRAME

        return (0 until totalFrames).map { frameIndex ->
            val pcm = generateSineFrame(frequency, frameIndex)
            AlacEncoder.encodeFrame(pcm)
        }
    }
}
