package org.fossify.keyboard.dialogs

import android.app.Activity
import android.widget.SeekBar
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.keyboard.R
import org.fossify.keyboard.databinding.DialogSliderSettingBinding

/**
 * Material dialog displaying a customizable slider (SeekBar) for real-time numeric settings.
 */
class SliderPreferenceDialog(
    private val activity: Activity,
    private val title: String,
    private val min: Int,
    private val max: Int,
    private val initialValue: Int,
    private val step: Int = 1,
    private val formatValue: (Int) -> String,
    private val onProgressChanged: ((Int) -> Unit)? = null,
    private val onConfirmed: (Int) -> Unit
) {
    fun show() {
        val binding = DialogSliderSettingBinding.inflate(activity.layoutInflater)
        var currentValue = initialValue.coerceIn(min, max)

        binding.dialogSliderMinLabel.text = formatValue(min)
        binding.dialogSliderMaxLabel.text = formatValue(max)
        binding.dialogSliderValue.text = formatValue(currentValue)

        val totalSteps = ((max - min) / step).coerceAtLeast(1)
        binding.dialogSliderSeekbar.max = totalSteps
        binding.dialogSliderSeekbar.progress = ((currentValue - min) / step).coerceIn(0, totalSteps)

        binding.dialogSliderSeekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentValue = (min + progress * step).coerceIn(min, max)
                binding.dialogSliderValue.text = formatValue(currentValue)
                if (fromUser) {
                    onProgressChanged?.invoke(currentValue)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        activity.getAlertDialogBuilder()
            .setTitle(title)
            .setPositiveButton(R.string.ok) { _, _ -> onConfirmed(currentValue) }
            .setNegativeButton(R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(binding.root, this)
            }
    }
}
