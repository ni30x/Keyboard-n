package org.fossify.keyboard.services

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.view.inputmethod.InputMethodManager
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.icu.text.BreakIterator
import android.icu.util.ULocale
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Bundle
import android.text.InputType.TYPE_CLASS_DATETIME
import android.text.InputType.TYPE_CLASS_NUMBER
import android.text.InputType.TYPE_CLASS_PHONE
import android.text.InputType.TYPE_CLASS_TEXT
import android.text.InputType.TYPE_MASK_CLASS
import android.text.InputType.TYPE_MASK_VARIATION
import android.text.InputType.TYPE_NULL
import android.text.TextUtils
import android.util.Log
import android.util.Size
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.EditorInfo.IME_ACTION_NONE
import android.view.inputmethod.EditorInfo.IME_FLAG_NO_ENTER_ACTION
import android.view.inputmethod.EditorInfo.IME_MASK_ACTION
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodSubtype
import android.widget.inline.InlinePresentationSpec
import androidx.annotation.RequiresApi
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.common.ImageViewStyle
import androidx.autofill.inline.common.TextViewStyle
import androidx.autofill.inline.common.ViewStyle
import androidx.autofill.inline.v1.InlineSuggestionUi
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat.Type
import androidx.core.view.updatePadding
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.getSharedPrefs
import org.fossify.commons.extensions.setSystemBarsAppearance
import org.fossify.keyboard.helpers.IhDebug
import org.fossify.keyboard.helpers.InputHistoryRecordingEngine
import org.fossify.keyboard.helpers.PredictiveTouchTargetEngine
import org.fossify.keyboard.helpers.SuggestionEngine
import org.fossify.commons.helpers.ACCENT_COLOR
import org.fossify.commons.helpers.BACKGROUND_COLOR
import org.fossify.commons.helpers.CUSTOM_ACCENT_COLOR
import org.fossify.commons.helpers.CUSTOM_BACKGROUND_COLOR
import org.fossify.commons.helpers.CUSTOM_PRIMARY_COLOR
import org.fossify.commons.helpers.CUSTOM_TEXT_COLOR
import org.fossify.commons.helpers.IS_GLOBAL_THEME_ENABLED
import org.fossify.commons.helpers.IS_SYSTEM_THEME_ENABLED
import org.fossify.commons.helpers.PRIMARY_COLOR
import org.fossify.commons.helpers.TEXT_COLOR
import org.fossify.commons.helpers.isNougatPlus
import org.fossify.commons.helpers.isPiePlus
import org.fossify.keyboard.R
import org.fossify.keyboard.activities.SettingsActivity
import org.fossify.keyboard.databinding.KeyboardViewKeyboardBinding
import org.fossify.keyboard.extensions.config
import org.fossify.keyboard.extensions.getKeyboardBackgroundColor
import org.fossify.keyboard.extensions.getKeyboardLanguageText
import org.fossify.keyboard.extensions.getSelectedLanguagesSorted
import org.fossify.keyboard.extensions.getStrokeColor
import org.fossify.keyboard.extensions.safeStorageContext
import org.fossify.keyboard.helpers.HEIGHT_PERCENTAGE
import org.fossify.keyboard.helpers.KEYBOARD_LANGUAGE
import org.fossify.keyboard.helpers.LANGUAGE_ARABIC
import org.fossify.keyboard.helpers.LANGUAGE_BELARUSIAN_CYRL
import org.fossify.keyboard.helpers.LANGUAGE_BELARUSIAN_LATN
import org.fossify.keyboard.helpers.LANGUAGE_BENGALI
import org.fossify.keyboard.helpers.LANGUAGE_BULGARIAN
import org.fossify.keyboard.helpers.LANGUAGE_CENTRAL_KURDISH
import org.fossify.keyboard.helpers.LANGUAGE_CHUVASH
import org.fossify.keyboard.helpers.LANGUAGE_CZECH_QWERTY
import org.fossify.keyboard.helpers.LANGUAGE_CZECH_QWERTZ
import org.fossify.keyboard.helpers.LANGUAGE_DANISH
import org.fossify.keyboard.helpers.LANGUAGE_DUTCH
import org.fossify.keyboard.helpers.LANGUAGE_ENGLISH_ASSET
import org.fossify.keyboard.helpers.LANGUAGE_ENGLISH_COLEMAK
import org.fossify.keyboard.helpers.LANGUAGE_ENGLISH_COLEMAKDH
import org.fossify.keyboard.helpers.LANGUAGE_ENGLISH_DVORAK
import org.fossify.keyboard.helpers.LANGUAGE_ENGLISH_NIRO
import org.fossify.keyboard.helpers.LANGUAGE_ENGLISH_QWERTZ
import org.fossify.keyboard.helpers.LANGUAGE_ENGLISH_SOUL
import org.fossify.keyboard.helpers.LANGUAGE_ENGLISH_WORKMAN
import org.fossify.keyboard.helpers.LANGUAGE_ESPERANTO
import org.fossify.keyboard.helpers.LANGUAGE_FRENCH_AZERTY
import org.fossify.keyboard.helpers.LANGUAGE_FRENCH_BEPO
import org.fossify.keyboard.helpers.LANGUAGE_GERMAN
import org.fossify.keyboard.helpers.LANGUAGE_GERMAN_QWERTZ
import org.fossify.keyboard.helpers.LANGUAGE_GREEK
import org.fossify.keyboard.helpers.LANGUAGE_HEBREW
import org.fossify.keyboard.helpers.LANGUAGE_ITALIAN
import org.fossify.keyboard.helpers.LANGUAGE_KABYLE_AZERTY
import org.fossify.keyboard.helpers.LANGUAGE_LATVIAN
import org.fossify.keyboard.helpers.LANGUAGE_LITHUANIAN
import org.fossify.keyboard.helpers.LANGUAGE_NORWEGIAN
import org.fossify.keyboard.helpers.LANGUAGE_POLISH
import org.fossify.keyboard.helpers.LANGUAGE_PORTUGUESE
import org.fossify.keyboard.helpers.LANGUAGE_PORTUGUESE_HCESAR
import org.fossify.keyboard.helpers.LANGUAGE_ROMANIAN
import org.fossify.keyboard.helpers.LANGUAGE_RUSSIAN
import org.fossify.keyboard.helpers.LANGUAGE_SLOVENIAN
import org.fossify.keyboard.helpers.LANGUAGE_SPANISH
import org.fossify.keyboard.helpers.LANGUAGE_SWEDISH
import org.fossify.keyboard.helpers.LANGUAGE_TURKISH
import org.fossify.keyboard.helpers.LANGUAGE_TURKISH_Q
import org.fossify.keyboard.helpers.LANGUAGE_UKRAINIAN
import org.fossify.keyboard.helpers.AUTO_SYNC_MATERIAL_YOU
import org.fossify.keyboard.helpers.Material3Palette
import org.fossify.keyboard.helpers.MyKeyboard
import org.fossify.keyboard.helpers.SHOW_KEY_BORDERS
import org.fossify.keyboard.helpers.SHOW_NUMBERS_ROW
import org.fossify.keyboard.helpers.ShiftState
import org.fossify.keyboard.helpers.VOICE_INPUT_METHOD
import org.fossify.keyboard.helpers.LANGUAGE_VIETNAMESE_TELEX
import org.fossify.keyboard.helpers.cachedVNTelexData
import org.fossify.keyboard.interfaces.OnKeyboardActionListener
import org.fossify.keyboard.views.MyKeyboardView
import java.io.ByteArrayOutputStream
import java.util.Locale


