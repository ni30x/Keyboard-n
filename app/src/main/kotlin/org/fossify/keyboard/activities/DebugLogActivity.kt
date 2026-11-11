package org.fossify.keyboard.activities

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.keyboard.R
import org.fossify.keyboard.databinding.ActivityDebugLogBinding
import org.fossify.keyboard.helpers.AppLogManager

class DebugLogActivity : SimpleActivity() {

    private val binding by viewBinding(ActivityDebugLogBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        initViews()
        reloadLogs()
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.debugLogAppbar, NavigationIcon.Arrow)
        reloadLogs()
        AppLogManager.onNewLogListener = { newEntry ->
            binding.logTextContent.append("$newEntry\n")
            binding.logScrollView.post {
                binding.logScrollView.fullScroll(android.view.View.FOCUS_DOWN)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        AppLogManager.onNewLogListener = null
    }

    private fun initViews() {
        binding.apply {
            setupEdgeToEdge(padBottomSystem = listOf(logScrollView))

            btnCopyLogs.setOnClickListener {
                val logs = logTextContent.text.toString()
                if (logs.isNotEmpty()) {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Fossify Keyboard Logs", logs)
                    clipboard.setPrimaryClip(clip)
                    toast(R.string.logs_copied_to_clipboard)
                }
            }

            btnShareLogs.setOnClickListener {
                val logs = logTextContent.text.toString()
                if (logs.isNotEmpty()) {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "Fossify Keyboard Diagnostic Logs")
                        putExtra(Intent.EXTRA_TEXT, logs)
                    }
                    startActivity(Intent.createChooser(intent, getString(R.string.share_logs)))
                }
            }

            btnClearLogs.setOnClickListener {
                ConfirmationDialog(
                    activity = this@DebugLogActivity,
                    message = "",
                    messageId = R.string.clear_logs_confirmation,
                    positive = R.string.yes,
                    negative = R.string.cancel
                ) {
                    AppLogManager.clearLogs()
                    reloadLogs()
                    toast(R.string.logs_cleared)
                }
            }
        }
    }

    private fun reloadLogs() {
        val hasCrash = AppLogManager.hasCrash()
        val formattedLogs = AppLogManager.getFormattedLogs()

        binding.apply {
            logTextContent.text = formattedLogs

            if (hasCrash) {
                debugStatusCard.setCardBackgroundColor(Color.parseColor("#22F44336"))
                debugStatusCard.strokeColor = Color.parseColor("#F44336")
                debugStatusTitle.text = getString(R.string.status_crash_detected)
                debugStatusTitle.setTextColor(Color.parseColor("#D32F2F"))
                debugStatusDetails.text = "A fatal crash was captured. Check the stack trace below."
            } else {
                debugStatusCard.setCardBackgroundColor(Color.parseColor("#224CAF50"))
                debugStatusCard.strokeColor = Color.parseColor("#4CAF50")
                debugStatusTitle.text = getString(R.string.status_no_crashes)
                debugStatusTitle.setTextColor(Color.parseColor("#388E3C"))
                debugStatusDetails.text = getString(R.string.status_healthy_summary)
            }
        }
    }
}
