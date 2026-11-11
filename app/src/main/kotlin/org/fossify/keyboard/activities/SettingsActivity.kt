package org.fossify.keyboard.activities

import android.content.Intent
import android.os.Bundle
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.extensions.beGoneIf
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.isTiramisuPlus
import org.fossify.commons.models.RadioItem
import org.fossify.keyboard.R
import org.fossify.keyboard.databinding.ActivitySettingsBinding
import org.fossify.keyboard.dialogs.ManageKeyboardLanguagesDialog
import org.fossify.keyboard.dialogs.ManageExcludedAppsDialog
import org.fossify.keyboard.dialogs.SliderPreferenceDialog
import org.fossify.keyboard.extensions.config
import org.fossify.keyboard.helpers.FeedbackManager
import org.fossify.keyboard.helpers.InputHistoryRecordingEngine
import org.fossify.keyboard.helpers.MyKeyboard
import org.fossify.keyboard.helpers.TouchPersonalizationManager
import org.fossify.keyboard.extensions.getCurrentVoiceInputMethod
import org.fossify.keyboard.extensions.getKeyboardLanguageText
import org.fossify.keyboard.extensions.getKeyboardLanguagesRadioItems
import org.fossify.keyboard.extensions.getVoiceInputMethods
import org.fossify.keyboard.extensions.getVoiceInputRadioItems
import org.fossify.keyboard.helpers.KEYBOARD_HEIGHT_100_PERCENT
import org.fossify.keyboard.helpers.KEYBOARD_HEIGHT_120_PERCENT
import org.fossify.keyboard.helpers.KEYBOARD_HEIGHT_140_PERCENT
import org.fossify.keyboard.helpers.KEYBOARD_HEIGHT_160_PERCENT
import org.fossify.keyboard.helpers.KEYBOARD_HEIGHT_70_PERCENT
import org.fossify.keyboard.helpers.KEYBOARD_HEIGHT_80_PERCENT
import org.fossify.keyboard.helpers.KEYBOARD_HEIGHT_90_PERCENT
import org.fossify.keyboard.helpers.SOUND_ALWAYS
import org.fossify.keyboard.helpers.SOUND_NONE
import org.fossify.keyboard.helpers.SOUND_SYSTEM
import java.util.Locale
import kotlin.system.exitProcess

class SettingsActivity : SimpleActivity() {
    private val binding by viewBinding(ActivitySettingsBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        binding.apply {
            setupEdgeToEdge(padBottomSystem = listOf(settingsNestedScrollview))
            setupMaterialScrollListener(binding.settingsNestedScrollview, binding.settingsAppbar)
        }
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.settingsAppbar, NavigationIcon.Arrow)

        setupAutoSyncMaterialYou()
        setupCustomizeColors()
        setupUseEnglish()
        setupLanguage()
        setupManageClipboardItems()
        setupVibrateOnKeypress()
        setupHapticStrength()
        setupSoundOnKeypress()
        setupSoundVolume()
        setupLongPressDelay()
        setupShowPopupOnKeypress()
        setupPersonaliseTouch()
        setupResetTouchData()
        setupShowTouchDebugOverlay()
        setupShowKeyBorders()
        setupManageKeyboardLanguages()
        setupKeyboardLanguage()
        setupKeyboardHeightMultiplier()
        setupShowEmojiKey()
        setupShowLanguageSwitchKey()
        setupShowClipboardContent()
        setupSentencesCapitalization()
        setupShowNumbersRow()
        setupKeyboardHeightPreset()
        setupOneHandedMode()
        setupShowKeyBordersToggle()
        setupVoiceInputMethod()
        setupRecordInputHistory()
        setupExcludedApps()
        setupInputHistory()
        setupHistoryRetention()
        setupHistoryLock()
        setupDebugLogs()
        setupPersonalDictionary()

