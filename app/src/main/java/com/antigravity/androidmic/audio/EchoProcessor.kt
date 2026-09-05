package com.antigravity.androidmic.audio

import kotlin.math.min

/**
 * Real-time Vocal Karaoke Echo / Reverb Delay Processor:
 * - Circular delay buffer with smooth fractional sample indexing
 * - Adjustable Delay Time (80 ms - 450 ms)
 * - Adjustable Feedback Decay (number of repeats)
 * - Wet/Dry Mix level
 * - Lowpass Damping filter (creates a warm, studio-grade analog tape/room echo)
 */
class EchoProcessor(private val sampleRate: Int = 48000) {

    var isEnabled: Boolean = true

    // Delay time in milliseconds (100ms - 400ms, typical karaoke is ~200-240ms)
    var delayMs: Int = 220
        set(value) {
            field = value.coerceIn(50, 600)
        }

    // Echo feedback / repeats (0.0 = single echo, 0.7 = long trailing repeats)
    var decay: Float = 0.40f
        set(value) {
            field = value.coerceIn(0.0f, 0.75f)
        }

    // Wet mix ratio (0.0 = pure voice, 0.6 = heavy karaoke echo)
    var wetMix: Float = 0.35f
        set(value) {
            field = value.coerceIn(0.0f, 0.85f)
        }

    // Damping factor for smooth warm sound on each reflection
    var damping: Float = 0.35f

    // 1-second circular buffer
    private val maxDelaySamples = sampleRate
    private val delayBuffer = FloatArray(maxDelaySamples)
    private var writeIndex = 0
    private var dampedFeedback = 0.0f

    /**
     * Process 16-bit PCM buffer with Karaoke Vocal Echo
     */
    fun process(buffer: ShortArray, readSize: Int) {
        if (!isEnabled || readSize <= 0 || wetMix <= 0.001f) return

        val delaySamples = ((delayMs / 1000.0) * sampleRate).toInt().coerceIn(1, maxDelaySamples - 1)
        val dryLevel = 1.0f - (wetMix * 0.4f)

        for (i in 0 until readSize) {
            val inputFloat = buffer[i] / 32768.0f

            // Read delayed sample from circular buffer
            val readIdx = (writeIndex - delaySamples + maxDelaySamples) % maxDelaySamples
            val delayedSample = delayBuffer[readIdx]

            // Apply 1-pole Low-Pass Filter on feedback to make repeats warm and smooth
            dampedFeedback += (delayedSample - dampedFeedback) * (1.0f - damping)

            // Calculate feedback sample to write back
            val feedbackSample = inputFloat + (dampedFeedback * decay)
            delayBuffer[writeIndex] = feedbackSample

            // Advance circular write pointer
            writeIndex = (writeIndex + 1) % maxDelaySamples

            // Output wet/dry mix
            val outputFloat = (inputFloat * dryLevel) + (delayedSample * wetMix)

            // Convert back to 16-bit PCM integer with saturation clamp
            val outputInt = (outputFloat * 32767.0f).toInt()
            buffer[i] = outputInt.coerceIn(-32768, 32767).toShort()
        }
    }

    fun reset() {
        delayBuffer.fill(0.0f)
        writeIndex = 0
        dampedFeedback = 0.0f
    }
}
