package com.antigravity.androidmic.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log

data class AudioDeviceItem(
    val id: Int,
    val name: String,
    val type: Int,
    val isSource: Boolean,
    val deviceInfo: AudioDeviceInfo? = null
)

class AudioDeviceManager(private val context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    var selectedInputDevice: AudioDeviceInfo? = null
        private set
    var selectedOutputDevice: AudioDeviceInfo? = null
        private set
    var isSpeakerForced: Boolean = true
        private set

    var onDevicesChanged: (() -> Unit)? = null
    var onScoAudioConnected: (() -> Unit)? = null

    private var isScoReceiverRegistered = false
    private var isScoStarted = false

    private val scoReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED) {
                val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_ERROR)
                Log.d(TAG, "Bluetooth SCO Audio State changed: $state")
                when (state) {
                    AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                        Log.i(TAG, "Bluetooth SCO Audio is now CONNECTED!")
                        @Suppress("DEPRECATION")
                        audioManager.isBluetoothScoOn = true
                        onScoAudioConnected?.invoke()
                    }
                    AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                        Log.i(TAG, "Bluetooth SCO Audio DISCONNECTED")
                        @Suppress("DEPRECATION")
                        audioManager.isBluetoothScoOn = false
                        isScoStarted = false
                    }
                    AudioManager.SCO_AUDIO_STATE_ERROR -> {
                        Log.w(TAG, "Bluetooth SCO Audio ERROR")
                        isScoStarted = false
                    }
                }
            }
        }
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            onDevicesChanged?.invoke()
            applyCurrentRouting()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            onDevicesChanged?.invoke()
            applyCurrentRouting()
        }
    }

    init {
        try {
            audioManager.registerAudioDeviceCallback(deviceCallback, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register audio device callback", e)
        }

        try {
            val filter = IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
            context.registerReceiver(scoReceiver, filter)
            isScoReceiverRegistered = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register SCO broadcast receiver", e)
        }
    }

    fun release() {
        try {
            audioManager.unregisterAudioDeviceCallback(deviceCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering audio device callback", e)
        }

        if (isScoReceiverRegistered) {
            try {
                context.unregisterReceiver(scoReceiver)
                isScoReceiverRegistered = false
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering scoReceiver", e)
            }
        }

        resetRouting()
    }

    /**
     * Apply routing according to selected input device (Microphone)
     */
    fun routeToInput(device: AudioDeviceInfo?) {
        selectedInputDevice = device
        applyCurrentRouting()
    }

    /**
     * Apply routing according to selected output device (Speaker / Bluetooth)
     */
    fun routeToOutput(device: AudioDeviceInfo?) {
        selectedOutputDevice = device
        isSpeakerForced = (device == null || device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        applyCurrentRouting()
    }

    /**
     * Legacy helper to force phone built-in speaker
     */
    fun applyRouting(forceSpeaker: Boolean) {
        if (forceSpeaker) {
            routeToOutput(getBuiltinSpeakerDevice())
        } else {
            routeToOutput(null)
        }
    }

    private fun applyCurrentRouting() {
        try {
            val inTarget = selectedInputDevice
            val outTarget = selectedOutputDevice

            val isBluetoothMic = inTarget != null && (
                inTarget.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                inTarget.type == 26 /* TYPE_BLE_HEADSET */
            )

            // Keep system in MODE_NORMAL so AudioTrack USAGE_MEDIA is NEVER muted/ducked by telephony policy
            audioManager.mode = AudioManager.MODE_NORMAL

            if (isBluetoothMic) {
                // Bluetooth SCO microphone requires startBluetoothSco
                Log.i(TAG, "Activating Bluetooth SCO microphone link...")
                @Suppress("DEPRECATION")
                if (!audioManager.isBluetoothScoOn && !isScoStarted) {
                    isScoStarted = true
                    try {
                        audioManager.startBluetoothSco()
                    } catch (e: Throwable) {
                        Log.e(TAG, "startBluetoothSco error", e)
                    }
                }

                // If output is built-in speaker, ensure loudspeaker is forced on
                if (outTarget == null || outTarget.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                    @Suppress("DEPRECATION")
                    audioManager.isSpeakerphoneOn = true
                } else {
                    @Suppress("DEPRECATION")
                    audioManager.isSpeakerphoneOn = false
                }
            } else {
                // Non-Bluetooth microphone (Phone mic, Wired headset, USB mic)
                @Suppress("DEPRECATION")
                if (audioManager.isBluetoothScoOn || isScoStarted) {
                    try {
                        audioManager.stopBluetoothSco()
                    } catch (e: Throwable) {}
                    audioManager.isBluetoothScoOn = false
                    isScoStarted = false
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    audioManager.clearCommunicationDevice()
                }

                if (outTarget != null && outTarget.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                    @Suppress("DEPRECATION")
                    audioManager.isSpeakerphoneOn = true
                } else {
                    @Suppress("DEPRECATION")
                    audioManager.isSpeakerphoneOn = false
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to apply current audio routing", e)
        }
    }

    /**
     * Restore normal audio routing
     */
    fun resetRouting() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            }
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = false
            @Suppress("DEPRECATION")
            if (audioManager.isBluetoothScoOn || isScoStarted) {
                try {
                    audioManager.stopBluetoothSco()
                } catch (e: Throwable) {}
                audioManager.isBluetoothScoOn = false
                isScoStarted = false
            }
            audioManager.mode = AudioManager.MODE_NORMAL
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to reset audio routing", e)
        }
    }

    /**
     * Get list of currently connected input devices (microphones)
     */
    fun getAvailableInputDevices(): List<AudioDeviceItem> {
        val list = mutableListOf<AudioDeviceItem>()
        try {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)

            for (dev in devices) {
                val productName = try {
                    dev.productName?.toString()
                } catch (e: Throwable) {
                    null
                }

                val name = when (dev.type) {
                    AudioDeviceInfo.TYPE_WIRED_HEADSET -> "หูฟังมีสาย (Wired Headset Mic)"
                    AudioDeviceInfo.TYPE_USB_HEADSET -> "หูฟัง USB-C (USB Headset Mic)"
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "ไมค์บลูทูธ (Bluetooth Mic: ${productName ?: ""})".trim()
                    26 /* TYPE_BLE_HEADSET */ -> "ไมค์บลูทูธ BLE (Bluetooth BLE Mic: ${productName ?: ""})".trim()
                    AudioDeviceInfo.TYPE_BUILTIN_MIC -> "ไมค์ตัวเครื่องมือถือ (Phone Mic)"
                    else -> if (!productName.isNullOrBlank()) "ไมโครโฟนภายนอก ($productName)" else "ไมโครโฟนภายนอก (External Mic)"
                }
                list.add(AudioDeviceItem(dev.id, name, dev.type, isSource = true, deviceInfo = dev))
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error querying audio input devices", e)
        }

        if (list.isEmpty()) {
            list.add(AudioDeviceItem(0, "ไมค์ตัวเครื่อง (Default Mic)", AudioDeviceInfo.TYPE_BUILTIN_MIC, true))
        }

        return list
    }

    /**
     * Get list of currently available audio OUTPUT devices (Loudspeaker, Bluetooth, Headphones)
     */
    fun getAvailableOutputDevices(): List<AudioDeviceItem> {
        val list = mutableListOf<AudioDeviceItem>()
        try {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

            for (dev in devices) {
                val productName = try {
                    dev.productName?.toString()
                } catch (e: Throwable) {
                    null
                }

                val name = when (dev.type) {
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "🔊 ลำโพงตัวเครื่องมือถือ (Phone Speaker)"
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "📻 ลำโพงบลูทูธ (${productName ?: "Bluetooth Speaker"})"
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "🎧 บลูทูธแฮนด์ฟรี (${productName ?: "Bluetooth Device"})"
                    AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "🎧 หูฟังมีสาย (Wired Headphones)"
                    AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> "🔌 อุปกรณ์เสียง USB (${productName ?: "USB Audio"})"
                    else -> if (dev.type == 26 /* TYPE_BLE_HEADSET */ || dev.type == 27 /* TYPE_BLE_SPEAKER */) {
                        "📻 ลำโพงบลูทูธ BLE (${productName ?: "BLE Speaker"})"
                    } else if (dev.type != AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) {
                        "อุปกรณ์เสียงภายนอก (${productName ?: "External"})"
                    } else null
                }

                if (name != null) {
                    list.add(AudioDeviceItem(dev.id, name, dev.type, isSource = false, deviceInfo = dev))
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error querying audio output devices", e)
        }

        // Always ensure Phone Speaker is in the list
        if (list.none { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }) {
            list.add(0, AudioDeviceItem(0, "🔊 ลำโพงตัวเครื่องมือถือ (Phone Speaker)", AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, false, getBuiltinSpeakerDevice()))
        }

        return list
    }

    /**
     * Check if a headset or external mic is currently connected
     */
    fun isHeadsetConnected(): Boolean {
        return try {
            val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            inputs.any {
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * Returns the built-in speaker device info if found
     */
    fun getBuiltinSpeakerDevice(): AudioDeviceInfo? {
        return try {
            val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            outputs.find { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        } catch (e: Throwable) {
            null
        }
    }

    companion object {
        private const val TAG = "AudioDeviceManager"
    }
}
