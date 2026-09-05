package com.antigravity.androidmic.audio

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tanh

class AudioDspProcessor(val sampleRate: Int = 48000) {
    // Gain multiplier (1.0 = normal unity gain, up to 6.0x)
    var gain: Float = 1.0f

    // Noise gate threshold: 0.02 (2% default prevents acoustic feedback during silence)
    var noiseGateThreshold: Float = 0.02f

    // Anti-Howling / Feedback Eliminator Engine (aggressive mode by default for safety)
    val antiHowl = AntiHowlProcessor(sampleRate).apply {
        isEnabled = true
        isAggressiveMode = true
    }

    // Vocal Karaoke Echo / Reverb Delay Engine
    val echo = EchoProcessor(sampleRate)

    // Noise gate envelope follower and hold timer for smooth vocal delivery
    private var gateEnvelope: Float = 1.0f
    private var gateHoldSamples: Int = 0
    private val gateAttack: Float = 0.1f    // Smooth opening
    private val gateRelease: Float = 0.008f // Smooth closing without abrupt dropouts

    // Real-time audio metrics
    var currentRmsNormalized: Float = 0.0f
        private set
    var currentPeakNormalized: Float = 0.0f
        private set
    var currentDb: Float = -100.0f
        private set

    /**
     * Process a 16-bit PCM audio buffer in-place:
     * 1. Calculate input metrics (RMS & Peak)
     * 2. Apply Noise Gate (with Hold time)
     * 3. Apply Anti-Howl Frequency Shift & Resonance Filtering
     * 4. Apply Karaoke Echo / Vocal Reverb
     * 5. Apply Digital Gain with Soft-Clipping Limiter (prevents harsh distortion)
     */
    fun process(buffer: ShortArray, readSize: Int) {
        if (readSize <= 0) return

        var sumSquares = 0.0
        var maxPeak = 0

        // 1. First pass: Measure RMS and peak of raw input
        for (i in 0 until readSize) {
            val sample = buffer[i].toInt()
            sumSquares += sample * sample
            val absSample = abs(sample)
            if (absSample > maxPeak) {
                maxPeak = absSample
            }
        }

        val rms = sqrt(sumSquares / readSize).toFloat()
        val normalizedRms = min(1.0f, rms / 32768.0f)
        val normalizedPeak = min(1.0f, maxPeak / 32768.0f)

        currentRmsNormalized = normalizedRms
        currentPeakNormalized = normalizedPeak
        currentDb = if (rms > 0f) {
            max(-80.0f, (20.0f * log10(rms / 32768.0f)))
        } else {
            -80.0f
        }

        // 2. Process Noise Gate
        if (noiseGateThreshold <= 0.001f) {
            // Noise Gate is OFF: full passthrough
            gateEnvelope = 1.0f
            gateHoldSamples = 0
        } else {
            // Noise Gate is ON: evaluate threshold with 150ms hold time
            if (normalizedPeak >= noiseGateThreshold) {
                gateHoldSamples = (sampleRate * 0.15).toInt()
            } else if (gateHoldSamples > 0) {
                gateHoldSamples -= readSize
            }

            val targetGate = if (gateHoldSamples > 0 || normalizedPeak >= noiseGateThreshold) 1.0f else 0.0f

            for (i in 0 until readSize) {
                gateEnvelope += (targetGate - gateEnvelope) * (if (targetGate > gateEnvelope) gateAttack else gateRelease)
                val inputFloat = (buffer[i] / 32768.0f) * gateEnvelope
                val outputInt = (inputFloat * 32767.0f).toInt()
                buffer[i] = outputInt.coerceIn(-32768, 32767).toShort()
            }
        }

        // 4. Anti-Howling DSP: Frequency Shifter + Bandpass + Resonance Squelch
        if (antiHowl.isEnabled) {
            antiHowl.process(buffer, readSize)
        }

        // 5. Vocal Karaoke Echo DSP
        if (echo.isEnabled) {
            echo.process(buffer, readSize)
        }

        // 6. Digital Gain Boost + Soft-Clipping Limiter (tanh saturation)
        for (i in 0 until readSize) {
            val inputFloat = buffer[i] / 32768.0f
            val amplifiedSample = inputFloat * gain

            val limitedSample = if (abs(amplifiedSample) <= 0.7f) {
                amplifiedSample
            } else {
                tanh(amplifiedSample.toDouble()).toFloat()
            }

            val outputInt = (limitedSample * 32767.0f).toInt()
            buffer[i] = outputInt.coerceIn(-32768, 32767).toShort()
        }
    }

    fun reset() {
        antiHowl.reset()
        echo.reset()
    }
}
