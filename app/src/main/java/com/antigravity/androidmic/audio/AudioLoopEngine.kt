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
            // SCO is now connected — find the real BT SCO device and restart AudioRecord
            Log.i(TAG, "Bluetooth SCO connected! Rebinding AudioRecord to real BT device...")
            if (isRunning) {
                // Look up the actual connected SCO device NOW (it should be visible post-SCO)
                val btScoDevice = try {
                    val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        am.availableCommunicationDevices.find {
                            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == 26
                        } ?: am.getDevices(AudioManager.GET_DEVICES_INPUTS).find {
                            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == 26
                        }
                    } else {
                        am.getDevices(AudioManager.GET_DEVICES_INPUTS).find {
                            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == 26
                        }
                    }
                } catch (e: Throwable) { null }

                if (btScoDevice != null) {
                    Log.i(TAG, "Found real BT SCO device: ${btScoDevice.productName} (id=${btScoDevice.id})")
                    // Update preferredInputDevice to real hardware device
                    preferredInputDevice = btScoDevice
                } else {
                    Log.w(TAG, "SCO connected but no device found in inputs, restarting AudioRecord anyway")
                    restartAudioRecord()
                }
            }
        }
    }

    var preferredInputItem: AudioDeviceItem? = null
        set(value) {
            val wasBt = field?.isBluetooth == true
            val isBt = value?.isBluetooth == true
            field = value
            // Only update preferredInputDevice from item if item has a real device
            if (value?.deviceInfo != null) {
                preferredInputDevice = value.deviceInfo
            } else if (isBt) {
                // Bluetooth chosen but no hardware device yet (SCO not connected)
                // Don't overwrite preferredInputDevice — let SCO callback set it
                // But do trigger routing so SCO starts connecting
                deviceManager.routeToInput(value)
            } else {
                preferredInputDevice = null
            }
            if (field?.deviceInfo == null && isBt) {
                // Already routed above, don't double-route
            } else {
                deviceManager.routeToInput(value)
            }
            if (isRunning) {
                if (wasBt != isBt) {
                    restart()
                } else {
                    restartAudioRecord()
                }
            }
        }

    var preferredInputDevice: AudioDeviceInfo? = null
        set(value) {
            val wasBt = field?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        field?.type == 26 ||
                        field?.id == 9999
            val isBt = value?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                       value?.type == 26
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

    var preferredOutputItem: AudioDeviceItem? = null
        set(value) {
            field = value
            // For output, prefer the real deviceInfo; for speaker, look it up
            val realDev = value?.deviceInfo
                ?: if (value?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) deviceManager.getBuiltinSpeakerDevice() else null
            preferredOutputDevice = realDev
            deviceManager.routeToOutput(value)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && isRunning && realDev != null) {
                audioTrack?.preferredDevice = realDev
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

    fun isBluetoothMicActive(): Boolean {
        return preferredInputItem?.isBluetooth == true ||
               deviceManager.selectedInputItem?.isBluetooth == true ||
               preferredInputDevice?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
               preferredInputDevice?.type == 26 ||
               deviceManager.selectedInputDevice?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
               deviceManager.selectedInputDevice?.type == 26
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
                if (oldRecord?.state == AudioRecord.STATE_INITIALIZED &&
                    oldRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    oldRecord.stop()
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Error stopping previous AudioRecord: ${e.message}")
            }
            oldRecord?.release()

            // Determine the real hardware device to bind
            val isBtMic = isBluetoothMicActive()
            val targetDev: AudioDeviceInfo? = if (isBtMic) {
                // If we have a real BT SCO device already, use it
                preferredInputDevice
                    ?: deviceManager.selectedInputDevice
                    ?: try {
                        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            am.availableCommunicationDevices.find {
                                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == 26
                            } ?: am.getDevices(AudioManager.GET_DEVICES_INPUTS).find {
                                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == 26
                            }
                        } else {
                            am.getDevices(AudioManager.GET_DEVICES_INPUTS).find {
                                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == 26
                            }
                        }
                    } catch (e: Throwable) { null }
            } else {
                preferredInputDevice ?: deviceManager.selectedInputDevice
            }

            val sampleRates = if (isBtMic) intArrayOf(16000, 8000, 48000, 44100) else intArrayOf(48000, 44100)
            // For BT SCO, VOICE_COMMUNICATION is required to bind to the SCO stream
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
                        val candidate = AudioRecord(source, rate, CHANNEL_IN, AUDIO_FORMAT, minBuf * 4)
                        if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                            record = candidate
                            actualRecordSampleRate = rate
                            Log.i(TAG, "AudioRecord restarted: source=$source rate=${rate}Hz isBtMic=$isBtMic")
                            break
                        } else {
                            candidate.release()
                        }
                    } catch (e: Throwable) {
                        Log.w(TAG, "AudioRecord restart failed for source=$source rate=$rate: ${e.message}")
                    }
                }
                if (record != null) break
            }

            if (record != null) {
                // Bind to real BT hardware device if found
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && targetDev != null) {
                    try {
                        record.preferredDevice = targetDev
                        Log.i(TAG, "AudioRecord.preferredDevice set to ${targetDev.productName} type=${targetDev.type}")
                    } catch (e: Throwable) {
                        Log.w(TAG, "Could not set preferredDevice: ${e.message}")
                    }
                }
                record.startRecording()
                audioRecord = record
                Log.i(TAG, "AudioRecord restarted OK at ${actualRecordSampleRate}Hz, isBtMic=$isBtMic")
            } else {
                Log.e(TAG, "Could not restart AudioRecord — no valid configuration found")
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
            // 1. Apply input routing first — this triggers startBluetoothSco if BT mic chosen
            if (preferredInputItem != null) {
                deviceManager.routeToInput(preferredInputItem)
            } else {
                deviceManager.routeToInput(preferredInputDevice)
            }

            // 2. Apply output routing
            if (preferredOutputItem != null) {
                deviceManager.routeToOutput(preferredOutputItem)
            } else {
                deviceManager.applyRouting(forceSpeaker)
            }

            val minTrackBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, AUDIO_FORMAT)
            if (minTrackBufferSize <= 0) {
                onError?.invoke("ไม่สามารถคำนวณขนาด Audio Buffer ที่เหมาะสมได้")
                return
            }

            val isBtMic = isBluetoothMicActive()

            // 3. Find the real hardware device to bind AudioRecord to
            val targetDev: AudioDeviceInfo? = if (isBtMic) {
                preferredInputDevice
                    ?: deviceManager.selectedInputDevice
                    ?: try {
                        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            am.availableCommunicationDevices.find {
                                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == 26
                            } ?: am.getDevices(AudioManager.GET_DEVICES_INPUTS).find {
                                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == 26
                            }
                        } else {
                            am.getDevices(AudioManager.GET_DEVICES_INPUTS).find {
                                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == 26
                            }
                        }
                    } catch (e: Throwable) { null }
            } else {
                preferredInputDevice ?: deviceManager.selectedInputDevice
            }

            Log.i(TAG, "start(): isBtMic=$isBtMic, targetDev=${targetDev?.productName} (${targetDev?.type})")

            // 4. Initialize AudioRecord
            // For BT: try VOICE_COMMUNICATION (required for SCO), fallback to MIC
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
                        val candidate = AudioRecord(source, rate, CHANNEL_IN, AUDIO_FORMAT, minBuf * 4)
                        if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                            record = candidate
                            actualRecordSampleRate = rate
                            Log.i(TAG, "AudioRecord created: source=$source rate=${rate}Hz")
                            break
                        } else {
                            candidate.release()
                        }
                    } catch (e: Throwable) {
                        Log.w(TAG, "AudioRecord candidate failed source=$source rate=$rate: ${e.message}")
                    }
                }
                if (record != null) break
            }

            if (record == null) {
                onError?.invoke("ไม่สามารถเปิดใช้งานไมโครโฟนได้ (AudioRecord init failed)")
                return
            }
            audioRecord = record

            // Bind to real BT hardware if available
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && targetDev != null) {
                try {
                    audioRecord?.preferredDevice = targetDev
                    Log.i(TAG, "AudioRecord.preferredDevice = ${targetDev.productName} type=${targetDev.type}")
                } catch (e: Throwable) {
                    Log.w(TAG, "preferredDevice set failed: ${e.message}")
                }
            }

            // NOTE: Hardware AcousticEchoCanceler is intentionally NOT enabled here.
            // On Android, hardware AEC subtracts all speaker/loopback sound from mic input,
            // which causes microphone loopback/megaphone apps to be completely silenced!
            // Anti-howling is handled cleanly via our software AntiHowlProcessor.

            // 5. Initialize AudioTrack
            // KEY FIX: Always use USAGE_MEDIA + STREAM_MUSIC so audio routes to speaker properly.
            // For BT mic, the routing is handled by MODE_IN_COMMUNICATION + isSpeakerphoneOn=true,
            // NOT by AudioTrack usage type. Using VOICE_COMMUNICATION on AudioTrack sends to earpiece!
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
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
                    .setBufferSizeInBytes(minTrackBufferSize * 4)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    SAMPLE_RATE,
                    CHANNEL_OUT,
                    AUDIO_FORMAT,
                    minTrackBufferSize * 4,
                    AudioTrack.MODE_STREAM
                )
            }

            if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                cleanup()
                onError?.invoke("ไม่สามารถเปิดใช้งานระบบเสียงลำโพงได้ (AudioTrack init failed)")
                return
            }

            // Route AudioTrack output device
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val outItem = preferredOutputItem
                val outDev = preferredOutputDevice
                    ?: if (outItem?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER || outItem == null || forceSpeaker) {
                        deviceManager.getBuiltinSpeakerDevice()
                    } else {
                        outItem.deviceInfo
                    }
                if (outDev != null) {
                    audioTrack?.preferredDevice = outDev
                    Log.i(TAG, "AudioTrack output device: ${outDev.productName} type=${outDev.type}")
                }
            }

            // Ensure media and call volumes are NOT muted
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            try {
                val maxMusic = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (maxMusic * 0.85f).toInt(), AudioManager.FLAG_SHOW_UI)
                }
                val maxCall = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
                if (audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL) == 0) {
                    audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxCall, 0)
                }
            } catch (e: Throwable) {}

            // 6. Start recording and playback
            audioRecord?.startRecording()
            audioTrack?.play()

            isRunning = true

            // 7. Start real-time audio loop thread
            val outChunkSize = (SAMPLE_RATE * 0.010).toInt() // 480 samples (10ms at 48kHz)
            val processBuffer = ShortArray(outChunkSize)
            val inRawBuffer = ShortArray(SAMPLE_RATE / 5) // generous input buffer

            loopThread = Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

                while (isRunning) {
                    val activeRecord = audioRecord
                    if (activeRecord == null || activeRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        try { Thread.sleep(20) } catch (e: InterruptedException) { break }
                        continue
                    }

                    val inRate = actualRecordSampleRate
                    val inChunk = when (inRate) {
                        16000 -> 160  // 10ms at 16kHz
                        8000  -> 80   // 10ms at 8kHz
                        44100 -> 441  // 10ms at 44.1kHz
                        else  -> outChunkSize // 480 samples at 48kHz
                    }

                    val readCount = activeRecord.read(inRawBuffer, 0, inChunk)
                    if (readCount <= 0) {
                        try { Thread.sleep(10) } catch (e: InterruptedException) { break }
                        continue
                    }

                    val finalCount: Int = when (inRate) {
                        16000 -> upsample16kTo48k(inRawBuffer, readCount, processBuffer)
                        8000  -> upsample8kTo48k(inRawBuffer, readCount, processBuffer)
                        else  -> {
                            val copyLen = minOf(readCount, outChunkSize)
                            System.arraycopy(inRawBuffer, 0, processBuffer, 0, copyLen)
                            copyLen
                        }
                    }

                    if (finalCount > 0) {
                        // Apply DSP: Gain, Limiter, Noise Gate, Anti-Howl, Echo
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
            }, "AudioLoopThread").apply {
                start()
            }

            Log.i(TAG, "AudioLoopEngine started (inRate=${actualRecordSampleRate}Hz, outRate=${SAMPLE_RATE}Hz, isBtMic=$isBtMic)")
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
                try {
                    if (state == AudioRecord.STATE_INITIALIZED) stop()
                } catch (e: Throwable) {}
                release()
            }
            audioRecord = null

            audioTrack?.apply {
                try {
                    if (state == AudioTrack.STATE_INITIALIZED) stop()
                } catch (e: Throwable) {}
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
