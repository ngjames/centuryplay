package com.airplay.streamer

import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.DynamicColors
import com.google.android.material.materialswitch.MaterialSwitch

class SettingsActivity : AppCompatActivity() {

    companion object {
        const val PREFS_NAME = "airplay_prefs"
        const val KEY_DEBUG_MODE = "debug_mode"
        const val KEY_MANUAL_HOST = "manual_host"
        const val KEY_PROTOCOL_PREFERENCE = "protocol_preference"
        const val KEY_AP2_TIMING = "ap2_timing"
    }

    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        
        // Enable edge-to-edge
        enableEdgeToEdge()
        
        setContentView(R.layout.activity_settings)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        
        // Handle insets for toolbar
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.appBar)) { view: View, windowInsets: WindowInsetsCompat ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars())
            view.setPadding(0, insets.top, 0, 0)
            windowInsets
        }

        // Check for dark mode
        val isNightMode = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        
        // Use light status bar icons (false) for dark theme, dark icons (true) for light theme
        val windowInsetsController = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
        windowInsetsController.isAppearanceLightStatusBars = !isNightMode

        setupToolbar()
        setupMetadataPermission()
        setupGeneralSettings()
        setupAirPlay2Settings()
        setupDebugMode()
        setupFooter()
    }

    private fun setupToolbar() {
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupMetadataPermission() {
        val permissionLayout = findViewById<LinearLayout>(R.id.metadataPermissionLayout)
        val grantButton = findViewById<MaterialButton>(R.id.grantPermissionButton)
        val permissionStatus = findViewById<TextView>(R.id.permissionStatus)

        fun checkPermission(): Boolean {
            val componentName = android.content.ComponentName(this, com.airplay.streamer.service.NotificationListener::class.java)
            val flat = android.provider.Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            return flat != null && flat.contains(componentName.flattenToString())
        }

        fun updateUI() {
            val hasPermission = checkPermission()
            if (hasPermission) {
                permissionStatus.text = "permission active"
                permissionStatus.setTextColor(com.google.android.material.color.MaterialColors.getColor(permissionStatus, com.google.android.material.R.attr.colorOnSurface))
                grantButton.visibility = View.GONE
            } else {
                permissionStatus.text = "permission needed"
                permissionStatus.setTextColor(com.google.android.material.color.MaterialColors.getColor(permissionStatus, com.google.android.material.R.attr.colorError))
                grantButton.visibility = View.VISIBLE
            }
        }

        grantButton.setOnClickListener {
            try {
                startActivity(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(this, "Could not open settings", Toast.LENGTH_SHORT).show()
            }
        }

        // Check on resume to update state if user comes back
        permissionLayout.viewTreeObserver.addOnWindowFocusChangeListener { hasFocus ->
            if (hasFocus) updateUI()
        }
        
        updateUI()

        // Setup Show Now Playing Switch
        val showNowPlayingSwitch = findViewById<MaterialSwitch>(R.id.showNowPlayingSwitch)
        showNowPlayingSwitch.isChecked = prefs.getBoolean("show_now_playing", true)
        
        showNowPlayingSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("show_now_playing", isChecked).apply()
        }

        setupGeneralSettings()
    }

    private fun setupGeneralSettings() {
        // Keep Screen On
        val keepScreenOnSwitch = findViewById<MaterialSwitch>(R.id.keepScreenOnSwitch)
        keepScreenOnSwitch.isChecked = prefs.getBoolean("keep_screen_on", false)
        keepScreenOnSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("keep_screen_on", isChecked).apply()
        }

        // Auto Connect
        val autoConnectSwitch = findViewById<MaterialSwitch>(R.id.autoConnectSwitch)
        autoConnectSwitch.isChecked = prefs.getBoolean("auto_connect", false)
        autoConnectSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("auto_connect", isChecked).apply()
        }
    }

    private fun setupAirPlay2Settings() {
        val protocolRow = findViewById<LinearLayout>(R.id.protocolPreferenceRow)
        val protocolValue = findViewById<TextView>(R.id.protocolPreferenceValue)
        val timingRow = findViewById<LinearLayout>(R.id.ap2TimingRow)
        val timingValue = findViewById<TextView>(R.id.ap2TimingValue)

        val protocolOptions = arrayOf(
            getString(R.string.protocol_auto),
            getString(R.string.protocol_v1),
            getString(R.string.protocol_v2)
        )
        val timingOptions = arrayOf(
            getString(R.string.timing_auto),
            getString(R.string.timing_ptp)
        )

        fun updateProtocolLabel(value: Int) {
            protocolValue.text = when (value) {
                1 -> getString(R.string.protocol_v1)
                2 -> getString(R.string.protocol_v2)
                else -> getString(R.string.protocol_auto)
            }
        }

        fun updateTimingLabel(value: Int) {
            timingValue.text = if (value == 1) getString(R.string.timing_ptp) else getString(R.string.timing_auto)
        }

        val protocol = prefs.getInt(KEY_PROTOCOL_PREFERENCE, 0)
        val timing = prefs.getInt(KEY_AP2_TIMING, 0)
        updateProtocolLabel(protocol)
        updateTimingLabel(timing)

        protocolRow.setOnClickListener {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.protocol_preference)
                .setSingleChoiceItems(protocolOptions, protocol) { dialog, which ->
                    prefs.edit { putInt(KEY_PROTOCOL_PREFERENCE, which) }
                    updateProtocolLabel(which)
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        timingRow.setOnClickListener {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.ap2_timing)
                .setSingleChoiceItems(timingOptions, timing) { dialog, which ->
                    prefs.edit { putInt(KEY_AP2_TIMING, which) }
                    updateTimingLabel(which)
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun setupDebugMode() {
        val debugSwitch = findViewById<MaterialSwitch>(R.id.debugModeSwitch)
        val debugSection = findViewById<LinearLayout>(R.id.debugSection)
        val manualHostInput = findViewById<EditText>(R.id.manualHostInput)
        val connectButton = findViewById<MaterialButton>(R.id.manualConnectButton)

        val debugEnabled = prefs.getBoolean(KEY_DEBUG_MODE, false)
        debugSwitch.isChecked = debugEnabled
        debugSection.visibility = if (debugEnabled) View.VISIBLE else View.GONE

        // Load saved host
        manualHostInput.setText(prefs.getString(KEY_MANUAL_HOST, "192.168.1.100:5000"))

        debugSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit { putBoolean(KEY_DEBUG_MODE, isChecked) }
            debugSection.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        connectButton.setOnClickListener {
            val input = manualHostInput.text.toString().trim()
            if (input.isNotEmpty()) {
                prefs.edit { putString(KEY_MANUAL_HOST, input) }
                addManualDevice(input)
            }
        }
    }

    private fun addManualDevice(input: String) {
        try {
            val parts = input.split(":")
            if (parts.size != 2) {
                Toast.makeText(this, "Invalid format. Use IP:Port", Toast.LENGTH_SHORT).show()
                return
            }
            val host = parts[0]
            val port = parts[1].toIntOrNull() ?: throw NumberFormatException()

            // Store the manual device info
            prefs.edit {
                putString("manual_device_host", host)
                putInt("manual_device_port", port)
                putBoolean("manual_device_pending", true)
            }
            
            Toast.makeText(this, "Manual device saved: $host:$port\nGo back to connect", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Invalid input: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupFooter() {
        val footer = findViewById<TextView>(R.id.developerFooter)
        footer.setOnClickListener {
            try {
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/g8row/centuryplay"))
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Could not open browser", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
