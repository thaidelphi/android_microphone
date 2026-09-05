package com.antigravity.androidmic.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Process
import android.util.Log

class AudioLoopEngine(
    private val context: Context,
    val dspProcessor: AudioDspProcessor = AudioDspProcessor()
) {
    private var isRunning = false
    private var loopThread: Thread? = null

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var autoGainControl: AutomaticGainControl? = null

    val deviceManager = AudioDeviceManager(context)

    init {
        deviceManager.onScoAudioConnected = {
            if (isRunning) {
                Log.i(TAG, "Bluetooth SCO connected callback received: restarting AudioRecord to bind Bluetooth mic stream...")
                restartAudioRecord()
            }
        }
    }

    var preferredInputDevice: AudioDeviceInfo? = null
        set(value) {
            val wasBt = field?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || field?.type == 26 || field?.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || field?.id == 9999
            val isBt = value?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || value?.type == 26 || value?.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || value?.id == 9999
            field = value
            deviceManager.routeToInput(value)
            if (isRunning) {
                if (wasBt != isBt) {
                    restart()
                } else {
                    restartAudioRecord()
                }
            }
        }

    var preferredOutputDevice: AudioDeviceInfo? = null
        set(value) {
            field = value
            deviceManager.routeToOutput(value)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && isRunning) {
                audioTrack?.preferredDevice = value
            }
        }

    var forceSpeaker: Boolean = true
        set(value) {
            field = value
            if (value) {
                preferredOutputDevice = deviceManager.getBuiltinSpeakerDevice()
            } else {
                preferredOutputDevice = null
            }
        }

    var onAudioLevelUpdated: ((peak: Float, rms: Float, db: Float) -> Unit)? = null
    var onError: ((message: String) -> Unit)? = null

    companion object {
        private const val TAG = "AudioLoopEngine"
        private const val SAMPLE_RATE = 48000
        private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var actualRecordSampleRate: Int = SAMPLE_RATE

    val isActive: Boolean
        get() = isRunning

    private fun upsample16kTo48k(input: ShortArray, inCount: Int, output: ShortArray): Int {
        if (inCount <= 0) return 0
        var outIdx = 0
        val maxOut = output.size
        for (i in 0 until inCount) {
            if (outIdx + 3 > maxOut) break
            val current = input[i].toInt()
            val next = if (i + 1 < inCount) input[i + 1].toInt() else current
            output[outIdx++] = current.toShort()
            output[outIdx++] = ((current * 2 + next) / 3).toShort()
            output[outIdx++] = ((current + next * 2) / 3).toShort()
        }
        return outIdx
    }

    private fun upsample8kTo48k(input: ShortArray, inCount: Int, output: ShortArray): Int {
        if (inCount <= 0) return 0
        var outIdx = 0
        val maxOut = output.size
        for (i in 0 until inCount) {
            if (outIdx + 6 > maxOut) break
            val current = input[i].toInt()
            val next = if (i + 1 < inCount) input[i + 1].toInt() else current
            for (k in 0 until 6) {
                output[outIdx++] = ((current * (6 - k) + next * k) / 6).toShort()
            }
        }
        return outIdx
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun restartAudioRecord() {
        if (!isRunning) return
        try {
            val oldRecord = audioRecord
            audioRecord = null
            try {
                if (oldRecord?.state == AudioRecord.STATE_INITIALIZED && oldRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    oldRecord.stop()
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Error stopping previous AudioRecord: ${e.message}")
            }
            oldRecord?.release()

            val targetDev = preferredInputDevice ?: deviceManager.selectedInputDevice
            val isBtMic = targetDev?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                          targetDev?.type == 26 ||
                          targetDev?.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                          targetDev?.id == 9999 || targetDev?.id == 8888
            val sampleRates = if (isBtMic) intArrayOf(16000, 8000, 48000, 44100) else intArrayOf(48000, 44100)
            val sources = if (isBtMic) {
                intArrayOf(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    MediaRecorder.AudioSource.MIC,
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    MediaRecorder.AudioSource.DEFAULT
                )
            } else {
                intArrayOf(
                    MediaRecorder.AudioSource.MIC,
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    MediaRecorder.AudioSource.DEFAULT
                )
            }

            var record: AudioRecord? = null
            for (rate in sampleRates) {
                val minBuf = AudioRecord.getMinBufferSize(rate, CHANNEL_IN, AUDIO_FORMAT)
                if (minBuf <= 0) continue
                for (source in sources) {
                    try {
                        val candidate = AudioRecord(
                            source,
                            rate,
                            CHANNEL_IN,
                            AUDIO_FORMAT,
                            minBuf * 2
                        )
                        if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                            record = candidate
                            actualRecordSampleRate = rate
                            Log.i(TAG, "AudioRecord restarted successfully with source: $source at ${rate}Hz")
                            break
                        } else {
                            candidate.release()
                        }
                    } catch (e: Throwable) {
                        Log.w(TAG, "AudioRecord restart candidate failed for source $source at $rate", e)
                    }
                }
                if (record != null) break
            }

            if (record != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && targetDev != null) {
                    record.preferredDevice = targetDev
                }
                record.startRecording()
                audioRecord = record
                Log.i(TAG, "AudioRecord successfully restarted with device: ${targetDev?.type} at ${actualRecordSampleRate}Hz")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error in restartAudioRecord", e)
        }
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun start() {
        if (isRunning) return

        try {
            // Apply input & output routing
            deviceManager.routeToInput(preferredInputDevice)
            deviceManager.applyRouting(forceSpeaker)

            val minTrackBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, AUDIO_FORMAT)
            if (minTrackBufferSize <= 0) {
                onError?.invoke("ไม่สามารถคำนวณขนาด Audio Buffer ที่เหมาะสมได้")
                return
            }

            // 1. Initialize AudioRecord (supports 16kHz native SCO or 48kHz)
            var record: AudioRecord? = null
            val targetDev = preferredInputDevice ?: deviceManager.selectedInputDevice
            val isBtMic = targetDev?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                          targetDev?.type == 26 ||
                          targetDev?.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                          targetDev?.id == 9999 || targetDev?.id == 8888
            val sampleRates = if (isBtMic) intArrayOf(16000, 8000, 48000, 44100) else intArrayOf(48000, 44100)
            val sources = if (isBtMic) {
                intArrayOf(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    MediaRecorder.AudioSource.MIC,
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    MediaRecorder.AudioSource.DEFAULT
                )
            } else {
                intArrayOf(
                    MediaRecorder.AudioSource.MIC,
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    MediaRecorder.AudioSource.DEFAULT
                )
            }

            for (rate in sampleRates) {
                val minBuf = AudioRecord.getMinBufferSize(rate, CHANNEL_IN, AUDIO_FORMAT)
                if (minBuf <= 0) continue
                for (source in sources) {
                    try {
                        val candidate = AudioRecord(
                            source,
                            rate,
                            CHANNEL_IN,
                            AUDIO_FORMAT,
                            minBuf * 2
                        )
                        if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                            record = candidate
                            actualRecordSampleRate = rate
                            Log.i(TAG, "AudioRecord initialized successfully with audio source: $source at ${rate}Hz")
                            break
                        } else {
                            candidate.release()
                        }
                    } catch (e: Throwable) {
                        Log.w(TAG, "Failed initializing AudioRecord with source $source at $rate: ${e.message}")
                    }
                }
                if (record != null) break
            }

            if (record == null) {
                onError?.invoke("ไม่สามารถเปิดใช้งานไมโครโฟนได้ (AudioRecord init failed)")
                return
            }
            audioRecord = record

            // Set preferred input device if specified (e.g. wired headset / external mic)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && targetDev != null) {
                audioRecord?.preferredDevice = targetDev
            }

            // NOTE: Hardware AcousticEchoCanceler is intentionally NOT enabled here.
            // On Android, hardware AEC subtracts all speaker/loopback sound from mic input,
            // which causes microphone loopback/megaphone apps to be completely silenced!
            // Anti-howling is handled cleanly via our software AntiHowlProcessor.

            // 2. Initialize AudioTrack with appropriate stream/usage
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(if (isBtMic) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
                .setContentType(if (isBtMic) AudioAttributes.CONTENT_TYPE_SPEECH else AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

            val audioFormatObj = AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNEL_OUT)
                .setEncoding(AUDIO_FORMAT)
                .build()

            audioTrack = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                AudioTrack.Builder()
                    .setAudioAttributes(audioAttributes)
                    .setAudioFormat(audioFormatObj)
                    .setBufferSizeInBytes(minTrackBufferSize * 2)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                AudioTrack(
                    if (isBtMic) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC,
                    SAMPLE_RATE,
                    CHANNEL_OUT,
                    AUDIO_FORMAT,
                    minTrackBufferSize * 2,
                    AudioTrack.MODE_STREAM
                )
            }

            if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                cleanup()
                onError?.invoke("ไม่สามารถเปิดใช้งานระบบเสียงลำโพงได้ (AudioTrack init failed)")
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val outDev = preferredOutputDevice ?: if (forceSpeaker) deviceManager.getBuiltinSpeakerDevice() else null
                if (outDev != null) {
                    audioTrack?.preferredDevice = outDev
                    Log.i(TAG, "AudioTrack routed to device: ${outDev.type}")
                }
            }

            // Ensure volumes are active on the device
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (currentVol == 0) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (maxVol * 0.85f).toInt(), AudioManager.FLAG_SHOW_UI)
            }
            val currentCallVol = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
            val maxCallVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            if (currentCallVol == 0) {
                audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, (maxCallVol * 0.85f).toInt(), AudioManager.FLAG_SHOW_UI)
            }

            // 4. Start recording and playback streams
            audioRecord?.startRecording()
            audioTrack?.play()

            isRunning = true

            // 5. Start real-time audio loop thread with high audio priority
            val outChunkSize = (SAMPLE_RATE * 0.010).toInt() // 480 samples (10ms at 48kHz)
            val processBuffer = ShortArray(outChunkSize)
            val inRawBuffer = ShortArray(SAMPLE_RATE / 10) // generous buffer for reading

            loopThread = Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

                while (isRunning) {
                    val activeRecord = audioRecord
                    if (activeRecord == null || activeRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        try {
                            Thread.sleep(20)
                        } catch (e: InterruptedException) {
                            break
                        }
                        continue
                    }

                    val inRate = actualRecordSampleRate
                    val inChunk = when (inRate) {
                        16000 -> 160
                        8000 -> 80
                        44100 -> 441
                        else -> outChunkSize // 480
                    }

                    val readCount = activeRecord.read(inRawBuffer, 0, inChunk)
                    if (readCount <= 0) {
                        try { Thread.sleep(10) } catch (e: InterruptedException) { break }
                        continue
                    }
                    if (readCount > 0) {
                        val finalCount: Int
                        if (inRate == 16000) {
                            finalCount = upsample16kTo48k(inRawBuffer, readCount, processBuffer)
                        } else if (inRate == 8000) {
                            finalCount = upsample8kTo48k(inRawBuffer, readCount, processBuffer)
                        } else {
                            val copyLen = minOf(readCount, outChunkSize)
                            System.arraycopy(inRawBuffer, 0, processBuffer, 0, copyLen)
                            finalCount = copyLen
                        }

                        if (finalCount > 0) {
                            // Apply DSP: Gain, Limiter, Noise Gate, RMS calculation
                            dspProcessor.process(processBuffer, finalCount)

                            // Output to speaker
                            audioTrack?.write(processBuffer, 0, finalCount)

                            // Post metrics to UI callback
                            onAudioLevelUpdated?.invoke(
                                dspProcessor.currentPeakNormalized,
                                dspProcessor.currentRmsNormalized,
                                dspProcessor.currentDb
                            )
                        }
                    }
                }
            }, "AudioLoopThread").apply {
                start()
            }

            Log.i(TAG, "AudioLoopEngine started successfully (inRate=${actualRecordSampleRate}Hz, outRate=${SAMPLE_RATE}Hz)")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting AudioLoopEngine", e)
            cleanup()
            onError?.invoke("เกิดข้อผิดพลาดในการเริ่มระบบขยายเสียง: ${e.message}")
        }
    }

    @Synchronized
    fun stop() {
        if (!isRunning) return
        isRunning = false

        try {
            loopThread?.interrupt()
            loopThread?.join(500)
        } catch (e: InterruptedException) {
            Log.e(TAG, "Thread join interrupted", e)
        }
        loopThread = null

        cleanup()
        Log.i(TAG, "AudioLoopEngine stopped")
    }

    @Synchronized
    fun restart() {
        if (!isRunning) return
        stop()
        start()
    }

    private fun cleanup() {
        try {
            echoCanceler?.release()
            echoCanceler = null
            noiseSuppressor?.release()
            noiseSuppressor = null
            autoGainControl?.release()
            autoGainControl = null

            audioRecord?.apply {
                if (state == AudioRecord.STATE_INITIALIZED) {
                    stop()
                }
                release()
            }
            audioRecord = null

            audioTrack?.apply {
                if (state == AudioTrack.STATE_INITIALIZED) {
                    stop()
                }
                release()
            }
            audioTrack = null

            deviceManager.resetRouting()
            dspProcessor.reset()
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }
}
