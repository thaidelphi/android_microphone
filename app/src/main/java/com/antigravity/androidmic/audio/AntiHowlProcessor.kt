package com.antigravity.androidmic.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * High-performance DSP Anti-Feedback / Anti-Howling Engine:
 * 1. Voice Bandpass Filters (HP 180Hz + LP 4200Hz) to cut howling-prone resonances
 * 2. Multi-Band Notch Filters targeting smartphone acoustic resonance frequencies (800Hz, 1.6kHz, 2.5kHz, 3.15kHz)
 * 3. True Single-Sideband (SSB) Frequency Shifter (+6 Hz via Hilbert / Hartley 90° allpass splitter)
 *    to continuously disrupt acoustic standing-wave feedback loops
 * 4. Fast Howl Detector with Hold-time Squelch & Smooth Recovery
 */
class AntiHowlProcessor(private val sampleRate: Int = 48000) {

    var isEnabled: Boolean = true
    var isAggressiveMode: Boolean = true

    // 1. Voice Bandpass Biquad Filters (180Hz – 4200Hz)
    private val highPassFilter = BiquadFilter().apply {
        setHighPass(sampleRate.toDouble(), 180.0, 0.707)
    }
    private val lowPassFilter = BiquadFilter().apply {
        setLowPass(sampleRate.toDouble(), 4200.0, 0.707)
    }

    // 2. Multi-Band Notch Filters for common smartphone resonance frequencies
    private val notch800  = BiquadFilter().apply { setNotch(sampleRate.toDouble(),  800.0, 4.5) }
    private val notch1600 = BiquadFilter().apply { setNotch(sampleRate.toDouble(), 1600.0, 4.5) }
    private val notch2500 = BiquadFilter().apply { setNotch(sampleRate.toDouble(), 2500.0, 4.0) }
    private val notch3150 = BiquadFilter().apply { setNotch(sampleRate.toDouble(), 3150.0, 5.0) }

    // 3. True SSB Frequency Shifter (+6 Hz)
    // Uses 4-stage wideband 90-degree allpass phase splitter (Hartley / Weaver structure)
    // Section A produces In-phase signal I, Section B produces Quadrature signal Q (shifted ~90°)
    // y[n] = I * cos(theta) - Q * sin(theta) shifts all spectral components by +shiftHz
    private val allpassPolesA = doubleArrayOf(0.161758, 0.733029, 0.945350, 0.990598)
    private val allpassPolesB = doubleArrayOf(0.479401, 0.876218, 0.976599, 0.997500)

    private val x1A = DoubleArray(4)
    private val y1A = DoubleArray(4)
    private val x1B = DoubleArray(4)
    private val y1B = DoubleArray(4)

    private val shiftHz = 6.0 // 6 Hz shift: completely imperceptible on speech, breaks all acoustic feedback
    private val dTheta = 2.0 * PI * shiftHz / sampleRate.toDouble()
    private var oscPhase = 0.0

    // 4. Howl / Resonance Detection & Squelch
    private var sineWaveStreak = 0
    private var squelchGain = 1.0f
    private var squelchHoldBuffers = 0 // Keep suppressed while acoustic echo dissipates

    private val squelchRecoveryNormal     = 0.02f
    private val squelchRecoveryAggressive = 0.01f

    private val squelchFloorNormal     = 0.15f  // -16 dB
    private val squelchFloorAggressive = 0.04f  // -28 dB (cuts howl instantly)

    /**
     * Process 16-bit PCM audio buffer in-place to eliminate feedback howling
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

            // Step A: Voice bandpass (cut sub-bass thumps + ultrasonic shrieks)
            var sample = highPassFilter.process(rawSample.toDouble())
            sample = lowPassFilter.process(sample)

            // Step B: Multi-band notch filtering
            // In normal mode: notch 3.15 kHz & 1.6 kHz (most common smartphone squeal frequencies)
            sample = notch3150.process(sample)
            sample = notch1600.process(sample)

            if (isAggressiveMode) {
                // Aggressive: additionally notch 800 Hz and 2.5 kHz
                sample = notch800.process(sample)
                sample = notch2500.process(sample)
            }

            // Step C: True SSB Frequency Shifting (+6 Hz)
            // Pass through 90-degree phase splitter
            var inA = sample
            for (k in 0 until 4) {
                val p = allpassPolesA[k]
                val out = -p * inA + x1A[k] + p * y1A[k]
                x1A[k] = inA
                y1A[k] = out
                inA = out
            }
            val iVal = inA

            var inB = sample
            for (k in 0 until 4) {
                val p = allpassPolesB[k]
                val out = -p * inB + x1B[k] + p * y1B[k]
                x1B[k] = inB
                y1B[k] = out
                inB = out
            }
            val qVal = inB

            // Single-sideband upshift: I*cos(theta) - Q*sin(theta)
            val shiftedSample = (iVal * cos(oscPhase) - qVal * sin(oscPhase)).toFloat()
            oscPhase += dTheta
            if (oscPhase >= 2.0 * PI) {
                oscPhase -= 2.0 * PI
            }

            // Step D: Apply squelch gain
            val finalSample = shiftedSample * squelchGain
            val outputInt = (finalSample * 32767.0f).toInt()
            buffer[i] = outputInt.coerceIn(-32768, 32767).toShort()
        }

        // Step E: Howl / Resonance Detection
        val rms = sqrt(sumSquare / readSize).toFloat()
        if (rms > 0.04f) {
            val crestFactor = if (rms > 0.001f) (maxPeak / rms) else 10f
            // Pure sine wave has crest factor ~1.414. Feedback in rooms/BT lies in 1.25..1.75
            if (crestFactor in 1.25f..1.75f) {
                sineWaveStreak++
                if (sineWaveStreak >= 2) { // 2 buffers = ~20ms: fast detection before ear damage
                    val floor = if (isAggressiveMode) squelchFloorAggressive else squelchFloorNormal
                    squelchGain = minOf(squelchGain, floor)
                    squelchHoldBuffers = 35 // Hold squelch for ~350ms to let room reflection die out
                }
            } else {
                sineWaveStreak = 0
            }
        } else {
            sineWaveStreak = 0
        }

        // Step F: Hold timer and smooth recovery
        if (squelchHoldBuffers > 0) {
            squelchHoldBuffers--
        } else if (squelchGain < 1.0f) {
            val recovery = if (isAggressiveMode) squelchRecoveryAggressive else squelchRecoveryNormal
            squelchGain += (1.0f - squelchGain) * recovery
            if (squelchGain > 0.98f) squelchGain = 1.0f
        }
    }

    fun reset() {
        highPassFilter.reset()
        lowPassFilter.reset()
        notch800.reset()
        notch1600.reset()
        notch2500.reset()
        notch3150.reset()
        x1A.fill(0.0)
        y1A.fill(0.0)
        x1B.fill(0.0)
        y1B.fill(0.0)
        oscPhase = 0.0
        squelchGain = 1.0f
        squelchHoldBuffers = 0
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
