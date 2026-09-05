package com.antigravity.androidmic

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.antigravity.androidmic.audio.AudioDeviceItem
import com.antigravity.androidmic.audio.AudioDeviceManager
import com.antigravity.androidmic.databinding.ActivityMainBinding
import kotlin.math.log10

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var amplifierService: MicAmplifierService? = null
    private var isBound = false

    private val inputDevices = mutableListOf<AudioDeviceItem>()
    private lateinit var inputSpinnerAdapter: ArrayAdapter<String>

    private val outputDevices = mutableListOf<AudioDeviceItem>()
    private lateinit var outputSpinnerAdapter: ArrayAdapter<String>

    private var isBtReceiverRegistered = false

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            when (action) {
                BluetoothDevice.ACTION_ACL_CONNECTED,
                BluetoothDevice.ACTION_ACL_DISCONNECTED,
                BluetoothAdapter.ACTION_STATE_CHANGED,
                AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED -> {
                    runOnUiThread {
                        refreshDeviceList()
                    }
                }
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MicAmplifierService.LocalBinder
            val svc = binder.getService()
            amplifierService = svc
            isBound = true

            // Attach callbacks
            svc.engine.onAudioLevelUpdated = { peak, rms, db ->
                runOnUiThread {
                    binding.visualizerView.updateAudioLevel(peak, rms, db)
                }
            }

            svc.engine.onError = { message ->
                runOnUiThread {
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                    updateUiState(false)
                }
            }

            svc.engine.deviceManager.onDevicesChanged = {
                runOnUiThread {
                    refreshDeviceList()
                }
            }

            // Sync UI with current engine state
            updateUiState(svc.engine.isActive)
            binding.switchAntiHowl.isChecked = svc.engine.dspProcessor.antiHowl.isEnabled
            binding.switchAggressiveNotch.isChecked = svc.engine.dspProcessor.antiHowl.isAggressiveMode

            val dsp = svc.engine.dspProcessor
            binding.sliderGain.value = dsp.gain.coerceIn(1.0f, 6.0f)
            val dbVal = (20.0 * log10(dsp.gain.toDouble())).toInt()
            binding.tvGainLabel.text = "ขยายความดัง (Gain Boost): ${String.format("%.1f", dsp.gain)}x (+${dbVal} dB)"

            val gatePct = (dsp.noiseGateThreshold * 100.0f).coerceIn(0.0f, 8.0f)
            binding.sliderNoiseGate.value = gatePct
            if (gatePct <= 0.05f) {
                binding.tvNoiseGateLabel.text = "ตัดเสียงรบกวน (Noise Gate): ปิด (0%)"
            } else {
                binding.tvNoiseGateLabel.text = "ตัดเสียงรบกวน (Noise Gate): ${String.format("%.1f", gatePct)}%"
            }

            val echo = svc.engine.dspProcessor.echo
            binding.switchEcho.isChecked = echo.isEnabled
            binding.layoutEchoControls.alpha = if (echo.isEnabled) 1.0f else 0.45f
            binding.sliderEchoVolume.value = (echo.wetMix * 100.0f).toInt().toFloat().coerceIn(0f, 80f)
            binding.sliderEchoDelay.value = (echo.delayMs / 10 * 10).toFloat().coerceIn(80f, 420f)
            binding.sliderEchoRepeats.value = (echo.decay * 100.0f).toInt().toFloat().coerceIn(10f, 70f)

            // SCO callback: UI only — engine handles AudioRecord rebinding internally via its own BroadcastReceiver
            svc.engine.onScoConnected = {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "🎙️ ไมค์บลูทูธเชื่อมต่อสัญญาณเสียงสำเร็จ (Bluetooth Mic Ready)", Toast.LENGTH_SHORT).show()
                    refreshDeviceList()
                }
            }

            // Sync current spinner selections to engine
            val inPos = binding.spinnerInputSource.selectedItemPosition
            if (inPos in inputDevices.indices) {
                svc.engine.preferredInputItem = inputDevices[inPos]
            }
            val outPos = binding.spinnerOutputRoute.selectedItemPosition
            if (outPos in outputDevices.indices) {
                svc.engine.preferredOutputItem = outputDevices[outPos]
            }

            refreshDeviceList()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            amplifierService = null
            isBound = false
            updateUiState(false)
        }
    }

    private val initialPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        refreshDeviceList()
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val recordAudioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        if (recordAudioGranted) {
            refreshDeviceList()
            startAmplifierService()
        } else {
            Toast.makeText(this, getString(R.string.permission_rationale), Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupDeviceSpinners()
        setupControls()
        checkInitialPermissions()
    }

    private fun checkInitialPermissions() {
        val permissionsNeeded = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_SCAN)
            }
        }
        if (permissionsNeeded.isNotEmpty()) {
            initialPermissionLauncher.launch(permissionsNeeded.toTypedArray())
        }
    }

    override fun onResume() {
        super.onResume()
        refreshDeviceList()
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, MicAmplifierService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        if (!isBtReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
            }
            registerReceiver(bluetoothReceiver, filter)
            isBtReceiverRegistered = true
        }
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        if (isBtReceiverRegistered) {
            try {
                unregisterReceiver(bluetoothReceiver)
                isBtReceiverRegistered = false
            } catch (e: Exception) {}
        }
    }

    private fun setupControls() {
        // Bluetooth Settings Quick Launch Button
        binding.btnOpenBtSettings.setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                startActivity(intent)
            } catch (e: Throwable) {
                Toast.makeText(this, "ไม่สามารถเปิดหน้าตั้งค่าบลูทูธได้", Toast.LENGTH_SHORT).show()
            }
        }

        // Bluetooth / Device Refresh Button
        binding.btnRefreshDevices.setOnClickListener {
            refreshDeviceList()
            Toast.makeText(this, "🔄 อัปเดตรายชื่ออุปกรณ์และสถานะบลูทูธแล้ว", Toast.LENGTH_SHORT).show()
        }

        // Power button toggle
        binding.btnPowerToggle.setOnClickListener {
            val isActive = amplifierService?.engine?.isActive == true
            if (isActive) {
                stopAmplifierService()
            } else {
                checkPermissionsAndStart()
            }
        }

        // Anti-Howl Feedback Suppressor switches
        binding.switchAntiHowl.setOnCheckedChangeListener { _, isChecked ->
            amplifierService?.engine?.dspProcessor?.antiHowl?.isEnabled = isChecked
            binding.switchAggressiveNotch.isEnabled = isChecked
        }

        binding.switchAggressiveNotch.setOnCheckedChangeListener { _, isChecked ->
            amplifierService?.engine?.dspProcessor?.antiHowl?.isAggressiveMode = isChecked
        }

        // Vocal Karaoke Echo switch
        binding.switchEcho.setOnCheckedChangeListener { _, isChecked ->
            amplifierService?.engine?.dspProcessor?.echo?.isEnabled = isChecked
            binding.layoutEchoControls.alpha = if (isChecked) 1.0f else 0.45f
            binding.sliderEchoVolume.isEnabled = isChecked
            binding.sliderEchoDelay.isEnabled = isChecked
            binding.sliderEchoRepeats.isEnabled = isChecked
            binding.btnPresetKaraoke.isEnabled = isChecked
            binding.btnPresetHall.isEnabled = isChecked
            binding.btnPresetStudio.isEnabled = isChecked
        }

        // Echo Volume slider
        binding.sliderEchoVolume.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                binding.tvEchoVolumeLabel.text = "ระดับเสียงสะท้อน (Echo Volume): ${value.toInt()}%"
                amplifierService?.engine?.dspProcessor?.echo?.wetMix = value / 100.0f
            }
        }

        // Echo Delay Time slider
        binding.sliderEchoDelay.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                binding.tvEchoDelayLabel.text = "จังหวะการสะท้อน (Delay Time): ${value.toInt()} ms"
                amplifierService?.engine?.dspProcessor?.echo?.delayMs = value.toInt()
            }
        }

        // Echo Repeats / Decay slider
        binding.sliderEchoRepeats.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                binding.tvEchoRepeatsLabel.text = "จำนวนครั้งที่สะท้อน (Repeats / Decay): ${value.toInt()}%"
                amplifierService?.engine?.dspProcessor?.echo?.decay = value / 100.0f
            }
        }

        // Echo Presets
        binding.btnPresetKaraoke.setOnClickListener {
            applyEchoPreset(volume = 35f, delayMs = 220f, repeats = 40f)
        }
        binding.btnPresetHall.setOnClickListener {
            applyEchoPreset(volume = 45f, delayMs = 320f, repeats = 55f)
        }
        binding.btnPresetStudio.setOnClickListener {
            applyEchoPreset(volume = 25f, delayMs = 130f, repeats = 25f)
        }

        // Gain Boost Slider
        binding.sliderGain.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val dbVal = (20.0 * log10(value.toDouble())).toInt()
                binding.tvGainLabel.text = "ขยายความดัง (Gain Boost): ${String.format("%.1f", value)}x (+${dbVal} dB)"
                amplifierService?.engine?.dspProcessor?.gain = value
            }
        }

        // Noise Gate Slider
        binding.sliderNoiseGate.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                if (value <= 0.05f) {
                    binding.tvNoiseGateLabel.text = "ตัดเสียงรบกวน (Noise Gate): ปิด (0%)"
                    amplifierService?.engine?.dspProcessor?.noiseGateThreshold = 0.0f
                } else {
                    binding.tvNoiseGateLabel.text = "ตัดเสียงรบกวน (Noise Gate): ${String.format("%.1f", value)}%"
                    amplifierService?.engine?.dspProcessor?.noiseGateThreshold = value / 100.0f
                }
            }
        }
    }

    private fun applyEchoPreset(volume: Float, delayMs: Float, repeats: Float) {
        binding.sliderEchoVolume.value = volume
        binding.sliderEchoDelay.value = delayMs
        binding.sliderEchoRepeats.value = repeats
        binding.tvEchoVolumeLabel.text = "ระดับเสียงสะท้อน (Echo Volume): ${volume.toInt()}%"
        binding.tvEchoDelayLabel.text = "จังหวะการสะท้อน (Delay Time): ${delayMs.toInt()} ms"
        binding.tvEchoRepeatsLabel.text = "จำนวนครั้งที่สะท้อน (Repeats / Decay): ${repeats.toInt()}%"

        val echo = amplifierService?.engine?.dspProcessor?.echo ?: return
        echo.wetMix = volume / 100.0f
        echo.delayMs = delayMs.toInt()
        echo.decay = repeats / 100.0f
    }

    private fun setupDeviceSpinners() {
        // 1. Input Devices Spinner
        inputSpinnerAdapter = ArrayAdapter(
            this,
            R.layout.item_spinner_selected,
            mutableListOf<String>()
        ).apply {
            setDropDownViewResource(R.layout.item_spinner_dropdown)
        }
        binding.spinnerInputSource.adapter = inputSpinnerAdapter

        binding.spinnerInputSource.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position in inputDevices.indices) {
                    val selected = inputDevices[position]
                    amplifierService?.engine?.preferredInputItem = selected

                    syncDspControlsFromEngine()

                    if (selected.isBluetooth || selected.id == 9999 || selected.name.contains("บลูทูธ") || selected.name.contains("Bluetooth")) {
                        Toast.makeText(this@MainActivity, "🎙️ เลือกใช้: ${selected.name}", Toast.LENGTH_SHORT).show()
                    }

                    // Feedback warning if user selects phone's built-in mic while output is phone speaker
                    val currentOut = amplifierService?.engine?.preferredOutputItem
                    val isSpeaker = currentOut == null || currentOut.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    if (selected.type == AudioDeviceInfo.TYPE_BUILTIN_MIC && isSpeaker) {
                        showFeedbackWarningIfNeeded()
                    }
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 2. Output Devices Spinner (Phone Speaker vs Bluetooth Speaker)
        outputSpinnerAdapter = ArrayAdapter(
            this,
            R.layout.item_spinner_selected,
            mutableListOf<String>()
        ).apply {
            setDropDownViewResource(R.layout.item_spinner_dropdown)
        }
        binding.spinnerOutputRoute.adapter = outputSpinnerAdapter

        binding.spinnerOutputRoute.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position in outputDevices.indices) {
                    val selected = outputDevices[position]
                    amplifierService?.engine?.preferredOutputItem = selected
                    syncDspControlsFromEngine()

                    if (selected.isBluetooth || selected.id == 8888 || selected.name.contains("บลูทูธ") || selected.name.contains("Bluetooth")) {
                        Toast.makeText(this@MainActivity, "📻 ส่งเสียงออก: ${selected.name}", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Immediate initial load so spinners are never empty
        loadInitialDevices()
    }

    private fun syncDspControlsFromEngine() {
        val svc = amplifierService ?: return
        val dsp = svc.engine.dspProcessor
        binding.switchAntiHowl.isChecked = dsp.antiHowl.isEnabled
        binding.switchAggressiveNotch.isChecked = dsp.antiHowl.isAggressiveMode
        binding.sliderGain.value = dsp.gain.coerceIn(1.0f, 6.0f)
        val dbVal = (20.0 * log10(dsp.gain.toDouble())).toInt()
        binding.tvGainLabel.text = "ขยายความดัง (Gain Boost): ${String.format("%.1f", dsp.gain)}x (+${dbVal} dB)"
        val gatePct = (dsp.noiseGateThreshold * 100.0f).coerceIn(0.0f, 8.0f)
        binding.sliderNoiseGate.value = gatePct
        if (gatePct <= 0.05f) {
            binding.tvNoiseGateLabel.text = "ตัดเสียงรบกวน (Noise Gate): ปิด (0%)"
        } else {
            binding.tvNoiseGateLabel.text = "ตัดเสียงรบกวน (Noise Gate): ${String.format("%.1f", gatePct)}%"
        }
    }

    private fun loadInitialDevices() {
        val tempManager = AudioDeviceManager(this)
        val inDevs = tempManager.getAvailableInputDevices()
        inputDevices.clear()
        inputDevices.addAll(inDevs)
        inputSpinnerAdapter.clear()
        inputSpinnerAdapter.addAll(inDevs.map { it.name })
        inputSpinnerAdapter.notifyDataSetChanged()

        val outDevs = tempManager.getAvailableOutputDevices()
        outputDevices.clear()
        outputDevices.addAll(outDevs)
        outputSpinnerAdapter.clear()
        outputSpinnerAdapter.addAll(outDevs.map { it.name })
        outputSpinnerAdapter.notifyDataSetChanged()

        // Default: Index 0 is Bluetooth mic, and speaker is phone speaker
        binding.spinnerInputSource.setSelection(0)
        val speakerIdx = outDevs.indexOfFirst { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        if (speakerIdx >= 0) {
            binding.spinnerOutputRoute.setSelection(speakerIdx)
        }

        updateBluetoothStatusUI(tempManager)
    }

    private fun updateBluetoothStatusUI(manager: AudioDeviceManager) {
        val summary = manager.getBluetoothStatusSummary()
        binding.tvBtStatusDesc.text = summary
        if (summary.contains("🟢")) {
            binding.tvBtStatusDesc.setTextColor(ContextCompat.getColor(this, R.color.emerald_active))
            binding.tvBtBadge.text = "🟢 เชื่อมต่อแล้ว"
            binding.tvBtBadge.setTextColor(ContextCompat.getColor(this, R.color.emerald_active))
        } else {
            binding.tvBtStatusDesc.setTextColor(ContextCompat.getColor(this, R.color.cyan_accent))
            binding.tvBtBadge.text = "🔵 บลูทูธ"
            binding.tvBtBadge.setTextColor(ContextCompat.getColor(this, R.color.cyan_accent))
        }
    }

    private var hasShownWarning = false
    private fun showFeedbackWarningIfNeeded() {
        if (!hasShownWarning) {
            hasShownWarning = true
            AlertDialog.Builder(this)
                .setTitle("คำเตือน: โอกาสเกิดเสียงหอน")
                .setMessage("คุณกำลังเลือกใช้ 'ไมค์ตัวเครื่องมือถือ' พร้อมกับเปิดออกลำโพงมือถือ ซึ่งอาจทำให้เกิดเสียงหวีดหอน (Larsen effect) ได้หากเร่งเสียงดัง แนะนำให้ใช้หูฟังหรือไมค์แยก หรือต่อลำโพงบลูทูธเพื่อความปลอดภัย")
                .setPositiveButton("เข้าใจแล้ว", null)
                .show()
        }
    }

    private fun refreshDeviceList() {
        val manager = amplifierService?.engine?.deviceManager ?: AudioDeviceManager(this)

        // 1. Refresh Input Devices
        val inDevs = manager.getAvailableInputDevices()
        val currentSelectedIn = if (binding.spinnerInputSource.selectedItemPosition in inputDevices.indices) {
            inputDevices[binding.spinnerInputSource.selectedItemPosition]
        } else {
            amplifierService?.engine?.preferredInputItem
        }

        inputDevices.clear()
        inputDevices.addAll(inDevs)
        inputSpinnerAdapter.clear()
        inputSpinnerAdapter.addAll(inDevs.map { it.name })
        inputSpinnerAdapter.notifyDataSetChanged()

        if (currentSelectedIn != null) {
            val matchIdx = inDevs.indexOfFirst {
                (it.deviceInfo != null && it.deviceInfo.id == currentSelectedIn.deviceInfo?.id) ||
                (it.id == currentSelectedIn.id) ||
                (it.isBluetooth && currentSelectedIn.isBluetooth)
            }
            if (matchIdx >= 0) {
                binding.spinnerInputSource.setSelection(matchIdx)
            } else if (inDevs.isNotEmpty()) {
                binding.spinnerInputSource.setSelection(0)
            }
        } else if (inDevs.isNotEmpty()) {
            binding.spinnerInputSource.setSelection(0)
        }

        // 2. Refresh Output Devices (Loudspeaker, Bluetooth, Headphones)
        val outDevs = manager.getAvailableOutputDevices()
        val currentSelectedOut = if (binding.spinnerOutputRoute.selectedItemPosition in outputDevices.indices) {
            outputDevices[binding.spinnerOutputRoute.selectedItemPosition]
        } else {
            amplifierService?.engine?.preferredOutputItem
        }

        outputDevices.clear()
        outputDevices.addAll(outDevs)
        outputSpinnerAdapter.clear()
        outputSpinnerAdapter.addAll(outDevs.map { it.name })
        outputSpinnerAdapter.notifyDataSetChanged()

        if (currentSelectedOut != null) {
            val matchIdx = outDevs.indexOfFirst {
                (it.deviceInfo != null && it.deviceInfo.id == currentSelectedOut.deviceInfo?.id) ||
                (it.id == currentSelectedOut.id) ||
                (it.isBluetooth && currentSelectedOut.isBluetooth)
            }
            if (matchIdx >= 0) {
                binding.spinnerOutputRoute.setSelection(matchIdx)
            } else {
                val speakerIdx = outDevs.indexOfFirst { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if (speakerIdx >= 0) {
                    binding.spinnerOutputRoute.setSelection(speakerIdx)
                }
            }
        } else {
            val speakerIdx = outDevs.indexOfFirst { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            if (speakerIdx >= 0) {
                binding.spinnerOutputRoute.setSelection(speakerIdx)
            }
        }

        updateBluetoothStatusUI(manager)
    }

    private fun checkPermissionsAndStart() {
        val permissionsNeeded = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.RECORD_AUDIO)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_SCAN)
            }
        }

        if (permissionsNeeded.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsNeeded.toTypedArray())
        } else {
            startAmplifierService()
        }
    }

    private fun startAmplifierService() {
        val intent = Intent(this, MicAmplifierService::class.java)
        ContextCompat.startForegroundService(this, intent)
        updateUiState(true)
    }

    private fun stopAmplifierService() {
        amplifierService?.stopAmplifier()
        val intent = Intent(this, MicAmplifierService::class.java)
        stopService(intent)
        updateUiState(false)
        binding.visualizerView.reset()
    }

    private fun updateUiState(isActive: Boolean) {
        if (isActive) {
            binding.btnPowerToggle.setBackgroundResource(R.drawable.bg_power_button_on)
            binding.btnPowerToggle.setColorFilter(Color.WHITE)
            binding.tvPowerStatus.text = getString(R.string.btn_stop)
            binding.tvPowerStatus.setTextColor(ContextCompat.getColor(this, R.color.cyan_accent))
            binding.tvPowerSubtext.text = "แตะเพื่อหยุดการส่งเสียง"

            binding.tvLiveBadge.text = getString(R.string.status_active)
            binding.tvLiveBadge.setTextColor(ContextCompat.getColor(this, R.color.emerald_active))
        } else {
            binding.btnPowerToggle.setBackgroundResource(R.drawable.bg_power_button_off)
            binding.btnPowerToggle.setColorFilter(ContextCompat.getColor(this, R.color.cyan_accent))
            binding.tvPowerStatus.text = getString(R.string.btn_start)
            binding.tvPowerStatus.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            binding.tvPowerSubtext.text = "แตะเพื่อเริ่มส่งเสียงไมค์ออกลำโพง"

            binding.tvLiveBadge.text = getString(R.string.status_standby)
            binding.tvLiveBadge.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }
    }
}
