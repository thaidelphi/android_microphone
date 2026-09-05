package com.antigravity.androidmic.audio

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
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
    val deviceInfo: AudioDeviceInfo? = null,
    val isBluetooth: Boolean = false
)

class AudioDeviceManager(private val context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    var selectedInputItem: AudioDeviceItem? = null
        private set
    var selectedOutputItem: AudioDeviceItem? = null
        private set

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
            Log.d(TAG, "Audio devices added")
            onDevicesChanged?.invoke()
            applyCurrentRouting()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            Log.d(TAG, "Audio devices removed")
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
     * Apply routing according to selected input item
     */
    fun routeToInput(item: AudioDeviceItem?) {
        selectedInputItem = item
        selectedInputDevice = item?.deviceInfo
        applyCurrentRouting()
    }

    fun routeToInput(device: AudioDeviceInfo?) {
        selectedInputDevice = device
        selectedInputItem = device?.let {
            val isBt = it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == 26 || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
            AudioDeviceItem(it.id, it.productName?.toString() ?: "Mic", it.type, true, it, isBt)
        }
        applyCurrentRouting()
    }

    /**
     * Apply routing according to selected output item
     */
    fun routeToOutput(item: AudioDeviceItem?) {
        selectedOutputItem = item
        selectedOutputDevice = item?.deviceInfo
        isSpeakerForced = (item == null || item.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        applyCurrentRouting()
    }

    fun routeToOutput(device: AudioDeviceInfo?) {
        selectedOutputDevice = device
        selectedOutputItem = device?.let {
            val isBt = it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == 26 || it.type == 27
            AudioDeviceItem(it.id, it.productName?.toString() ?: "Speaker", it.type, false, it, isBt)
        }
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
            routeToOutput(null as AudioDeviceInfo?)
        }
    }

    fun applyCurrentRouting() {
        try {
            val inItem = selectedInputItem
            val outItem = selectedOutputItem
            val inTarget = selectedInputDevice
            val outTarget = selectedOutputDevice

            val isBluetoothMic = inItem?.isBluetooth == true ||
                                 inItem?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                                 inItem?.type == 26 /* BLE_HEADSET */ ||
                                 inItem?.id == 9999 ||
                                 inItem?.name?.contains("บลูทูธ") == true ||
                                 inItem?.name?.contains("Bluetooth") == true ||
                                 (inTarget != null && (
                                     inTarget.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                                     inTarget.type == 26 ||
                                     inTarget.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                                     inTarget.id == 9999
                                 ))

            Log.i(TAG, "applyCurrentRouting: isBluetoothMic=$isBluetoothMic (inItem=${inItem?.name}, outItem=${outItem?.name})")

            if (isBluetoothMic) {
                // Must be MODE_IN_COMMUNICATION for Bluetooth SCO / HFP on Android!
                Log.i(TAG, "Activating Bluetooth Communication mode (MODE_IN_COMMUNICATION)...")
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

                // Android 12+ Communication Device routing
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val commDevices = audioManager.availableCommunicationDevices
                    val btComm = commDevices.find {
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        it.type == 26 /* TYPE_BLE_HEADSET */
                    } ?: (if (inTarget?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || inTarget?.type == 26) inTarget else null)

                    if (btComm != null) {
                        val ok = audioManager.setCommunicationDevice(btComm)
                        Log.i(TAG, "setCommunicationDevice(${btComm.productName}) = $ok")
                    } else {
                        Log.w(TAG, "No Bluetooth communication device in availableCommunicationDevices yet")
                    }
                }

                // Call startBluetoothSco for backward compatibility and all Android versions
                @Suppress("DEPRECATION")
                if (!audioManager.isBluetoothScoOn && !isScoStarted) {
                    isScoStarted = true
                    try {
                        audioManager.startBluetoothSco()
                        audioManager.isBluetoothScoOn = true
                        Log.i(TAG, "startBluetoothSco requested")
                    } catch (e: Throwable) {
                        Log.e(TAG, "startBluetoothSco error", e)
                    }
                }

                // If output is built-in speaker, force speakerphone on
                val isOutSpeaker = outItem == null || outItem.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER || outTarget?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                if (isOutSpeaker) {
                    @Suppress("DEPRECATION")
                    audioManager.isSpeakerphoneOn = true
                } else {
                    @Suppress("DEPRECATION")
                    audioManager.isSpeakerphoneOn = false
                }

                // Ensure stream volumes are active, but do NOT override user's lower volume setting
                try {
                    val curCall = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
                    if (curCall == 0) {
                        val maxCall = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
                        audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, (maxCall * 0.7f).toInt(), 0)
                    }
                    val curMusic = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    if (curMusic == 0) {
                        val maxMusic = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (maxMusic * 0.65f).toInt(), 0)
                    }
                } catch (e: Throwable) {}
            } else {
                // Non-Bluetooth microphone (Phone mic, Wired headset, USB mic)
                audioManager.mode = AudioManager.MODE_NORMAL

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    audioManager.clearCommunicationDevice()
                }

                @Suppress("DEPRECATION")
                if (audioManager.isBluetoothScoOn || isScoStarted) {
                    try { audioManager.stopBluetoothSco() } catch (e: Throwable) {}
                    audioManager.isBluetoothScoOn = false
                    isScoStarted = false
                }

                val isOutSpeaker = outItem == null || outItem.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER || outTarget?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                if (isOutSpeaker) {
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
            // 1. Standard Audio inputs
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)

            for (dev in devices) {
                val productName = try { dev.productName?.toString() } catch (e: Throwable) { null }
                val isBt = dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || dev.type == 26 /* TYPE_BLE_HEADSET */

                val name = when (dev.type) {
                    AudioDeviceInfo.TYPE_WIRED_HEADSET -> "หูฟังมีสาย (Wired Headset Mic)"
                    AudioDeviceInfo.TYPE_USB_HEADSET -> "หูฟัง/ไมค์ USB-C (USB Headset Mic)"
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "🎙️ ไมค์บลูทูธ (Bluetooth Mic: ${productName ?: ""})".trim()
                    26 /* TYPE_BLE_HEADSET */ -> "🎙️ ไมค์บลูทูธ BLE (Bluetooth BLE Mic: ${productName ?: ""})".trim()
                    AudioDeviceInfo.TYPE_BUILTIN_MIC -> "📱 ไมค์ตัวเครื่องมือถือ (Phone Mic)"
                    else -> if (!productName.isNullOrBlank()) "ไมโครโฟนภายนอก ($productName)" else "ไมโครโฟนภายนอก (External Mic)"
                }
                list.add(AudioDeviceItem(dev.id, name, dev.type, isSource = true, deviceInfo = dev, isBluetooth = isBt))
            }

            // 2. On Android 12+ (API 31+), check availableCommunicationDevices
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val commDevices = audioManager.availableCommunicationDevices
                for (dev in commDevices) {
                    if (dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        dev.type == 26 /* TYPE_BLE_HEADSET */ ||
                        dev.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                        dev.type == AudioDeviceInfo.TYPE_USB_HEADSET) {
                        val isAlreadyIn = list.any { it.deviceInfo?.id == dev.id || it.type == dev.type }
                        if (!isAlreadyIn) {
                            val productName = try { dev.productName?.toString() } catch (e: Throwable) { null }
                            val isBt = dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || dev.type == 26
                            val name = "🎙️ ไมค์บลูทูธ (Bluetooth Mic: ${productName ?: ""})".trim()
                            list.add(0, AudioDeviceItem(dev.id, name, dev.type, isSource = true, deviceInfo = dev, isBluetooth = isBt))
                        }
                    }
                }
            }

            // 3. Check GET_DEVICES_OUTPUTS for connected Bluetooth devices (A2DP / SCO)
            // If BT device is visible in outputs but NOT in inputs, show BT mic option.
            // IMPORTANT: do NOT pass the A2DP output device as deviceInfo for an input item!
            // An A2DP output device cannot be used as AudioRecord.preferredDevice (it's output-only).
            // Set deviceInfo=null so the SCO callback will locate the real SCO input device.
            val outputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val btOutput = outputDevices.find {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == 26 || it.type == 27
            }
            val hasBtInList = list.any { it.isBluetooth }
            if (btOutput != null && !hasBtInList) {
                val btName = try { btOutput.productName?.toString() } catch (e: Throwable) { null } ?: ""
                list.add(0, AudioDeviceItem(
                    id = 9999,
                    name = "🎙️ ไมค์บลูทูธ (Bluetooth Mic: $btName)".trim(),
                    type = AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    isSource = true,
                    deviceInfo = null,  // null! Let SCO callback find real input device
                    isBluetooth = true
                ))
            }

            // 4. Query paired/bonded Bluetooth devices for friendly device names
            val bondedAudio = getBondedAudioDevices()
            if (list.none { it.isBluetooth } && bondedAudio.isNotEmpty()) {
                val firstBonded = bondedAudio.first()
                list.add(0, AudioDeviceItem(
                    id = 9999,
                    name = "🎙️ ไมค์บลูทูธ (Bluetooth Mic: $firstBonded)",
                    type = AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    isSource = true,
                    deviceInfo = null,
                    isBluetooth = true
                ))
            }

            // 5. ALWAYS guarantee Bluetooth Mic option is in the list!
            if (list.none { it.isBluetooth }) {
                list.add(0, AudioDeviceItem(
                    id = 9999,
                    name = "🎙️ ไมค์บลูทูธ (Bluetooth Mic)",
                    type = AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    isSource = true,
                    deviceInfo = null,
                    isBluetooth = true
                ))
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error querying audio input devices", e)
        }

        // Always ensure Bluetooth Mic and Phone Mic are in list
        if (list.none { it.isBluetooth }) {
            list.add(0, AudioDeviceItem(9999, "🎙️ ไมค์บลูทูธ (Bluetooth Mic)", AudioDeviceInfo.TYPE_BLUETOOTH_SCO, true, null, true))
        }
        if (list.none { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }) {
            list.add(AudioDeviceItem(0, "📱 ไมค์ตัวเครื่องมือถือ (Phone Mic)", AudioDeviceInfo.TYPE_BUILTIN_MIC, true, null, false))
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
                val productName = try { dev.productName?.toString() } catch (e: Throwable) { null }
                val isBt = dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                           dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                           dev.type == 26 || dev.type == 27

                val name = when (dev.type) {
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "🔊 ลำโพงตัวเครื่องมือถือ (Phone Speaker)"
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "📻 ลำโพงบลูทูธ (${productName ?: "Bluetooth Speaker"})"
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "🎧 บลูทูธแฮนด์ฟรี (${productName ?: "Bluetooth Device"})"
                    AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "🎧 หูฟังมีสาย (Wired Headphones)"
                    AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> "🔌 อุปกรณ์เสียง USB (${productName ?: "USB Audio"})"
                    else -> if (dev.type == 26 || dev.type == 27) {
                        "📻 ลำโพงบลูทูธ BLE (${productName ?: "BLE Speaker"})"
                    } else if (dev.type != AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) {
                        "อุปกรณ์เสียงภายนอก (${productName ?: "External"})"
                    } else null
                }

                if (name != null) {
                    list.add(AudioDeviceItem(dev.id, name, dev.type, isSource = false, deviceInfo = dev, isBluetooth = isBt))
                }
            }

            // Check paired/bonded audio devices if no Bluetooth speaker found
            val bondedAudio = getBondedAudioDevices()
            if (list.none { it.isBluetooth } && bondedAudio.isNotEmpty()) {
                val firstBonded = bondedAudio.first()
                list.add(AudioDeviceItem(
                    id = 8888,
                    name = "📻 ลำโพงบลูทูธ ($firstBonded)",
                    type = AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    isSource = false,
                    deviceInfo = null,
                    isBluetooth = true
                ))
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error querying audio output devices", e)
        }

        // Always ensure Phone Speaker is in the list
        if (list.none { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }) {
            list.add(0, AudioDeviceItem(0, "🔊 ลำโพงตัวเครื่องมือถือ (Phone Speaker)", AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, false, getBuiltinSpeakerDevice(), false))
        }

        // Always ensure Bluetooth Speaker is in the list!
        if (list.none { it.isBluetooth }) {
            list.add(AudioDeviceItem(8888, "📻 ลำโพงบลูทูธ (Bluetooth Speaker)", AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, false, null, true))
        }

        return list
    }

    /**
     * Get bonded/paired audio devices
     */
    fun getBondedAudioDevices(): List<String> {
        val names = mutableListOf<String>()
        try {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = bluetoothManager?.adapter ?: @Suppress("DEPRECATION") BluetoothAdapter.getDefaultAdapter()
            if (adapter != null && adapter.isEnabled) {
                @Suppress("MissingPermission")
                val bonded = adapter.bondedDevices
                if (bonded != null) {
                    for (dev in bonded) {
                        val devName = try { dev.name } catch (e: Throwable) { null }
                        if (!devName.isNullOrBlank()) {
                            names.add(devName)
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            Log.d(TAG, "getBondedAudioDevices notice: ${e.message}")
        }
        return names
    }

    /**
     * Get summary of current Bluetooth connection status for UI badge
     */
    fun getBluetoothStatusSummary(): String {
        return try {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = bluetoothManager?.adapter ?: @Suppress("DEPRECATION") BluetoothAdapter.getDefaultAdapter()
            if (adapter == null) {
                "อุปกรณ์ไม่รองรับบลูทูธ"
            } else if (!adapter.isEnabled) {
                "บลูทูธปิดอยู่ (แตะปุ่มตั้งค่าเพื่อเปิด)"
            } else {
                val outBt = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).find {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    it.type == 26 || it.type == 27
                }
                val inBt = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).find {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == 26
                }
                val activeDev = inBt ?: outBt
                if (activeDev != null) {
                    val name = activeDev.productName?.toString() ?: "ไมค์/ลำโพงบลูทูธ"
                    "🟢 เชื่อมต่อแล้ว: $name"
                } else {
                    val bonded = getBondedAudioDevices()
                    if (bonded.isNotEmpty()) {
                        "🔵 พร้อมเชื่อมต่อ: ${bonded.first()}"
                    } else {
                        "🔵 พร้อมจับคู่ไมค์/ลำโพงบลูทูธ"
                    }
                }
            }
        } catch (e: Throwable) {
            "พร้อมใช้งานบลูทูธ"
        }
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
