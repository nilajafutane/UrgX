package com.nilaja.urgx

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment

class SettingsFragment : Fragment() {

    private val sensitivityLabels = listOf("Very Low", "Low", "Medium", "High", "Very High")
    private val armTimeoutValues  = listOf(5, 10, 15, 20)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val prefs = requireContext()
            .getSharedPreferences("urgx_settings", Context.MODE_PRIVATE)

        val seekSens    = view.findViewById<SeekBar>(R.id.seekSensitivity)
        val tvSensVal   = view.findViewById<TextView>(R.id.tvSensitivityValue)
        val seekArm     = view.findViewById<SeekBar>(R.id.seekArmTimeout)
        val tvArmVal    = view.findViewById<TextView>(R.id.tvArmTimeoutValue)
        val switchVoice = view.findViewById<Switch>(R.id.switchVoice)

        // Load saved settings
        seekSens.progress     = prefs.getInt("sensitivity", 2)
        tvSensVal.text        = sensitivityLabels[seekSens.progress]
        seekArm.progress      = prefs.getInt("arm_timeout_index", 1)
        tvArmVal.text         = "${armTimeoutValues[seekArm.progress]} seconds"
        switchVoice.isChecked = prefs.getBoolean("voice_enabled", true)

        // Sensitivity slider
        seekSens.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, user: Boolean) {
                tvSensVal.text = sensitivityLabels[p]
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // Arm timeout slider
        seekArm.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, user: Boolean) {
                tvArmVal.text = "${armTimeoutValues[p]} seconds"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // Save button
        view.findViewById<Button>(R.id.btnSaveSettings).setOnClickListener {
            prefs.edit()
                .putInt("sensitivity", seekSens.progress)
                .putInt("arm_timeout_index", seekArm.progress)
                .putBoolean("voice_enabled", switchVoice.isChecked)
                .apply()

            val thresholds = listOf(6f, 8f, 10f, 13f, 16f)

            // ✅ Fixed parameter names
            SOSService.updateSettings(
                threshold  = thresholds[seekSens.progress],
                armTimeout = armTimeoutValues[seekArm.progress] * 1000L,
                voiceOn    = switchVoice.isChecked
            )

            Toast.makeText(requireContext(), "Settings saved!", Toast.LENGTH_SHORT).show()
        }
    }
}