// based on https://www.androidauthority.com/lets-build-custom-keyboard-android-832362/
class SimpleKeyboardIME : InputMethodService(), OnKeyboardActionListener, SharedPreferences.OnSharedPreferenceChangeListener {
    companion object {
        // How quickly do we have to double tap shift to enable permanent caps lock
        private var SHIFT_PERM_TOGGLE_SPEED = 500

        // Keyboard modes
        const val KEYBOARD_LETTERS = 0
        const val KEYBOARD_SYMBOLS = 1
        const val KEYBOARD_SYMBOLS_SHIFT = 2
        const val KEYBOARD_NUMBERS = 3
        const val KEYBOARD_PHONE = 4
        const val KEYBOARD_SYMBOLS_ALT = 5
    }

    private var keyboard: MyKeyboard? = null
    private var keyboardView: MyKeyboardView? = null
    private var lastShiftPressTS = 0L
    private var keyboardMode = KEYBOARD_LETTERS
    private var inputTypeClass = TYPE_CLASS_TEXT
    private var inputTypeClassVariation = TYPE_CLASS_TEXT
    private var enterKeyType = IME_ACTION_NONE
    private var switchToLetters = false
    private var breakIterator: BreakIterator? = null
    private val composingWord = StringBuilder()
    private var lastCommittedWord = ""
    private var currentSuggestions = listOf<String>()
    private var isQuickActionsExpanded = false

    data class AutocorrectRecord(val typedWord: String, val correctedWord: String, val timestamp: Long)
    private var lastAutocorrect: AutocorrectRecord? = null

    private lateinit var binding: KeyboardViewKeyboardBinding

    override fun onInitializeInterface() {
        super.onInitializeInterface()
        safeStorageContext.getSharedPrefs().registerOnSharedPreferenceChangeListener(this)
        SuggestionEngine.init(this)
    }

    private var cachedInputView: View? = null

    override fun onCreateInputView(): View {
        if (cachedInputView != null && ::binding.isInitialized) {
            return cachedInputView!!
        }
        binding = KeyboardViewKeyboardBinding.inflate(layoutInflater)
        binding.keyboardHolder.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        if (keyboard == null) {
            keyboard = createNewKeyboard()
        }
        keyboardView = binding.keyboardView.apply {
            setKeyboardHolder(binding)
            setKeyboard(keyboard!!)
            if (currentInputEditorInfo != null) {
                setEditorInfo(currentInputEditorInfo)
            }
            setupEdgeToEdge()
            mOnKeyboardActionListener = this@SimpleKeyboardIME
        }
        setupSuggestions()
        cachedInputView = binding.root
        return cachedInputView!!
    }

    override fun onStartInputView(editorInfo: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(editorInfo, restarting)
        updateBackgroundColors()
        keyboardView?.setupKeyboard()
        binding.keyboardHolder.post {
            ViewCompat.requestApplyInsets(binding.keyboardHolder)
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::binding.isInitialized) {
            updateBackgroundColors()
            keyboardView?.setupKeyboard()
        }
    }

    override fun onPress(primaryCode: Int) {
        keyboardView?.hideClipboardPasteChip()
    }

    override fun isInputFieldEmpty(): Boolean {
        val ic = currentInputConnection ?: return true
        val before = ic.getTextBeforeCursor(1, 0)
        val after = ic.getTextAfterCursor(1, 0)
        return before.isNullOrEmpty() && after.isNullOrEmpty()
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        val pkg = attribute?.packageName ?: ""
        val inputType = attribute?.inputType ?: TYPE_CLASS_TEXT
        Log.d("IME_DEBUG", "onStartInput pkg=$pkg inputType=$inputType restarting=$restarting")
        SuggestionEngine.onStartSession(pkg, inputType)
        val ic = currentInputConnection
        val initialText = if (ic != null) {
            val before = ic.getTextBeforeCursor(2000, 0)?.toString() ?: ""
            val after = ic.getTextAfterCursor(2000, 0)?.toString() ?: ""
            before + after
        } else null
        InputHistoryRecordingEngine.onStartInput(this, attribute, initialText)
        composingWord.clear()
        lastCommittedWord = ""
        lastAutocorrect = null
        isQuickActionsExpanded = false
        hideSuggestions()
        updateToolbarState()
        inputTypeClass = inputType and TYPE_MASK_CLASS
        inputTypeClassVariation = inputType and TYPE_MASK_VARIATION
        enterKeyType = (attribute?.imeOptions ?: 0) and (IME_MASK_ACTION or IME_FLAG_NO_ENTER_ACTION)
        keyboard = createNewKeyboard()
        keyboardView?.setKeyboard(keyboard!!)
        if (attribute != null) {
            keyboardView?.setEditorInfo(attribute)
        }
        updateToolbarVisibility()
        if (isNougatPlus()) {
            breakIterator = BreakIterator.getCharacterInstance(ULocale.getDefault())
        }
        updateShiftKeyState()
    }