        binding.apply {
            updateTextColors(settingsNestedScrollview)

            arrayOf(
                settingsColorCustomizationSectionLabel,
                settingsGeneralSettingsLabel,
                settingsLayoutAppearanceLabel,
                settingsKeypressLabel,
                settingsTypingInputLabel,
                settingsClipboardSettingsLabel,
                settingsInputHistoryLabel,
                settingsPersonalDictionarySectionLabel
            ).forEach {
                it.setTextColor(getProperPrimaryColor())
            }
        }
    }

    private fun setupAutoSyncMaterialYou() {
        binding.apply {
            settingsAutoSyncMaterialYou.isChecked = config.autoSyncMaterialYou
            settingsColorCustomizationHolder.beGoneIf(config.autoSyncMaterialYou)
            settingsAutoSyncMaterialYouHolder.setOnClickListener {
                settingsAutoSyncMaterialYou.toggle()
                config.autoSyncMaterialYou = settingsAutoSyncMaterialYou.isChecked
                settingsColorCustomizationHolder.beGoneIf(config.autoSyncMaterialYou)
            }
        }
    }

    private fun setupCustomizeColors() {
        binding.apply {
            settingsColorCustomizationHolder.setOnClickListener {
                startCustomizationActivity()
            }
        }
    }

    private fun setupUseEnglish() {
        binding.apply {
            settingsUseEnglishHolder.beVisibleIf((config.wasUseEnglishToggled || Locale.getDefault().language != "en") && !isTiramisuPlus())
            settingsUseEnglish.isChecked = config.useEnglish
            settingsUseEnglishHolder.setOnClickListener {
                settingsUseEnglish.toggle()
                config.useEnglish = settingsUseEnglish.isChecked
                exitProcess(0)
            }
        }
    }

    private fun setupLanguage() {
        binding.apply {
            settingsLanguage.text = Locale.getDefault().displayLanguage
            settingsLanguageHolder.beVisibleIf(isTiramisuPlus())
            settingsLanguageHolder.setOnClickListener {
                launchChangeAppLanguageIntent()
            }
        }
    }

    private fun setupManageClipboardItems() {
        binding.settingsManageClipboardItemsHolder.setOnClickListener {
            Intent(this, ManageClipboardItemsActivity::class.java).apply {
                startActivity(this)
            }
        }
    }

    private fun setupRecordInputHistory() {
        binding.apply {
            settingsRecordInputHistory.isChecked = config.recordInputHistory
            settingsRecordInputHistoryHolder.setOnClickListener {
                settingsRecordInputHistory.toggle()
                config.recordInputHistory = settingsRecordInputHistory.isChecked
                if (!config.recordInputHistory) {
                    InputHistoryRecordingEngine.stopRecording()
                }
            }
        }
    }

    private fun setupExcludedApps() {
        binding.apply {
            updateExcludedAppsSummary()
            settingsExcludedAppsHolder.setOnClickListener {
                ManageExcludedAppsDialog(this@SettingsActivity) {
                    updateExcludedAppsSummary()
                }.show()
            }
        }
    }

    private fun updateExcludedAppsSummary() {
        val count = config.blockedHistoryPackages.size
        binding.settingsExcludedAppsSummary.text = when (count) {
            0 -> getString(R.string.no_excluded_apps)
            1 -> "1 app excluded"
            else -> "$count apps excluded"
        }
    }

    private fun setupInputHistory() {
        binding.settingsInputHistoryHolder.setOnClickListener {
            Intent(this, InputHistoryActivity::class.java).apply {
                startActivity(this)
            }
        }
    }

    private fun setupHistoryRetention() {
        val retentionOptions = listOf(
            0 to getString(R.string.history_retention_forever),
            7 to getString(R.string.history_retention_7_days),
            30 to getString(R.string.history_retention_30_days),
            90 to getString(R.string.history_retention_90_days)
        )
        binding.apply {
            val currentDays = config.historyRetentionDays
            settingsHistoryRetentionValue.text = retentionOptions.firstOrNull { it.first == currentDays }?.second
                ?: getString(R.string.history_retention_forever)

            settingsHistoryRetentionHolder.setOnClickListener {
                val items = retentionOptions.map { (days, label) -> RadioItem(days, label) }.toCollection(ArrayList())
                RadioGroupDialog(
                    activity = this@SettingsActivity,
                    items = items,
                    checkedItemId = config.historyRetentionDays
                ) {
                    val selectedDays = it as Int
                    config.historyRetentionDays = selectedDays
                    settingsHistoryRetentionValue.text = retentionOptions.firstOrNull { opt -> opt.first == selectedDays }?.second
                        ?: getString(R.string.history_retention_forever)
                }
            }
        }
    }

    private fun setupHistoryLock() {
        binding.apply {
            settingsHistoryLock.isChecked = config.historyLockEnabled
            settingsHistoryLockHolder.setOnClickListener {
                settingsHistoryLock.toggle()
                config.historyLockEnabled = settingsHistoryLock.isChecked
            }
        }
    }

    private fun setupVibrateOnKeypress() {
        binding.apply {
            settingsVibrateOnKeypress.isChecked = config.vibrateOnKeypress
            settingsHapticStrengthHolder.beVisibleIf(config.vibrateOnKeypress)
            settingsVibrateOnKeypressHolder.setOnClickListener {
                settingsVibrateOnKeypress.toggle()
                config.vibrateOnKeypress = settingsVibrateOnKeypress.isChecked
                settingsHapticStrengthHolder.beVisibleIf(config.vibrateOnKeypress)
            }
        }
    }

    private fun setupHapticStrength() {
        binding.apply {
            settingsHapticStrength.text = "${config.hapticStrength}%"
            settingsHapticStrengthHolder.setOnClickListener {
                val feedbackManager = FeedbackManager(this@SettingsActivity)
                SliderPreferenceDialog(
                    activity = this@SettingsActivity,
                    title = getString(R.string.haptic_strength),
                    min = 1,
                    max = 100,
                    initialValue = config.hapticStrength,
                    step = 1,
                    formatValue = { "$it%" },
                    onProgressChanged = { strength ->
                        config.hapticStrength = strength
                        feedbackManager.performHaptic(binding.root)
                    },
                    onConfirmed = { strength ->
                        config.hapticStrength = strength
                        settingsHapticStrength.text = "$strength%"
                    }
                ).show()
            }
        }
    }

    private fun setupSoundOnKeypress() {
        binding.apply {
            settingsSoundOnKeypress.text = getSoundOnKeypressText(config.soundOnKeypress)
            settingsSoundVolumeHolder.beVisibleIf(config.soundOnKeypress != SOUND_NONE)
            settingsSoundOnKeypressHolder.setOnClickListener {
                val items = arrayListOf(
                    RadioItem(SOUND_NONE, getString(R.string.sound_none)),
                    RadioItem(SOUND_SYSTEM, getString(R.string.sound_system)),
                    RadioItem(SOUND_ALWAYS, getString(R.string.sound_always))
                )

                RadioGroupDialog(
                    activity = this@SettingsActivity,
                    items = items,
                    checkedItemId = config.soundOnKeypress
                ) {
                    config.soundOnKeypress = it as Int
                    settingsSoundOnKeypress.text = getSoundOnKeypressText(config.soundOnKeypress)
                    settingsSoundVolumeHolder.beVisibleIf(config.soundOnKeypress != SOUND_NONE)
                }
            }
        }
    }

    private fun setupSoundVolume() {
        binding.apply {
            settingsSoundVolume.text = "${config.soundVolume}%"
            settingsSoundVolumeHolder.setOnClickListener {
                val feedbackManager = FeedbackManager(this@SettingsActivity)
                SliderPreferenceDialog(
                    activity = this@SettingsActivity,
                    title = getString(R.string.sound_volume),
                    min = 0,
                    max = 100,
                    initialValue = config.soundVolume,
                    step = 5,
                    formatValue = { "$it%" },
                    onProgressChanged = { vol ->
                        config.soundVolume = vol
                        feedbackManager.performSound(MyKeyboard.KEYCODE_SPACE)
                    },
                    onConfirmed = { vol ->
                        config.soundVolume = vol
                        settingsSoundVolume.text = "$vol%"
                    }
                ).show()
            }
        }
    }

    private fun setupLongPressDelay() {
        binding.apply {
            settingsLongPressDelay.text = "${config.longPressDelayMs} ms"
            settingsLongPressDelayHolder.setOnClickListener {
                SliderPreferenceDialog(
                    activity = this@SettingsActivity,
                    title = getString(R.string.long_press_delay),
                    min = 100,
                    max = 700,
                    initialValue = config.longPressDelayMs.toInt(),
                    step = 10,
                    formatValue = { "$it ms" },
                    onConfirmed = { delay ->
                        config.longPressDelayMs = delay.toLong()
                        settingsLongPressDelay.text = "$delay ms"
                    }
                ).show()
            }
        }
    }

    private fun getSoundOnKeypressText(mode: Int): String = getString(
        when (mode) {
            SOUND_SYSTEM -> R.string.sound_system
            SOUND_ALWAYS -> R.string.sound_always
            else -> R.string.sound_none
        }
    )

    private fun setupShowPopupOnKeypress() {
        binding.apply {
            settingsShowPopupOnKeypress.isChecked = config.showPopupOnKeypress
            settingsShowPopupOnKeypressHolder.setOnClickListener {
                settingsShowPopupOnKeypress.toggle()
                config.showPopupOnKeypress = settingsShowPopupOnKeypress.isChecked
            }
        }
    }

    private fun setupPersonaliseTouch() {
        binding.apply {
            settingsPersonaliseTouch.isChecked = config.personaliseTouch
            settingsResetTouchDataHolder.beVisibleIf(config.personaliseTouch)
            settingsPersonaliseTouchHolder.setOnClickListener {
                settingsPersonaliseTouch.toggle()
                config.personaliseTouch = settingsPersonaliseTouch.isChecked
                settingsResetTouchDataHolder.beVisibleIf(config.personaliseTouch)
            }
        }
    }

    private fun setupResetTouchData() {
        binding.settingsResetTouchDataHolder.setOnClickListener {
            ConfirmationDialog(
                activity = this,
                message = getString(R.string.reset_touch_data_confirmation),
                positive = R.string.ok,
                negative = R.string.cancel
            ) {
                TouchPersonalizationManager.getInstance(this).resetAllData {
                    toast(R.string.touch_data_reset_success)
                }
            }
        }
    }

    private fun setupShowTouchDebugOverlay() {
        binding.apply {
            settingsShowTouchDebugOverlay.isChecked = config.showTouchDebugOverlay
            settingsShowTouchDebugOverlayHolder.setOnClickListener {
                settingsShowTouchDebugOverlay.toggle()
                config.showTouchDebugOverlay = settingsShowTouchDebugOverlay.isChecked
            }
        }
    }

    private fun setupShowKeyBorders() {
        binding.apply {
            settingsShowKeyBorders.isChecked = config.showKeyBorders
            settingsShowKeyBordersHolder.setOnClickListener {
                settingsShowKeyBorders.toggle()
                config.showKeyBorders = settingsShowKeyBorders.isChecked
            }
        }
    }

    private fun setupManageKeyboardLanguages() {
        binding.apply {
            settingsManageKeyboardLanguagesHolder.setOnClickListener {
                ManageKeyboardLanguagesDialog(this@SettingsActivity) {
                    settingsKeyboardLanguage.text = getKeyboardLanguageText(config.keyboardLanguage)
                }
            }
        }
    }

    private fun setupKeyboardLanguage() {
        binding.apply {
            settingsKeyboardLanguage.text = getKeyboardLanguageText(config.keyboardLanguage)
            settingsKeyboardLanguageHolder.setOnClickListener {
                val items = getKeyboardLanguagesRadioItems()
                RadioGroupDialog(this@SettingsActivity, items, config.keyboardLanguage) {
                    config.keyboardLanguage = it as Int
                    settingsKeyboardLanguage.text = getKeyboardLanguageText(config.keyboardLanguage)
                }
            }
        }
    }

    private fun setupKeyboardHeightMultiplier() {
        binding.apply {
            settingsKeyboardHeightMultiplier.text =
                getKeyboardHeightPercentageText(config.keyboardHeightPercentage)
            settingsKeyboardHeightMultiplierHolder.setOnClickListener {
                val items = arrayListOf(
                    RadioItem(
                        id = KEYBOARD_HEIGHT_70_PERCENT,
                        title = getKeyboardHeightPercentageText(KEYBOARD_HEIGHT_70_PERCENT)
                    ),
                    RadioItem(
                        id = KEYBOARD_HEIGHT_80_PERCENT,
                        title = getKeyboardHeightPercentageText(KEYBOARD_HEIGHT_80_PERCENT)
                    ),
                    RadioItem(
                        id = KEYBOARD_HEIGHT_90_PERCENT,
                        title = getKeyboardHeightPercentageText(KEYBOARD_HEIGHT_90_PERCENT)
                    ),
                    RadioItem(
                        id = KEYBOARD_HEIGHT_100_PERCENT,
                        title = getKeyboardHeightPercentageText(KEYBOARD_HEIGHT_100_PERCENT)
                    ),
                    RadioItem(
                        id = KEYBOARD_HEIGHT_120_PERCENT,
                        title = getKeyboardHeightPercentageText(KEYBOARD_HEIGHT_120_PERCENT)
                    ),
                    RadioItem(
                        id = KEYBOARD_HEIGHT_140_PERCENT,
                        title = getKeyboardHeightPercentageText(KEYBOARD_HEIGHT_140_PERCENT)
                    ),
                    RadioItem(
                        id = KEYBOARD_HEIGHT_160_PERCENT,
                        title = getKeyboardHeightPercentageText(KEYBOARD_HEIGHT_160_PERCENT)
                    ),
                )

                RadioGroupDialog(this@SettingsActivity, items, config.keyboardHeightPercentage) {
                    config.keyboardHeightPercentage = it as Int
                    settingsKeyboardHeightMultiplier.text =
                        getKeyboardHeightPercentageText(config.keyboardHeightPercentage)
                }
            }
        }
    }

    private fun getKeyboardHeightPercentageText(keyboardHeightPercentage: Int): String =
        "$keyboardHeightPercentage%"

    private fun setupShowClipboardContent() {
        binding.apply {
            settingsShowClipboardContent.isChecked = config.showClipboardContent
            settingsShowClipboardContentHolder.setOnClickListener {
                settingsShowClipboardContent.toggle()
                config.showClipboardContent = settingsShowClipboardContent.isChecked
            }
        }
    }

    private fun setupSentencesCapitalization() {
        binding.apply {
            settingsStartSentencesCapitalized.isChecked = config.enableSentencesCapitalization
            settingsStartSentencesCapitalizedHolder.setOnClickListener {
                settingsStartSentencesCapitalized.toggle()
                config.enableSentencesCapitalization = settingsStartSentencesCapitalized.isChecked
            }
        }
    }

    private fun setupShowEmojiKey() {
        binding.apply {
            settingsShowEmojiKeyHolder.setOnClickListener {
                settingsShowEmojiKey.toggle()
                config.showEmojiKey = settingsShowEmojiKey.isChecked
                if (settingsShowEmojiKey.isChecked) {
                    config.showLanguageSwitchKey = false
                    settingsShowLanguageSwitchKey.isChecked = false
                }
            }
            settingsShowEmojiKey.isChecked = config.showEmojiKey
        }
    }

    private fun setupShowLanguageSwitchKey() {
        binding.apply {
            settingsShowLanguageSwitchKeyHolder.setOnClickListener {
                settingsShowLanguageSwitchKey.toggle()
                config.showLanguageSwitchKey = settingsShowLanguageSwitchKey.isChecked
                if (settingsShowLanguageSwitchKey.isChecked) {
                    config.showEmojiKey = false
                    settingsShowEmojiKey.isChecked = false
                }
            }
            settingsShowLanguageSwitchKey.isChecked = config.showLanguageSwitchKey
        }
    }

    private fun setupShowNumbersRow() {
        binding.apply {
            settingsShowNumbersRow.isChecked = config.showNumbersRow
            settingsShowNumbersRowHolder.setOnClickListener {
                settingsShowNumbersRow.toggle()
                config.showNumbersRow = settingsShowNumbersRow.isChecked
            }
        }
    }

    private fun setupVoiceInputMethod() {
        binding.apply {
            settingsVoiceInputMethodValue.text =
                getCurrentVoiceInputMethod()?.first?.loadLabel(packageManager)
                    ?: getString(R.string.none)
            settingsVoiceInputMethodHolder.setOnClickListener {
                val inputMethods = getVoiceInputMethods()
                if (inputMethods.isEmpty()) {
                    toast(R.string.no_app_found)
                    return@setOnClickListener
                }

                RadioGroupDialog(
                    activity = this@SettingsActivity,
                    items = getVoiceInputRadioItems(),
                    checkedItemId = inputMethods.indexOf(getCurrentVoiceInputMethod(inputMethods))
                ) {
                    config.voiceInputMethod = inputMethods.getOrNull(it as Int)?.first?.id.orEmpty()
                    settingsVoiceInputMethodValue.text =
                        getCurrentVoiceInputMethod(inputMethods)?.first?.loadLabel(packageManager)
                            ?: getString(R.string.none)
                }
            }
        }
    }

    private val heightPresetLabels = arrayOf(
        "Extra Short (70%)",
        "Short (80%)",
        "Medium-Short (90%)",
        "Normal (100%)",
        "Tall (120%)",
        "Very Tall (140%)",
        "Extra Tall (160%)"
    )

    private fun setupKeyboardHeightPreset() {
        binding.apply {
            settingsKeyboardHeightPresetValue.text = heightPresetLabels.getOrNull(config.keyboardHeightPreset) ?: heightPresetLabels[3]
            settingsKeyboardHeightPresetHolder.setOnClickListener {
                val items = heightPresetLabels.mapIndexed { index, label -> RadioItem(index, label) }.toCollection(ArrayList())
                RadioGroupDialog(
                    activity = this@SettingsActivity,
                    items = items,
                    checkedItemId = config.keyboardHeightPreset
                ) {
                    config.keyboardHeightPreset = it as Int
                    settingsKeyboardHeightPresetValue.text = heightPresetLabels[config.keyboardHeightPreset]
                }
            }
        }
    }

    private val oneHandedLabels = arrayOf("Off", "Left-Handed", "Right-Handed", "Floating / Movable")

    private fun setupOneHandedMode() {
        binding.apply {
            settingsOneHandedModeValue.text = oneHandedLabels.getOrNull(config.oneHandedMode) ?: oneHandedLabels[0]
            settingsOneHandedModeHolder.setOnClickListener {
                val items = oneHandedLabels.mapIndexed { index, label -> RadioItem(index, label) }.toCollection(ArrayList())
                RadioGroupDialog(
                    activity = this@SettingsActivity,
                    items = items,
                    checkedItemId = config.oneHandedMode
                ) {
                    config.oneHandedMode = it as Int
                    settingsOneHandedModeValue.text = oneHandedLabels[config.oneHandedMode]
                }
            }
        }
    }

    private fun setupShowKeyBordersToggle() {
        binding.apply {
            settingsShowKeyBorders.isChecked = config.showKeyBorders
            settingsShowKeyBordersHolder.setOnClickListener {
                settingsShowKeyBorders.toggle()
                config.showKeyBorders = settingsShowKeyBorders.isChecked
            }
        }
    }

    private fun setupDebugLogs() {
        binding.settingsDebugLogsHolder.setOnClickListener {
            startActivity(Intent(this, DebugLogActivity::class.java))
        }
    }

    private fun setupPersonalDictionary() {
        binding.settingsPersonalDictionaryHolder.setOnClickListener {
            startActivity(Intent(this, PersonalDictionaryActivity::class.java))
        }
    }
}
