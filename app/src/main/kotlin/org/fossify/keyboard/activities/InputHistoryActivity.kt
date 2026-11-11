package org.fossify.keyboard.activities

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.keyboard.R
import org.fossify.keyboard.extensions.config
import org.fossify.keyboard.adapters.CalendarAdapter
import org.fossify.keyboard.adapters.TimeBlockedHistoryAdapter
import org.fossify.keyboard.databases.InputHistoryDatabase
import org.fossify.keyboard.databinding.ActivityInputHistoryBinding
import org.fossify.keyboard.databinding.DialogClearHistoryPinBinding
import org.fossify.keyboard.databinding.DialogExportPasswordBinding
import org.fossify.keyboard.databinding.DialogImportPasswordBinding
import org.fossify.keyboard.databinding.DialogSetHistoryPinBinding
import org.fossify.keyboard.databinding.DialogUnlockHistoryPinBinding
import org.fossify.keyboard.dialogs.ManageExcludedAppsDialog
import org.fossify.keyboard.helpers.HistoryBackupManager
import org.fossify.keyboard.helpers.HistorySecurityManager
import org.fossify.keyboard.helpers.InputHistoryRecordingEngine
import org.fossify.keyboard.models.CalendarDayModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class InputHistoryActivity : SimpleActivity() {

    private val binding by viewBinding(ActivityInputHistoryBinding::inflate)

    private val timeBlockedAdapter = TimeBlockedHistoryAdapter()
    private lateinit var calendarAdapter: CalendarAdapter

    // Active session dates set for calendar indicators ("$year-$month-$day")
    private val sessionDatesSet = HashSet<String>()

    // Calendar navigation state
    private var displayedYear = 0
    private var displayedMonth = 0 // 0-indexed

    // Selected date state
    private var selectedYear = 0
    private var selectedMonth = 0
    private var selectedDay = 0

    // Filter state
    private var currentFilterStatuses: List<String>? = null // null = ALL
    private var sessionsJob: Job? = null
    private var timestampsJob: Job? = null

    // Export & Import state
    private var pendingExportPassword = ""
    private val exportDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null && pendingExportPassword.isNotEmpty()) {
            performExportToUri(uri, pendingExportPassword)
        }
        pendingExportPassword = ""
    }

    private val importDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            showImportPasswordDialog(uri)
        }
    }

    private val monthYearFormat = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
    private val dayHeaderFormat = SimpleDateFormat("EEEE, MMM d, yyyy", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        val todayCal = Calendar.getInstance()
        displayedYear = todayCal.get(Calendar.YEAR)
        displayedMonth = todayCal.get(Calendar.MONTH)
        selectedYear = displayedYear
        selectedMonth = displayedMonth
        selectedDay = todayCal.get(Calendar.DAY_OF_MONTH)

        initViews()
        setupOptionsMenu()
        setupCalendarNavigation()
        setupFilterChips()

        // Feature-level authentication gate:
        // Opt-in via historyLockEnabled (default false)
        if (!config.historyLockEnabled || HistorySecurityManager.isUnlocked) {
            showUnlockedContent()
        } else {
            showLockedScreen()
            authenticateUser(isInitial = true)
        }
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.inputHistoryAppbar, NavigationIcon.Arrow)
    }

    private fun initViews() {
        binding.apply {
            setupEdgeToEdge(padBottomSystem = listOf(timeBlockedList))
            setupMaterialScrollListener(timeBlockedList, inputHistoryAppbar)

            // Setup time-blocked day view recycler
            timeBlockedList.adapter = timeBlockedAdapter

            // Setup calendar grid (7 columns)
            calendarGrid.layoutManager = GridLayoutManager(this@InputHistoryActivity, 7)
            calendarAdapter = CalendarAdapter { dayModel ->
                selectedYear = dayModel.year
                selectedMonth = dayModel.month
                selectedDay = dayModel.day
                updateCalendarDisplay()
                loadSelectedDaySessions()
            }
            calendarGrid.adapter = calendarAdapter

            // Unlock button on locked overlay
            historyUnlockButton.setOnClickListener {
                authenticateUser(isInitial = false)
            }
        }
    }

    private fun setupOptionsMenu() {
        binding.inputHistoryToolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_export -> {
                    if (HistorySecurityManager.isUnlocked) {
                        showExportPasswordDialog()
                    } else {
                        authenticateUser(isInitial = false)
                    }
                    true
                }
                R.id.action_import -> {
                    if (HistorySecurityManager.isUnlocked) {
                        startImportFilePicker()
                    } else {
                        authenticateUser(isInitial = false)
                    }
                    true
                }
                R.id.action_lock_now -> {
                    lockFeature()
                    true
                }
                R.id.action_manage_excluded_apps -> {
                    if (HistorySecurityManager.isUnlocked) {
                        ManageExcludedAppsDialog(this) {}.show()
                    } else {
                        authenticateUser(isInitial = false)
                    }
                    true
                }
                R.id.action_change_pin -> {
                    if (HistorySecurityManager.isUnlocked) {
                        showSetPinDialog(isChangingPin = true)
                    } else {
                        authenticateUser(isInitial = false)
                    }
                    true
                }
                R.id.action_view_logs -> {
                    startActivity(Intent(this, DebugLogActivity::class.java))
                    true
                }
                R.id.action_clear_all -> {
                    if (HistorySecurityManager.isUnlocked) {
                        confirmClearHistory()
                    }
                    true
                }
                else -> false
            }
        }
    }

    // ==========================================
    // Phase 6: Encrypted Export & Import
    // ==========================================

    private fun showExportPasswordDialog() {
        val dialogBinding = DialogExportPasswordBinding.inflate(layoutInflater)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.export_history_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.export_history, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val pass = dialogBinding.exportPasswordInput.text?.toString() ?: ""
            val confirmPass = dialogBinding.exportPasswordConfirmInput.text?.toString() ?: ""

            dialogBinding.exportPasswordLayout.error = null
            dialogBinding.exportPasswordConfirmLayout.error = null

            if (pass.length < 6) {
                dialogBinding.exportPasswordLayout.error = getString(R.string.password_too_short)
                return@setOnClickListener
            }

            if (pass != confirmPass) {
                dialogBinding.exportPasswordConfirmLayout.error = getString(R.string.passwords_dont_match)
                return@setOnClickListener
            }

            pendingExportPassword = pass
            dialog.dismiss()

            // Launch SAF file creator
            val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val defaultFilename = "input_history_backup_$dateStr.json"
            exportDocumentLauncher.launch(defaultFilename)
        }
    }

    private fun performExportToUri(uri: Uri, password: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val outputStream = contentResolver.openOutputStream(uri)
                if (outputStream == null) {
                    withContext(Dispatchers.Main) {
                        toast(getString(R.string.export_failed, "Cannot open output stream"))
                    }
                    return@launch
                }

                val count = HistoryBackupManager.exportEncryptedHistory(
                    applicationContext,
                    outputStream,
                    password
                )

                withContext(Dispatchers.Main) {
                    if (count == 0) {
                        toast(R.string.no_sessions_to_export)
                    } else {
                        toast(getString(R.string.export_successful, count))
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    toast(getString(R.string.export_failed, e.localizedMessage ?: "Unknown error"))
                }
            }
        }
    }

    private fun startImportFilePicker() {
        importDocumentLauncher.launch(arrayOf("application/json", "*/*"))
    }

    private fun showImportPasswordDialog(uri: Uri) {
        val dialogBinding = DialogImportPasswordBinding.inflate(layoutInflater)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.import_history_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.import_history, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val pass = dialogBinding.importPasswordInput.text?.toString() ?: ""

            if (pass.isEmpty()) {
                dialogBinding.importPasswordLayout.error = getString(R.string.password_required)
                return@setOnClickListener
            }

            dialogBinding.importPasswordLayout.error = null
            dialog.dismiss()
            performImportFromUri(uri, pass)
        }
    }

    private fun performImportFromUri(uri: Uri, password: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val inputStream = contentResolver.openInputStream(uri)
                if (inputStream == null) {
                    withContext(Dispatchers.Main) {
                        toast(getString(R.string.import_failed, "Cannot open input stream"))
                    }
                    return@launch
                }

                val result = HistoryBackupManager.importEncryptedHistory(
                    applicationContext,
                    inputStream,
                    password
                )

                withContext(Dispatchers.Main) {
                    when (result) {
                        is HistoryBackupManager.ImportResult.Success -> {
                            toast(getString(R.string.import_successful, result.importedCount))
                            observeAllSessionDates()
                            loadSelectedDaySessions()
                        }
                        is HistoryBackupManager.ImportResult.Error -> {
                            toast(getString(R.string.import_failed, result.message))
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    toast(getString(R.string.import_failed, e.localizedMessage ?: "Unknown error"))
                }
            }
        }
    }

    // ==========================================
    // Phase 5: Feature-Level Authentication Gate
    // ==========================================

    private fun showLockedScreen() {
        binding.historyLockedContainer.beVisibleIf(true)
        binding.historyMainContent.beVisibleIf(false)
    }

    private fun showUnlockedContent() {
        binding.historyLockedContainer.beVisibleIf(false)
        binding.historyMainContent.beVisibleIf(true)
        observeAllSessionDates()
        loadSelectedDaySessions()
    }

    private fun lockFeature() {
        HistorySecurityManager.isUnlocked = false
        sessionsJob?.cancel()
        timestampsJob?.cancel()
        showLockedScreen()
    }

    private fun authenticateUser(isInitial: Boolean) {
        if (!HistorySecurityManager.isPinSet(this)) {
            // First time use of the feature: prompt to set PIN
            showSetPinDialog(isChangingPin = false)
            return
        }

        // Check if biometric authentication is available on device
        val biometricManager = BiometricManager.from(this)
        val canBio = biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK
        ) == BiometricManager.BIOMETRIC_SUCCESS

        if (canBio) {
            showBiometricPrompt()
        } else {
            showPinUnlockDialog(canUseBiometrics = false)
        }
    }

    private fun showBiometricPrompt() {
        val executor = ContextCompat.getMainExecutor(this)
        val biometricPrompt = BiometricPrompt(
            this,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    onAuthSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    // On user cancel, negative button ("Use PIN"), or lockout -> fallback to PIN dialog
                    if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_CANCELED
                    ) {
                        showPinUnlockDialog(canUseBiometrics = true)
                    } else {
                        showPinUnlockDialog(canUseBiometrics = false)
                    }
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    // Biometric sensor did not recognize; remains active for retries
                }
            }
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.unlock_input_history))
            .setSubtitle(getString(R.string.authenticate_to_access_history))
            .setNegativeButtonText(getString(R.string.use_pin))
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK
            )
            .build()

        biometricPrompt.authenticate(promptInfo)
    }

    private fun showSetPinDialog(isChangingPin: Boolean) {
        val dialogBinding = DialogSetHistoryPinBinding.inflate(layoutInflater)
        if (isChangingPin) {
            dialogBinding.setPinDescription.text = getString(R.string.set_history_pin_desc)
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (isChangingPin) R.string.change_pin else R.string.set_history_pin)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.ok, null)
            .setNegativeButton(R.string.cancel) { d, _ ->
                d.dismiss()
                if (!HistorySecurityManager.isUnlocked && !isChangingPin) {
                    finish()
                }
            }
            .setCancelable(isChangingPin)
            .create()

        dialog.setOnCancelListener {
            if (!HistorySecurityManager.isUnlocked && !isChangingPin) {
                finish()
            }
        }

        dialog.show()

        // Override positive button click to prevent auto-dismiss on validation failure
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val pin = dialogBinding.pinInput.text?.toString()?.trim() ?: ""
            val confirm = dialogBinding.pinConfirmInput.text?.toString()?.trim() ?: ""

            dialogBinding.pinInputLayout.error = null
            dialogBinding.pinConfirmLayout.error = null

            if (pin.length < 4) {
                dialogBinding.pinInputLayout.error = getString(R.string.pin_too_short)
                return@setOnClickListener
            }

            if (pin != confirm) {
                dialogBinding.pinConfirmLayout.error = getString(R.string.pin_mismatch)
                return@setOnClickListener
            }

            // Secure storage via EncryptedSharedPreferences (never plaintext, never logged)
            HistorySecurityManager.setPin(this, pin)
            toast(R.string.pin_saved)
            dialog.dismiss()
            onAuthSuccess()
        }
    }

    private fun showPinUnlockDialog(canUseBiometrics: Boolean) {
        val dialogBinding = DialogUnlockHistoryPinBinding.inflate(layoutInflater)
        dialogBinding.unlockUseBiometricsBtn.beVisibleIf(canUseBiometrics)

        var dialogRef: AlertDialog? = null

        dialogBinding.unlockUseBiometricsBtn.setOnClickListener {
            dialogRef?.dismiss()
            showBiometricPrompt()
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.unlock_input_history)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.unlock, null)
            .setNegativeButton(R.string.cancel) { d, _ ->
                d.dismiss()
                if (!HistorySecurityManager.isUnlocked) {
                    finish()
                }
            }
            .setCancelable(false)
            .create()

        dialogRef = dialog
        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val enteredPin = dialogBinding.unlockPinInput.text?.toString()?.trim() ?: ""
            if (HistorySecurityManager.verifyPin(this, enteredPin)) {
                dialogBinding.unlockPinLayout.error = null
                dialog.dismiss()
                onAuthSuccess()
            } else {
                dialogBinding.unlockPinLayout.error = getString(R.string.incorrect_pin)
                dialogBinding.unlockPinInput.setText("")
            }
        }
    }

    private fun onAuthSuccess() {
        HistorySecurityManager.isUnlocked = true
        showUnlockedContent()
    }

    private fun confirmClearHistory() {
        if (!HistorySecurityManager.isPinSet(this)) {
            // Fallback confirmation if no PIN has been set
            ConfirmationDialog(
                activity = this,
                message = "",
                messageId = R.string.clear_history_confirmation,
                positive = R.string.yes,
                negative = R.string.cancel
            ) {
                executePermanentDeletion()
            }
            return
        }

        // Require Phase 5 PIN confirmation to permanently delete all history records
        val dialogBinding = DialogClearHistoryPinBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clear_history_pin_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.permanently_delete, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val enteredPin = dialogBinding.clearPinInput.text?.toString()?.trim() ?: ""
            if (HistorySecurityManager.verifyPin(this, enteredPin)) {
                dialog.dismiss()
                executePermanentDeletion()
            } else {
                dialogBinding.clearPinLayout.error = getString(R.string.incorrect_pin)
                dialogBinding.clearPinInput.setText("")
            }
        }
    }

    private fun executePermanentDeletion() {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = InputHistoryDatabase.getInstance(applicationContext)
            db.inputHistoryDao().clearAll()
            db.deletionEventDao().clearAll()

            withContext(Dispatchers.Main) {
                toast(R.string.history_permanently_cleared)
                sessionDatesSet.clear()
                updateCalendarDisplay()
                loadSelectedDaySessions()
            }
        }
    }

    // ==========================================
    // Calendar Navigation & Day View Loading
    // ==========================================

    private fun setupCalendarNavigation() {
        binding.apply {
            calendarPrevMonth.setOnClickListener {
                if (displayedMonth == 0) {
                    displayedMonth = 11
                    displayedYear--
                } else {
                    displayedMonth--
                }
                updateCalendarDisplay()
            }

            calendarNextMonth.setOnClickListener {
                if (displayedMonth == 11) {
                    displayedMonth = 0
                    displayedYear++
                } else {
                    displayedMonth++
                }
                updateCalendarDisplay()
            }

            calendarTodayBtn.setOnClickListener {
                val today = Calendar.getInstance()
                displayedYear = today.get(Calendar.YEAR)
                displayedMonth = today.get(Calendar.MONTH)
                selectedYear = displayedYear
                selectedMonth = displayedMonth
                selectedDay = today.get(Calendar.DAY_OF_MONTH)
                updateCalendarDisplay()
                loadSelectedDaySessions()
            }
        }
        updateCalendarDisplay()
    }

    private fun setupFilterChips() {
        binding.inputHistoryFilterGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            currentFilterStatuses = when {
                checkedIds.contains(R.id.filter_chip_sent) -> listOf(
                    InputHistoryRecordingEngine.STATUS_SENT,
                    InputHistoryRecordingEngine.STATUS_LEFT_FIELD,
                    "0",
                    "COMPLETED"
                )
                checkedIds.contains(R.id.filter_chip_discarded) -> listOf(
                    InputHistoryRecordingEngine.STATUS_DISCARDED,
                    "1"
                )
                checkedIds.contains(R.id.filter_chip_unclear) -> listOf(
                    InputHistoryRecordingEngine.STATUS_CLEARED_UNKNOWN
                )
                else -> null
            }
            loadSelectedDaySessions()
        }
    }

    /**
     * Observes all recorded session timestamps from Room to mark dates with indicator dots.
     */
    private fun observeAllSessionDates() {
        timestampsJob?.cancel()
        val dao = InputHistoryDatabase.getInstance(applicationContext).inputHistoryDao()
        timestampsJob = lifecycleScope.launch {
            dao.getAllSessionTimestamps().collectLatest { timestamps ->
                sessionDatesSet.clear()
                val cal = Calendar.getInstance()
                for (ts in timestamps) {
                    cal.timeInMillis = ts
                    val y = cal.get(Calendar.YEAR)
                    val m = cal.get(Calendar.MONTH)
                    val d = cal.get(Calendar.DAY_OF_MONTH)
                    sessionDatesSet.add("$y-$m-$d")
                }
                updateCalendarDisplay()
            }
        }
    }

    /**
     * Generates day cells for displayedMonth/displayedYear and updates calendarAdapter.
     */
    private fun updateCalendarDisplay() {
        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, displayedYear)
            set(Calendar.MONTH, displayedMonth)
            set(Calendar.DAY_OF_MONTH, 1)
        }

        binding.calendarMonthTitle.text = monthYearFormat.format(cal.time)

        // Determine starting day of week (Sunday=1, Monday=2, etc.)
        val startDayOfWeek = cal.get(Calendar.DAY_OF_WEEK) // 1..7 (1 = Sunday)
        val maxDaysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)

        val todayCal = Calendar.getInstance()
        val todayYear = todayCal.get(Calendar.YEAR)
        val todayMonth = todayCal.get(Calendar.MONTH)
        val todayDay = todayCal.get(Calendar.DAY_OF_MONTH)

        val dayCells = ArrayList<CalendarDayModel>()

        // 1. Add empty padding cells for days before the 1st
        val leadingEmptyCount = startDayOfWeek - 1
        for (i in 0 until leadingEmptyCount) {
            dayCells.add(
                CalendarDayModel(
                    day = 0,
                    month = displayedMonth,
                    year = displayedYear,
                    isCurrentMonth = false,
                    isSelected = false,
                    isToday = false,
                    hasSessions = false
                )
            )
        }

        // 2. Add actual days of current month
        for (d in 1..maxDaysInMonth) {
            val dateKey = "$displayedYear-$displayedMonth-$d"
            val hasSessions = sessionDatesSet.contains(dateKey)
            val isSelected = (displayedYear == selectedYear && displayedMonth == selectedMonth && d == selectedDay)
            val isToday = (displayedYear == todayYear && displayedMonth == todayMonth && d == todayDay)

            dayCells.add(
                CalendarDayModel(
                    day = d,
                    month = displayedMonth,
                    year = displayedYear,
                    isCurrentMonth = true,
                    isSelected = isSelected,
                    isToday = isToday,
                    hasSessions = hasSessions
                )
            )
        }

        // 3. Trailing padding to make multiple of 7
        val trailingEmptyCount = (7 - (dayCells.size % 7)) % 7
        for (i in 0 until trailingEmptyCount) {
            dayCells.add(
                CalendarDayModel(
                    day = 0,
                    month = displayedMonth,
                    year = displayedYear,
                    isCurrentMonth = false,
                    isSelected = false,
                    isToday = false,
                    hasSessions = false
                )
            )
        }

        calendarAdapter.setDays(dayCells)
    }

    /**
     * Loads sessions for the 24-hour range of selectedYear/selectedMonth/selectedDay.
     */
    private fun loadSelectedDaySessions() {
        sessionsJob?.cancel()

        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, selectedYear)
            set(Calendar.MONTH, selectedMonth)
            set(Calendar.DAY_OF_MONTH, selectedDay)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startOfDay = cal.timeInMillis

        // Update day view header title
        binding.selectedDateTitle.text = dayHeaderFormat.format(cal.time)

        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
        val endOfDay = cal.timeInMillis

        val dao = InputHistoryDatabase.getInstance(applicationContext).inputHistoryDao()
        val flow = if (currentFilterStatuses == null) {
            dao.getSessionsWithDeletionsInRange(startOfDay, endOfDay)
        } else {
            dao.getSessionsWithDeletionsInRangeAndStatuses(startOfDay, endOfDay, currentFilterStatuses!!)
        }

        sessionsJob = lifecycleScope.launch {
            flow.collectLatest { sessions ->
                timeBlockedAdapter.setSessions(sessions)

                val count = sessions.size
                binding.selectedDateSummary.text = when (count) {
                    0 -> "0 sessions"
                    1 -> "1 session · 2-hour blocks"
                    else -> "$count sessions · 2-hour blocks"
                }

                binding.timeBlockedList.beVisibleIf(sessions.isNotEmpty())
                binding.inputHistoryPlaceholder.beVisibleIf(sessions.isEmpty())
            }
        }
    }
}
