package com.antigravity.androidmic.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * High-performance Digital Signal Processing (DSP) Anti-Feedback / Anti-Howling Engine:
 * 1. Frequency Shifter (+5 Hz Frequency Displacement to break acoustic standing-wave feedback loops)
 * 2. Voice Bandpass Filters (High-Pass 200 Hz & Low-Pass 4000 Hz to cut howling-prone resonances)
 * 3. Dynamic Howl / Sine-wave Resonance Detector with Automatic Ducking / Squelch
 */
class AntiHowlProcessor(private val sampleRate: Int = 48000) {

    var isEnabled: Boolean = true
    var isAggressiveMode: Boolean = false

    // 1. Voice Bandpass Biquad Filters (200Hz - 4000Hz)
    private val highPassFilter = BiquadFilter().apply {
        setHighPass(sampleRate.toDouble(), 220.0, 0.707)
    }
    private val lowPassFilter = BiquadFilter().apply {
        setLowPass(sampleRate.toDouble(), 3800.0, 0.707)
    }
    private val antiResonanceNotch = BiquadFilter().apply {
        // Typical mobile phone acoustic resonance cavity around 3.1 kHz
        setNotch(sampleRate.toDouble(), 3150.0, 3.5)
    }

    // 2. Frequency Shifter (+5 Hz shift via dual-pointer crossfade circular delay)
    private val delayBufferSize = 2048
    private val delayBuffer = FloatArray(delayBufferSize)
    private var writeIndex = 0
    private var readPhase1 = 0.0
    // Frequency shift in Hz: +5.0 Hz
    private val shiftHz = 5.5
    // Delta per sample for reading
    private val readSpeed = 1.0 + (shiftHz / sampleRate.toDouble())

    // 3. Howl / Resonance Detection & Squelch
    private var sineWaveStreak = 0
    private var squelchGain = 1.0f
    private val squelchRecovery = 0.05f

    /**
     * Process 16-bit PCM audio buffer to eliminate feedback howling
     */
    fun process(buffer: ShortArray, readSize: Int) {
        if (!isEnabled || readSize <= 0) return

        var sumSquare = 0.0
        var maxPeak = 0.0f

        for (i in 0 until readSize) {
            val rawSample = buffer[i].toFloat() / 32768.0f
            val absSample = abs(rawSample)
            if (absSample > maxPeak) maxPeak = absSample
            sumSquare += rawSample * rawSample

            // Step A: Bandpass filtering (removes sub-bass rumble & ultrasonic shrieks)
            var sample = highPassFilter.process(rawSample.toDouble())
            sample = lowPassFilter.process(sample)

            if (isAggressiveMode) {
                sample = antiResonanceNotch.process(sample)
            }

            // Step B: Frequency Shifting (+5.5 Hz shift)
            // Store filtered sample in circular buffer
            delayBuffer[writeIndex] = sample.toFloat()

            // Calculate two read pointers 180 degrees apart in the window
            val windowLen = delayBufferSize / 2.0
            val p1 = readPhase1 % windowLen
            val p2 = (readPhase1 + windowLen / 2.0) % windowLen

            // Triangular crossfade weights
            val w1 = if (p1 < windowLen / 2.0) (p1 / (windowLen / 2.0)) else (2.0 - p1 / (windowLen / 2.0))
            val w2 = 1.0 - w1

            val readIdx1 = ((writeIndex - p1.toInt() + delayBufferSize) % delayBufferSize)
            val readIdx2 = ((writeIndex - p2.toInt() + delayBufferSize) % delayBufferSize)

            val shiftedSample = (delayBuffer[readIdx1] * w1 + delayBuffer[readIdx2] * w2).toFloat()

            // Advance pointers
            writeIndex = (writeIndex + 1) % delayBufferSize
            readPhase1 += (readSpeed - 1.0)
            if (readPhase1 >= windowLen * 1000.0) {
                readPhase1 %= windowLen
            }

            // Apply Squelch gain if howl is detected
            val finalSample = shiftedSample * squelchGain

            // Convert back to PCM 16-bit
            val outputInt = (finalSample * 32767.0f).toInt()
            buffer[i] = outputInt.coerceIn(-32768, 32767).toShort()
        }

        // Step C: Analyze buffer for Sine-Wave Resonance (Howling Signature)
        val rms = sqrt(sumSquare / readSize).toFloat()
        if (rms > 0.08f) { // Only check if sound is loud enough
            val crestFactor = if (rms > 0.001f) (maxPeak / rms) else 10f
            // Pure sine wave has crest factor ~1.414 (between 1.35 and 1.6)
            if (crestFactor in 1.32f..1.65f) {
                sineWaveStreak++
                if (sineWaveStreak >= 3) {
                    // Howling detected! Immediately duck volume by 60%
                    squelchGain = if (isAggressiveMode) 0.25f else 0.45f
                }
            } else {
                sineWaveStreak = 0
            }
        } else {
            sineWaveStreak = 0
        }

        // Smoothly recover squelch gain back to 1.0
        if (squelchGain < 1.0f) {
            squelchGain += (1.0f - squelchGain) * squelchRecovery
            if (squelchGain > 0.98f) squelchGain = 1.0f
        }
    }