    private fun updateShiftKeyState() {
        val currentKeyboard = keyboard ?: return
        if (currentKeyboard.mShiftState == ShiftState.ON_PERMANENT) {
            return
        }

        val editorInfo = currentInputEditorInfo
        val targetShiftState = if (config.enableSentencesCapitalization && editorInfo != null && editorInfo.inputType != TYPE_NULL) {
            if (currentInputConnection.getCursorCapsMode(editorInfo.inputType) != 0) {
                ShiftState.ON_ONE_CHAR
            } else {
                ShiftState.OFF
            }
        } else {
            ShiftState.OFF
        }

        if (currentKeyboard.mShiftState != targetShiftState) {
            currentKeyboard.setShifted(targetShiftState)
            keyboardView?.invalidateAllKeys()
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onCreateInlineSuggestionsRequest(uiExtras: Bundle): InlineSuggestionsRequest {
        val maxWidth = resources.getDimensionPixelSize(R.dimen.suggestion_max_width)

        return InlineSuggestionsRequest.Builder(
            listOf(
                InlinePresentationSpec.Builder(
                    Size(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                    Size(maxWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
                ).setStyle(buildSuggestionTextStyle()).build()
            )
        ).setMaxSuggestionCount(InlineSuggestionsRequest.SUGGESTION_COUNT_UNLIMITED)
            .build()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean {
        keyboardView?.clearClipboardViews()

        response.inlineSuggestions.forEach {
            it.inflate(this, Size(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT), this.mainExecutor) { view ->
                // If inflation fails for whatever reason, passed view will be null
                if (view != null) {
                    keyboardView?.addToClipboardViews(view, addToFront = it.info.isPinned)
                }
            }
        }

        return true
    }

    override fun onKey(code: Int) {
        val inputConnection = currentInputConnection
        if (keyboard == null || inputConnection == null) {
            return
        }

        if (code != MyKeyboard.KEYCODE_SHIFT) {
            lastShiftPressTS = 0
            collapseQuickActions()
        }

        when (code) {
            MyKeyboard.KEYCODE_DELETE -> {
                keyboardView?.hideClipboardPasteChip()

                val autocorrect = lastAutocorrect
                if (autocorrect != null && System.currentTimeMillis() - autocorrect.timestamp <= 2500L) {
                    lastAutocorrect = null
                    val toDelete = autocorrect.correctedWord.length + 1
                    inputConnection.deleteSurroundingText(toDelete, 0)
                    inputConnection.commitText(autocorrect.typedWord, 1)
                    InputHistoryRecordingEngine.onReplaceBeforeCursor(this, toDelete, autocorrect.typedWord)
                    SuggestionEngine.recordRejection(autocorrect.typedWord, autocorrect.correctedWord)
                    composingWord.clear()
                    composingWord.append(autocorrect.typedWord)
                    SuggestionEngine.getSuggestionsAsync(autocorrect.typedWord, lastCommittedWord.ifEmpty { null }) { suggestions ->
                        binding.root.post { showSuggestions(suggestions) }
                    }
                    return
                }
                lastAutocorrect = null

                val selectedText = inputConnection.getSelectedText(0)
                if (TextUtils.isEmpty(selectedText)) {
                    val count = getCountToDelete(inputConnection)
                    // Allowed repair 3: Wire onWordDeleted for multi-character deletion (e.g. compound emoji/grapheme)
                    if (count > 1) {
                        InputHistoryRecordingEngine.onWordDeleted(this, count)
                    } else {
                        InputHistoryRecordingEngine.onBackspace(this)
                    }
                    inputConnection.deleteSurroundingText(count, 0)
                } else {
                    IhDebug.logBypass("selected_text_delete", selectedText.length)
                    InputHistoryRecordingEngine.onSelectionDeleted(this, selectedText.length)
                    inputConnection.commitText("", 1)
                }

                if (composingWord.isNotEmpty()) {
                    composingWord.deleteCharAt(composingWord.length - 1)
                    if (composingWord.isNotEmpty()) {
                        val query = composingWord.toString()
                        SuggestionEngine.getSuggestionsAsync(query, lastCommittedWord.ifEmpty { null }) { suggestions ->
                            binding.root.post { showSuggestions(suggestions) }
                        }
                    } else {
                        hideSuggestions()
                    }
                } else {
                    val textBefore = inputConnection.getTextBeforeCursor(30, 0)?.toString()?.trim()
                    val lastWord = textBefore?.split("\\s+".toRegex())?.lastOrNull() ?: ""
                    if (lastWord.isNotEmpty()) {
                        SuggestionEngine.getNextWordPredictionsAsync(lastWord, null) { nextWords ->
                            binding.root.post {
                                if (nextWords.isNotEmpty()) {
                                    showSuggestions(nextWords)
                                } else {
                                    hideSuggestions()
                                }
                            }
                        }
                    } else {
                        hideSuggestions()
                    }
                }
            }

            MyKeyboard.KEYCODE_SHIFT -> {
                if (keyboardMode == KEYBOARD_LETTERS) {
                    when {
                        keyboard!!.mShiftState == ShiftState.ON_PERMANENT -> keyboard!!.mShiftState = ShiftState.OFF
                        System.currentTimeMillis() - lastShiftPressTS < SHIFT_PERM_TOGGLE_SPEED -> keyboard!!.mShiftState = ShiftState.ON_PERMANENT
                        keyboard!!.mShiftState == ShiftState.ON_ONE_CHAR -> keyboard!!.mShiftState = ShiftState.OFF
                        keyboard!!.mShiftState == ShiftState.OFF -> keyboard!!.mShiftState = ShiftState.ON_ONE_CHAR
                    }

                    lastShiftPressTS = System.currentTimeMillis()
                } else {
                    val keyboardXml = if (keyboardMode == KEYBOARD_SYMBOLS) {
                        keyboardMode = KEYBOARD_SYMBOLS_SHIFT
                        R.xml.keys_symbols_shift
                    } else {
                        keyboardMode = KEYBOARD_SYMBOLS
                        R.xml.keys_symbols
                    }
                    keyboard = constructKeyboard(keyboardXml, enterKeyType)
                    keyboardView!!.setKeyboard(keyboard!!)
                }
                keyboardView!!.invalidateAllKeys()
            }

            MyKeyboard.KEYCODE_ENTER -> {
                keyboardView?.hideClipboardPasteChip()
                InputHistoryRecordingEngine.onActionOrEnter(this, inputConnection, currentInputEditorInfo)
                if (composingWord.isNotEmpty()) {
                    SuggestionEngine.learnWord(this, composingWord.toString())
                    SuggestionEngine.recordWordTransition(lastCommittedWord, composingWord.toString())
                    lastCommittedWord = composingWord.toString()
                    composingWord.clear()
                    hideSuggestions()
                } else {
                    lastCommittedWord = ""
                }

                val imeOptionsActionId = getImeOptionsActionId()
                if (imeOptionsActionId != IME_ACTION_NONE) {
                    inputConnection.performEditorAction(imeOptionsActionId)
                } else {
                    inputConnection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                    inputConnection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
                }
            }

            MyKeyboard.KEYCODE_SYMBOLS_MODE_CHANGE -> {
                if (composingWord.isNotEmpty()) {
                    SuggestionEngine.learnWord(this, composingWord.toString())
                    composingWord.clear()
                    hideSuggestions()
                }

                val keyboardXML = if (keyboardMode == KEYBOARD_SYMBOLS || keyboardMode == KEYBOARD_SYMBOLS_SHIFT) {
                    keyboardMode = KEYBOARD_NUMBERS
                    R.xml.keys_numbers
                } else {
                    keyboardMode = KEYBOARD_SYMBOLS
                    R.xml.keys_symbols
                }

                keyboard = constructKeyboard(keyboardXML, enterKeyType)
                keyboardView!!.setKeyboard(keyboard!!)
                updateToolbarVisibility()
            }

            MyKeyboard.KEYCODE_MODE_CHANGE -> {
                if (composingWord.isNotEmpty()) {
                    SuggestionEngine.learnWord(this, composingWord.toString())
                    composingWord.clear()
                    hideSuggestions()
                }

                val keyboardXml = if (keyboardMode == KEYBOARD_LETTERS) {
                    keyboardMode = KEYBOARD_SYMBOLS
                    R.xml.keys_symbols
                } else {
                    keyboardMode = KEYBOARD_LETTERS
                    getKeyboardLayoutXML()
                }

                keyboard = constructKeyboard(keyboardXml, enterKeyType)
                keyboardView!!.setKeyboard(keyboard!!)
                updateToolbarVisibility()
            }

            MyKeyboard.KEYCODE_EMOJI_OR_LANGUAGE -> {
                if (composingWord.isNotEmpty()) {
                    SuggestionEngine.learnWord(this, composingWord.toString())
                    composingWord.clear()
                    hideSuggestions()
                }

                if (config.showEmojiKey) {
                    keyboardView?.openEmojiPalette()
                } else if (config.showLanguageSwitchKey) {
                    val sortedLanguages = getSelectedLanguagesSorted()
                    if (sortedLanguages.size > 1) {
                        val currentIndex = sortedLanguages.indexOf(config.keyboardLanguage)
                        val nextIndex = (currentIndex + 1) % sortedLanguages.size
                        config.keyboardLanguage = sortedLanguages[nextIndex]
                        reloadKeyboard()
                    }
                }
            }

            MyKeyboard.KEYCODE_POPUP_EMOJI -> {
                if (composingWord.isNotEmpty()) {
                    SuggestionEngine.learnWord(this, composingWord.toString())
                    composingWord.clear()
                    hideSuggestions()
                }
                keyboardView?.openEmojiPalette()
            }

            MyKeyboard.KEYCODE_POPUP_SETTINGS -> {
                if (composingWord.isNotEmpty()) {
                    SuggestionEngine.learnWord(this, composingWord.toString())
                    composingWord.clear()
                    hideSuggestions()
                }
                Intent(this, SettingsActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(this)
                }
            }

            else -> {
                var codeChar = code.toChar()

                if (Character.isLetter(codeChar) && keyboard!!.mShiftState > ShiftState.OFF) {
                    if (baseContext.config.keyboardLanguage == LANGUAGE_TURKISH_Q) {
                        codeChar = codeChar.toString().uppercase(Locale.forLanguageTag("tr")).single()
                    } else {
                        codeChar = Character.toUpperCase(codeChar)
                    }
                }

                // If the keyboard is set to symbols and the user presses space, switch back to the letters keyboard.
                if (keyboardMode != KEYBOARD_LETTERS && inputTypeClass == TYPE_CLASS_TEXT && code == MyKeyboard.KEYCODE_SPACE) {
                    Log.d("IME_DEBUG", "Space pressed in symbol mode")
                    keyboardView?.hideClipboardPasteChip()
                    if (composingWord.isNotEmpty()) {
                        handleSpaceAutoCorrection(inputConnection)
                    } else {
                        InputHistoryRecordingEngine.onKeystroke(' ')
                        lastAutocorrect = null
                        inputConnection.commitText(codeChar.toString(), 1)
                        val textBefore = inputConnection.getTextBeforeCursor(30, 0)?.toString()?.trim()
                        val lastWord = textBefore?.split("\\s+".toRegex())?.lastOrNull() ?: ""
                        if (lastWord.isNotEmpty()) {
                            SuggestionEngine.getNextWordPredictionsAsync(lastWord, null) { nextWords ->
                                binding.root.post {
                                    if (nextWords.isNotEmpty()) {
                                        showSuggestions(nextWords)
                                    } else {
                                        hideSuggestions()
                                    }
                                }
                            }
                        }
                    }
                    switchToLetters = keyboardMode != KEYBOARD_SYMBOLS_ALT
                    updateShiftKeyState()
                    return
                } else {
                    if (code == MyKeyboard.KEYCODE_SPACE) {
                        Log.d("IME_DEBUG", "Space pressed, composingWord='$composingWord'")
                        keyboardView?.hideClipboardPasteChip()
                        if (composingWord.isNotEmpty()) {
                            handleSpaceAutoCorrection(inputConnection)
                        } else {
                            lastAutocorrect = null
                            if (baseContext.config.doubleSpacePeriod) {
                                val textBefore = inputConnection.getTextBeforeCursor(2, 0)?.toString()
                                if (textBefore == "  ") {
                                    inputConnection.deleteSurroundingText(2, 0)
                                    inputConnection.commitText(". ", 1)
                                    InputHistoryRecordingEngine.onReplaceBeforeCursor(this, 1, ". ")
                                    lastCommittedWord = ""
                                    hideSuggestions()
                                    updateShiftKeyState()
                                    return
                                }
                            }

                            InputHistoryRecordingEngine.onKeystroke(' ')
                            inputConnection.commitText(codeChar.toString(), 1)
                            val textBefore = inputConnection.getTextBeforeCursor(30, 0)?.toString()?.trim()
                            val lastWord = textBefore?.split("\\s+".toRegex())?.lastOrNull() ?: ""
                            if (lastWord.isNotEmpty()) {
                                SuggestionEngine.getNextWordPredictionsAsync(lastWord, null) { nextWords ->
                                    binding.root.post {
                                        if (nextWords.isNotEmpty()) {
                                            showSuggestions(nextWords)
                                        } else {
                                            hideSuggestions()
                                        }
                                    }
                                }
                            }
                        }
                        updateShiftKeyState()
                        return
                    } else if (Character.isLetter(codeChar) || (composingWord.isNotEmpty() && (codeChar == '\'' || codeChar == '-'))) {
                        lastAutocorrect = null
                        keyboardView?.hideClipboardPasteChip()
                        collapseQuickActions()
                        composingWord.append(codeChar)
                        val query = composingWord.toString()
                        Log.d("IME_DEBUG", "Letter typed: '$codeChar', new composingWord='$query'")
                        SuggestionEngine.getSuggestionsAsync(query, lastCommittedWord.ifEmpty { null }) { suggestions ->
                            Log.d("IME_DEBUG", "Suggestions received for '$query': $suggestions")
                            binding.root.post { showSuggestions(suggestions) }
                        }
                    } else {
                        lastAutocorrect = null
                        keyboardView?.hideClipboardPasteChip()
                        if (composingWord.isNotEmpty()) {
                            SuggestionEngine.learnWord(this, composingWord.toString())
                            SuggestionEngine.recordWordTransition(lastCommittedWord, composingWord.toString())
                            lastCommittedWord = composingWord.toString()
                            composingWord.clear()
                            hideSuggestions()
                        }
                    }

                    val currentLang = baseContext.config.keyboardLanguage
                    val isVNTelexActive = currentLang == LANGUAGE_VIETNAMESE_TELEX && cachedVNTelexData.isNotEmpty()

                    if (isVNTelexActive) {
                        val originalText = inputConnection.getExtractedText(ExtractedTextRequest(), 0)?.text
                        if (!originalText.isNullOrEmpty()) {
                            val fullText = originalText.toString() + codeChar.toString()
                            val lastIndexEmpty = if (fullText.contains(" ")) {
                                fullText.lastIndexOf(" ")
                            } else 0
                            if (lastIndexEmpty >= 0) {
                                val word = fullText.subSequence(lastIndexEmpty, fullText.length).trim().toString()
                                val wordChars = word.toCharArray()
                                val predictWord = StringBuilder()
                                for (char in wordChars.size - 1 downTo 0) {
                                    predictWord.append(wordChars[char])
                                    val shouldChangeText = predictWord.reverse().toString()
                                    if (cachedVNTelexData.containsKey(shouldChangeText)) {
                                        val replacement = cachedVNTelexData[shouldChangeText] ?: ""
                                        inputConnection.setComposingRegion(fullText.length - shouldChangeText.length, fullText.length)
                                        inputConnection.setComposingText(replacement, fullText.length)
                                        inputConnection.setComposingRegion(fullText.length, fullText.length)
                                        InputHistoryRecordingEngine.onReplaceBeforeCursor(this, shouldChangeText.length - 1, replacement)
                                        return
                                    }
                                }
                            }
                        }
                    }

                    val selected = inputConnection.getSelectedText(0)
                    if (!TextUtils.isEmpty(selected)) {
                        inputConnection.commitText(codeChar.toString(), 1)
                        InputHistoryRecordingEngine.onSelectionReplaced(this, selected.length, codeChar.toString())
                    } else {
                        inputConnection.commitText(codeChar.toString(), 1)
                        InputHistoryRecordingEngine.onKeystroke(codeChar)
                    }
                    updateShiftKeyState()
                }
            }
        }
    }

    private fun getCountToDelete(inputConnection: InputConnection): Int {
        if (breakIterator == null || !isNougatPlus()) {
            return 1
        }

        val prevText = inputConnection.getTextBeforeCursor(8, 0)


        if (!TextUtils.isEmpty(prevText)) {
            return breakIterator?.let {
                it.setText(prevText.toString())
                val end = it.last()
                val start = it.previous()
                (end - (if (start == BreakIterator.DONE) 0 else start)).coerceIn(0, prevText?.length)
            } ?: 1
        }

        return 1
    }

    override fun onActionUp() {
        if (switchToLetters) {
            // TODO: Change keyboardMode to enum class
            keyboardMode = KEYBOARD_LETTERS

            keyboard = constructKeyboard(getKeyboardLayoutXML(), enterKeyType)

            val editorInfo = currentInputEditorInfo
            if (editorInfo != null && editorInfo.inputType != TYPE_NULL && keyboard?.mShiftState != ShiftState.ON_PERMANENT) {
                if (currentInputConnection.getCursorCapsMode(editorInfo.inputType) != 0) {
                    keyboard?.setShifted(ShiftState.ON_ONE_CHAR)
                }
            }

            keyboardView!!.setKeyboard(keyboard!!)
            switchToLetters = false
        }
    }

    override fun moveCursorLeft() {
        moveCursor(false)
    }

    override fun moveCursorRight() {
        moveCursor(true)
    }

    override fun onText(text: String) {
        InputHistoryRecordingEngine.onKeystroke(text)
        currentInputConnection?.commitText(text, 1)
    }

    override fun reloadKeyboard() {
        val keyboard = createNewKeyboard()
        this.keyboard = keyboard
        keyboardView?.setKeyboard(keyboard)
        updateToolbarVisibility()
    }

    private fun updateToolbarVisibility() {
        if (!::binding.isInitialized) return
        if (keyboardMode == KEYBOARD_NUMBERS || keyboardMode == KEYBOARD_PHONE) {
            binding.toolbarHolder.beGone()
        } else {
            binding.toolbarHolder.beVisible()
        }
    }

    override fun changeInputMethod(id: String, subtype: InputMethodSubtype) {
        if (isPiePlus()) {
            switchInputMethod(id, subtype)
        } else {
            switchInputMethod(id)
        }
    }

    private fun createNewKeyboard(): MyKeyboard {
        val keyboardXml = when (inputTypeClass) {
            TYPE_CLASS_NUMBER -> {
                keyboardMode = KEYBOARD_NUMBERS
                R.xml.keys_numbers
            }

            TYPE_CLASS_PHONE -> {
                keyboardMode = KEYBOARD_PHONE
                R.xml.keys_phone
            }

            TYPE_CLASS_DATETIME -> {
                keyboardMode = KEYBOARD_SYMBOLS
                R.xml.keys_symbols
            }

            else -> {
                keyboardMode = KEYBOARD_LETTERS
                getKeyboardLayoutXML()
            }
        }
        return constructKeyboard(keyboardXml, enterKeyType)
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        IhDebug.logSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (newSelStart == newSelEnd) {
            keyboardView?.closeClipboardManager()
        }
        updateShiftKeyState()
        InputHistoryRecordingEngine.onCursorMoved(newSelStart)
        InputHistoryRecordingEngine.onFieldOrSelectionUpdated(this, currentInputConnection)

        if (composingWord.isNotEmpty() && (newSelStart != newSelEnd || oldSelStart != newSelStart)) {
            val textBefore = currentInputConnection?.getTextBeforeCursor(composingWord.length, 0)?.toString()
            if (textBefore != composingWord.toString()) {
                composingWord.clear()
                hideSuggestions()
            }
        }
    }

    override fun onUpdateCursorAnchorInfo(cursorAnchorInfo: CursorAnchorInfo?) {
        super.onUpdateCursorAnchorInfo(cursorAnchorInfo)
        updateShiftKeyState()
    }

    private fun moveCursor(moveRight: Boolean) {
        val inputConnection = currentInputConnection
        val extractedText = inputConnection.getExtractedText(ExtractedTextRequest(), 0) ?: return
        val text = extractedText.text ?: return
        val oldPos = extractedText.selectionStart
        val newPos = if (moveRight) {
            oldPos + 1
        } else {
            oldPos - 1
        }.coerceIn(0, text.length)

        if (newPos != oldPos) {
            inputConnection?.setSelection(newPos, newPos)
            keyboardView?.performHapticHandleMove()
        }
    }

    private fun getImeOptionsActionId(): Int {
        return if (currentInputEditorInfo.imeOptions and IME_FLAG_NO_ENTER_ACTION != 0) {
            IME_ACTION_NONE
        } else {
            currentInputEditorInfo.imeOptions and IME_MASK_ACTION
        }
    }

    private fun getKeyboardLayoutXML(): Int {
        return when (baseContext.config.keyboardLanguage) {
            LANGUAGE_ARABIC -> R.xml.keys_letters_arabic
            LANGUAGE_BELARUSIAN_CYRL -> R.xml.keys_letters_belarusian_cyrl
            LANGUAGE_BELARUSIAN_LATN -> R.xml.keys_letters_belarusian_latn
            LANGUAGE_BENGALI -> R.xml.keys_letters_bengali
            LANGUAGE_BULGARIAN -> R.xml.keys_letters_bulgarian
            LANGUAGE_CENTRAL_KURDISH -> R.xml.keys_letters_central_kurdish
            LANGUAGE_CHUVASH -> R.xml.keys_letters_chuvash
            LANGUAGE_CZECH_QWERTY -> R.xml.keys_letters_czech_qwerty
            LANGUAGE_CZECH_QWERTZ -> R.xml.keys_letters_czech_qwertz
            LANGUAGE_DANISH -> R.xml.keys_letters_danish
            LANGUAGE_DUTCH -> R.xml.keys_letters_dutch
            LANGUAGE_ENGLISH_ASSET -> R.xml.keys_letters_english_asset
            LANGUAGE_ENGLISH_COLEMAK -> R.xml.keys_letters_english_colemak
            LANGUAGE_ENGLISH_COLEMAKDH -> R.xml.keys_letters_english_colemakdh
            LANGUAGE_ENGLISH_DVORAK -> R.xml.keys_letters_english_dvorak
            LANGUAGE_ENGLISH_NIRO -> R.xml.keys_letters_english_niro
            LANGUAGE_ENGLISH_QWERTZ -> R.xml.keys_letters_english_qwertz
            LANGUAGE_ENGLISH_SOUL -> R.xml.keys_letters_english_soul
            LANGUAGE_ENGLISH_WORKMAN -> R.xml.keys_letters_english_workman
            LANGUAGE_ESPERANTO -> R.xml.keys_letters_esperanto
            LANGUAGE_FRENCH_AZERTY -> R.xml.keys_letters_french_azerty
            LANGUAGE_FRENCH_BEPO -> R.xml.keys_letters_french_bepo
            LANGUAGE_GERMAN -> R.xml.keys_letters_german
            LANGUAGE_GERMAN_QWERTZ -> R.xml.keys_letters_german_qwertz
            LANGUAGE_GREEK -> R.xml.keys_letters_greek
            LANGUAGE_HEBREW -> R.xml.keys_letters_hebrew
            LANGUAGE_ITALIAN -> R.xml.keys_letters_italian
            LANGUAGE_KABYLE_AZERTY -> R.xml.keys_letters_kabyle_azerty
            LANGUAGE_LATVIAN -> R.xml.keys_letters_latvian
            LANGUAGE_LITHUANIAN -> R.xml.keys_letters_lithuanian
            LANGUAGE_NORWEGIAN -> R.xml.keys_letters_norwegian
            LANGUAGE_POLISH -> R.xml.keys_letters_polish
            LANGUAGE_PORTUGUESE -> R.xml.keys_letters_portuguese
            LANGUAGE_PORTUGUESE_HCESAR -> R.xml.keys_letters_portuguese_hcesar
            LANGUAGE_ROMANIAN -> R.xml.keys_letters_romanian
            LANGUAGE_RUSSIAN -> R.xml.keys_letters_russian
            LANGUAGE_SLOVENIAN -> R.xml.keys_letters_slovenian
            LANGUAGE_SWEDISH -> R.xml.keys_letters_swedish
            LANGUAGE_SPANISH -> R.xml.keys_letters_spanish_qwerty
            LANGUAGE_TURKISH -> R.xml.keys_letters_turkish
            LANGUAGE_TURKISH_Q -> R.xml.keys_letters_turkish_q
            LANGUAGE_UKRAINIAN -> R.xml.keys_letters_ukrainian
            else -> R.xml.keys_letters_english_qwerty
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    @SuppressLint("RestrictedApi", "UseCompatLoadingForDrawables")
    private fun buildSuggestionTextStyle(): Bundle {
        val stylesBuilder = UiVersions.newStylesBuilder()

        val verticalPadding = resources.getDimensionPixelSize(R.dimen.small_margin)
        val horizontalPadding = resources.getDimensionPixelSize(R.dimen.activity_margin)

        val textSize = resources.getDimension(R.dimen.label_text_size) / resources.displayMetrics.scaledDensity

        val rippleBg = resources.getDrawable(R.drawable.clipboard_background, theme) as RippleDrawable
        val layerDrawable = rippleBg.findDrawableByLayerId(R.id.clipboard_background_holder) as LayerDrawable
        layerDrawable.findDrawableByLayerId(R.id.clipboard_background_stroke).applyColorFilter(getStrokeColor())
        layerDrawable.findDrawableByLayerId(R.id.clipboard_background_shape).applyColorFilter(getProperBackgroundColor())

        val maxWidth = resources.getDimensionPixelSize(R.dimen.suggestion_max_width)
        val height = resources.getDimensionPixelSize(R.dimen.label_text_size) + verticalPadding * 2
        val chipBackgroundIcon: Icon = rippleBg.toBitmap(width = maxWidth, height = height).toIcon()

        val chipStyle =
            ViewStyle.Builder()
                // don't use Icon.createWithBitmap(), it crashes the app. Issue https://github.com/SimpleMobileTools/Simple-Keyboard/issues/248
                .setBackground(chipBackgroundIcon)
                .setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
                .build()

        val iconStyle = ImageViewStyle.Builder().build()

        val style = InlineSuggestionUi.newStyleBuilder()
            .setSingleIconChipStyle(chipStyle)
            .setChipStyle(chipStyle)
            .setStartIconStyle(iconStyle)
            .setEndIconStyle(iconStyle)
            .setSingleIconChipIconStyle(iconStyle)
            .setTitleStyle(
                TextViewStyle.Builder()
                    .setLayoutMargin(0, 0, horizontalPadding, 0)
                    .setTextColor(getProperTextColor())
                    .setTextSize(textSize)
                    .build()
            )
            .setSubtitleStyle(
                TextViewStyle.Builder()
                    .setTextColor(getProperTextColor())
                    .setTextSize(textSize)
                    .build()
            )
            .build()
        stylesBuilder.addStyle(style)
        return stylesBuilder.build()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key != null && key in arrayOf(
                SHOW_KEY_BORDERS, KEYBOARD_LANGUAGE, HEIGHT_PERCENTAGE, SHOW_NUMBERS_ROW, VOICE_INPUT_METHOD,
                TEXT_COLOR, BACKGROUND_COLOR, PRIMARY_COLOR, ACCENT_COLOR, CUSTOM_TEXT_COLOR, CUSTOM_BACKGROUND_COLOR,
                CUSTOM_PRIMARY_COLOR, CUSTOM_ACCENT_COLOR, IS_GLOBAL_THEME_ENABLED, IS_SYSTEM_THEME_ENABLED,
                AUTO_SYNC_MATERIAL_YOU
            )
        ) {
            if (::binding.isInitialized) {
                keyboardView?.setupKeyboard()
                updateBackgroundColors()
            }
        }
    }

    private fun setupEdgeToEdge() {
        window.window?.apply {
            WindowCompat.enableEdgeToEdge(this)
            ViewCompat.setOnApplyWindowInsetsListener(binding.keyboardHolder) { view, insets ->
                val system = insets.getInsetsIgnoringVisibility(Type.systemBars())
                binding.keyboardHolder.updatePadding(bottom = system.bottom)
                insets
            }
        }
    }

    private fun updateBackgroundColors() {
        val palette = Material3Palette.resolve(this)
        binding.keyboardHolder.setBackgroundColor(palette.backgroundColor)
        window.window?.setSystemBarsAppearance(palette.backgroundColor)

        binding.toolbarExpandButton.setColorFilter(palette.textColor)
        binding.suggestionLeft.setTextColor(palette.secondaryTextColor)
        binding.suggestionCenter.setTextColor(palette.accentColor)
        binding.suggestionRight.setTextColor(palette.secondaryTextColor)
        binding.suggestionDivider1.setBackgroundColor(palette.strokeColor)
        binding.suggestionDivider2.setBackgroundColor(palette.strokeColor)
        binding.voiceInputButton.setColorFilter(palette.textColor)
        binding.toolbarEmojiButton.setColorFilter(palette.textColor)
        binding.pinnedClipboardItems.setColorFilter(palette.textColor)
        binding.settingsCog.setColorFilter(palette.textColor)
    }

    private fun setupSuggestions() {
        binding.toolbarExpandButton.setOnClickListener {
            toggleQuickActions()
        }
        binding.suggestionLeft.setOnClickListener {
            onSuggestionClicked(0)
        }
        binding.suggestionCenter.setOnClickListener {
            onSuggestionClicked(1)
        }
        binding.suggestionRight.setOnClickListener {
            onSuggestionClicked(2)
        }
    }

    private fun toggleQuickActions() {
        isQuickActionsExpanded = !isQuickActionsExpanded
        updateToolbarState()
    }

    private fun collapseQuickActions() {
        if (isQuickActionsExpanded) {
            isQuickActionsExpanded = false
            updateToolbarState()
        }
    }

    private fun updateToolbarState() {
        if (!::binding.isInitialized) return
        if (isQuickActionsExpanded) {
            binding.toolbarExpandButton.setImageResource(R.drawable.ic_chevron_left_vector)
            binding.toolbarExpandButton.contentDescription = getString(R.string.collapse_actions)
            binding.wordSuggestionsHolder.beGone()
            binding.toolbarQuickActionsHolder.beVisible()
        } else {
            binding.toolbarExpandButton.setImageResource(R.drawable.ic_chevron_right_vector)
            binding.toolbarExpandButton.contentDescription = getString(R.string.expand_actions)
            binding.toolbarQuickActionsHolder.beGone()
            binding.wordSuggestionsHolder.beVisible()
        }
    }

    private fun onSuggestionClicked(index: Int) {
        val word = currentSuggestions.getOrNull(index) ?: return
        if (word.isEmpty()) return

        val inputConnection = currentInputConnection ?: return
        val lengthToDelete = composingWord.length
        if (lengthToDelete > 0) {
            inputConnection.deleteSurroundingText(lengthToDelete, 0)
        }
        inputConnection.commitText("$word ", 1)
        InputHistoryRecordingEngine.onReplaceBeforeCursor(this, lengthToDelete, "$word ")
        SuggestionEngine.learnWord(this, word)
        SuggestionEngine.recordWordTransition(lastCommittedWord, word)
        lastCommittedWord = word
        composingWord.clear()
        lastAutocorrect = null
        keyboardView?.hideClipboardPasteChip()
        updateShiftKeyState()
        keyboardView?.performKeypressFeedback(MyKeyboard.KEYCODE_SPACE)

        SuggestionEngine.getNextWordPredictionsAsync(word, null) { nextWords ->
            binding.root.post {
                if (nextWords.isNotEmpty()) {
                    showSuggestions(nextWords)
                } else {
                    hideSuggestions()
                }
            }
        }
    }

    private fun handleSpaceAutoCorrection(inputConnection: InputConnection) {
        val word = composingWord.toString()
        Log.d("IME_DEBUG", "handleSpaceAutoCorrection called for word='$word'")
        if (word.isEmpty()) return

        val correction = try {
            SuggestionEngine.getAutoCorrection(word, lastCommittedWord.ifEmpty { null })
        } catch (e: Exception) {
            Log.e("IME_DEBUG", "Error during getAutoCorrection: ${e.message}", e)
            null
        }

        val committed = try {
            if (correction != null && correction != word) {
                Log.d("IME_DEBUG", "Applying autocorrect: '$word' -> '$correction'")
                lastAutocorrect = AutocorrectRecord(word, correction, System.currentTimeMillis())
                val lengthToDelete = word.length
                inputConnection.deleteSurroundingText(lengthToDelete, 0)
                inputConnection.commitText(correction, 1)
                inputConnection.commitText(" ", 1)
                InputHistoryRecordingEngine.onReplaceBeforeCursor(this, lengthToDelete, "$correction ")
                SuggestionEngine.learnWord(this, correction)
                correction
            } else {
                Log.d("IME_DEBUG", "No autocorrect applied for '$word'")
                lastAutocorrect = null
                inputConnection.commitText(" ", 1)
                InputHistoryRecordingEngine.onKeystroke(' ')
                SuggestionEngine.learnWord(this, word)
                word
            }
        } catch (e: Exception) {
            Log.e("IME_DEBUG", "Error committing word: ${e.message}", e)
            word
        }

        try {
            SuggestionEngine.recordWordTransition(lastCommittedWord, committed)
        } catch (e: Exception) {
            Log.e("IME_DEBUG", "Error recording transition: ${e.message}", e)
        }
        lastCommittedWord = committed
        composingWord.clear()
        keyboardView?.hideClipboardPasteChip()

        // Fetch next word predictions safely
        try {
            SuggestionEngine.getNextWordPredictionsAsync(committed, null) { nextWords ->
                Log.d("IME_DEBUG", "Next word predictions for '$committed': $nextWords")
                binding.root.post {
                    if (nextWords.isNotEmpty()) {
                        showSuggestions(nextWords)
                    } else {
                        hideSuggestions()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("IME_DEBUG", "Error getting next word predictions: ${e.message}", e)
        }
    }

    private fun showSuggestions(suggestions: List<String>) {
        Log.d("IME_DEBUG", "showSuggestions called with: $suggestions")
        PredictiveTouchTargetEngine.updateComposingPrefix(composingWord.toString())
        if (suggestions.isEmpty() || suggestions.all { it.isEmpty() }) {
            hideSuggestions()
            return
        }

        currentSuggestions = suggestions
        val s0 = suggestions.getOrNull(0) ?: ""
        val s1 = suggestions.getOrNull(1) ?: ""
        val s2 = suggestions.getOrNull(2) ?: ""

        binding.suggestionLeft.text = s0
        binding.suggestionCenter.text = s1
        binding.suggestionRight.text = s2

        binding.suggestionLeft.beVisibleIf(s0.isNotEmpty())
        binding.suggestionDivider1.beVisibleIf(s0.isNotEmpty() && s1.isNotEmpty())
        binding.suggestionCenter.beVisibleIf(s1.isNotEmpty())
        binding.suggestionDivider2.beVisibleIf(s1.isNotEmpty() && s2.isNotEmpty())
        binding.suggestionRight.beVisibleIf(s2.isNotEmpty())

        if (!isQuickActionsExpanded) {
            binding.toolbarHolder.beVisible()
            binding.wordSuggestionsHolder.beVisible()
            binding.toolbarQuickActionsHolder.beGone()
            binding.toolbarExpandButton.setImageResource(R.drawable.ic_chevron_right_vector)
            binding.toolbarExpandButton.contentDescription = getString(R.string.expand_actions)
        }
    }

    private fun hideSuggestions() {
        PredictiveTouchTargetEngine.updateComposingPrefix("")
        currentSuggestions = emptyList()
        if (::binding.isInitialized) {
            binding.suggestionLeft.text = ""
            binding.suggestionCenter.text = ""
            binding.suggestionRight.text = ""
            binding.suggestionLeft.beGone()
            binding.suggestionDivider1.beGone()
            binding.suggestionCenter.beGone()
            binding.suggestionDivider2.beGone()
            binding.suggestionRight.beGone()
            if (!isQuickActionsExpanded) {
                binding.wordSuggestionsHolder.beGone()
                binding.toolbarQuickActionsHolder.beVisible()
                binding.toolbarExpandButton.setImageResource(R.drawable.ic_chevron_right_vector)
                binding.toolbarExpandButton.contentDescription = getString(R.string.expand_actions)
            }
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        org.fossify.keyboard.helpers.TouchPersonalizationManager.getInstance(this).flushOnPause()
        InputHistoryRecordingEngine.onFinishInput(this)
        if (composingWord.isNotEmpty()) {
            SuggestionEngine.learnWord(this, composingWord.toString())
            SuggestionEngine.recordWordTransition(lastCommittedWord, composingWord.toString())
            composingWord.clear()
            hideSuggestions()
        }
        lastCommittedWord = ""
        lastAutocorrect = null
        SuggestionEngine.onFinishSession()
    }

    override fun onDestroy() {
        super.onDestroy()
        SuggestionEngine.clearRejections()
    }


    private fun Bitmap.toIcon(): Icon {
        val byteArray: ByteArray = ByteArrayOutputStream().let { outputStream ->
            this.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            outputStream.toByteArray()
        }
        this.recycle()

        return Icon.createWithData(byteArray, 0, byteArray.size)
    }

    private fun constructKeyboard(keyboardXml: Int, enterKeyType: Int): MyKeyboard {
        val keyboard = MyKeyboard(this, keyboardXml, enterKeyType)
        return adjustBottomRow(keyboard)
    }

    // hacky, but good enough for now
    private fun adjustBottomRow(keyboard: MyKeyboard): MyKeyboard {
        keyboard.mKeys?.let { keys ->
            val spaceKeyIndex = keys.indexOfFirst { it.code == MyKeyboard.KEYCODE_SPACE }
            if (spaceKeyIndex != -1) {
                val spaceKey = keys[spaceKeyIndex]
                spaceKey.label = spaceKey.label.ifEmpty {
                    if (config.selectedLanguages.size > 1) getKeyboardLanguageText(config.keyboardLanguage) else ""
                }
            }

            if (keyboardMode != KEYBOARD_LETTERS) return keyboard
            val emojiKeyIndex = keys.indexOfFirst { it.code == MyKeyboard.KEYCODE_EMOJI_OR_LANGUAGE }
            if (emojiKeyIndex != -1 && spaceKeyIndex != -1) {
                val emojiKey = keys[emojiKeyIndex]
                val spaceKey = keys[spaceKeyIndex]
                emojiKey.secondaryIcon = null
                when {
                    config.showEmojiKey -> {
                        // no-op
                    }
                    config.showLanguageSwitchKey -> {
                        emojiKey.icon = ResourcesCompat.getDrawable(resources, R.drawable.ic_language_outlined, theme)
                    }
                    else -> {
                        // both emoji and language keys are disabled
                        spaceKey.width += emojiKey.width + emojiKey.gap
                        spaceKey.x = emojiKey.x

                        val mutableKeys = keys.toMutableList()
                        mutableKeys.removeAt(emojiKeyIndex)
                        keyboard.mKeys = mutableKeys
                    }
                }
            }

            // When emoji key is enabled, show settings-only popup with no hint on tools key
            if (config.showEmojiKey) {
                val currentKeys = keyboard.mKeys ?: return keyboard
                val toolsKey = currentKeys.firstOrNull { it.role == MyKeyboard.KEY_ROLE_TOOLS }
                if (toolsKey != null) {
                    toolsKey.popupResId = R.xml.popup_tools
                    toolsKey.secondaryIcon = null
                }
            }
        }
        return keyboard
    }
}

