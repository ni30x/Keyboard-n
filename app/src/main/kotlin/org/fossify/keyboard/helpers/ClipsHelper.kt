package org.fossify.keyboard.helpers

import android.content.Context
import org.fossify.keyboard.extensions.clipsDB
import org.fossify.keyboard.models.Clip

class ClipsHelper(val context: Context) {

    // Make sure clips have unique values: if clip value already exists, delete older copy and re-insert fresh at top
    fun insertClip(clip: Clip): Long {
        val trimmedValue = clip.value.trim()
        if (trimmedValue.isEmpty()) return -1
        context.clipsDB.deleteByValue(trimmedValue)
        return context.clipsDB.insertOrUpdate(clip.copy(id = 0, value = trimmedValue))
    }
}