    fun reset() {
        highPassFilter.reset()
        lowPassFilter.reset()
        antiResonanceNotch.reset()
        delayBuffer.fill(0f)
        writeIndex = 0
        readPhase1 = 0.0
        squelchGain = 1.0f
        sineWaveStreak = 0
    }

    /**
     * Biquad Filter (Direct Form II Transposed for highest numerical stability)
     */
    class BiquadFilter {
        private var b0 = 1.0
        private var b1 = 0.0
        private var b2 = 0.0
        private var a1 = 0.0
        private var a2 = 0.0

        private var d1 = 0.0
        private var d2 = 0.0

        fun process(input: Double): Double {
            val output = b0 * input + d1
            d1 = b1 * input - a1 * output + d2
            d2 = b2 * input - a2 * output
            return output
        }

        fun reset() {
            d1 = 0.0
            d2 = 0.0
        }

        fun setHighPass(sampleRate: Double, cutoffFreq: Double, q: Double = 0.707) {
            val w0 = 2.0 * PI * cutoffFreq / sampleRate
            val cosW0 = cos(w0)
            val sinW0 = sin(w0)
            val alpha = sinW0 / (2.0 * q)

            val a0 = 1.0 + alpha
            b0 = ((1.0 + cosW0) / 2.0) / a0
            b1 = (-(1.0 + cosW0)) / a0
            b2 = ((1.0 + cosW0) / 2.0) / a0
            a1 = (-2.0 * cosW0) / a0
            a2 = (1.0 - alpha) / a0
        }

        fun setLowPass(sampleRate: Double, cutoffFreq: Double, q: Double = 0.707) {
            val w0 = 2.0 * PI * cutoffFreq / sampleRate
            val cosW0 = cos(w0)
            val sinW0 = sin(w0)
            val alpha = sinW0 / (2.0 * q)

            val a0 = 1.0 + alpha
            b0 = ((1.0 - cosW0) / 2.0) / a0
            b1 = (1.0 - cosW0) / a0
            b2 = ((1.0 - cosW0) / 2.0) / a0
            a1 = (-2.0 * cosW0) / a0
            a2 = (1.0 - alpha) / a0
        }

        fun setNotch(sampleRate: Double, freq: Double, q: Double = 4.0) {
            val w0 = 2.0 * PI * freq / sampleRate
            val cosW0 = cos(w0)
            val sinW0 = sin(w0)
            val alpha = sinW0 / (2.0 * q)

            val a0 = 1.0 + alpha
            b0 = 1.0 / a0
            b1 = (-2.0 * cosW0) / a0
            b2 = 1.0 / a0
            a1 = (-2.0 * cosW0) / a0
            a2 = (1.0 - alpha) / a0
        }
    }
}
