package org.fossify.keyboard.dialogs

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fossify.keyboard.R
import org.fossify.keyboard.databases.InputHistoryDatabase
import org.fossify.keyboard.extensions.config
import org.fossify.keyboard.helpers.InputHistoryRecordingEngine

class ManageExcludedAppsDialog(
    private val activity: Activity,
    private val onUpdated: () -> Unit
) {

    data class AppEntry(
        val packageName: String,
        val label: String,
        var isExcluded: Boolean
    )

    fun show() {
        CoroutineScope(Dispatchers.IO).launch {
            val pm = activity.packageManager
            val config = activity.config
            val excludedSet = HashSet(config.blockedHistoryPackages)

            // Gather candidate apps: from recorded history + installed non-system apps
            val candidatePackages = HashSet<String>()
            val dbPackages = InputHistoryDatabase.getInstance(activity)
                .inputHistoryDao()
                .getDistinctPackages()
            candidatePackages.addAll(dbPackages)

            val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            for (appInfo in installedApps) {
                // Include non-system apps or apps with launch intents
                val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                if (!isSystem || pm.getLaunchIntentForPackage(appInfo.packageName) != null) {
                    candidatePackages.add(appInfo.packageName)
                }
            }

            // Also keep any previously excluded package even if not currently found
            candidatePackages.addAll(excludedSet)

            val appEntries = candidatePackages.map { pkg ->
                val label = try {
                    val info = pm.getApplicationInfo(pkg, 0)
                    pm.getApplicationLabel(info).toString()
                } catch (e: Exception) {
                    pkg
                }
                AppEntry(
                    packageName = pkg,
                    label = label,
                    isExcluded = excludedSet.contains(pkg)
                )
            }.sortedWith(compareBy({ !it.isExcluded }, { it.label.lowercase() }))

            withContext(Dispatchers.Main) {
                if (activity.isFinishing || activity.isDestroyed) return@withContext

                val displayNames = appEntries.map { "${it.label} (${it.packageName})" }.toTypedArray()
                val checkedStates = appEntries.map { it.isExcluded }.toBooleanArray()

                MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.select_excluded_apps)
                    .setMultiChoiceItems(displayNames, checkedStates) { _, which, isChecked ->
                        checkedStates[which] = isChecked
                    }
                    .setPositiveButton(R.string.ok) { _, _ ->
                        val updatedExcluded = HashSet<String>()
                        for (i in appEntries.indices) {
                            if (checkedStates[i]) {
                                updatedExcluded.add(appEntries[i].packageName)
                            }
                        }
                        config.blockedHistoryPackages = updatedExcluded

                        // If the currently recorded app is now excluded, stop buffering immediately
                        if (updatedExcluded.contains(InputHistoryRecordingEngine.currentPackage)) {
                            InputHistoryRecordingEngine.stopRecording()
                        }

                        onUpdated()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
    }
}
