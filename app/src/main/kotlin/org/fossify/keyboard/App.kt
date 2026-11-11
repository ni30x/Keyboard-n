package org.fossify.keyboard

import android.app.Application
import androidx.emoji2.bundled.BundledEmojiCompatConfig
import androidx.emoji2.text.EmojiCompat
import org.fossify.commons.extensions.checkUseEnglish
import org.fossify.keyboard.extensions.isDeviceInDirectBootMode

import org.fossify.keyboard.helpers.AppLogManager

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLogManager.init(this)
        if (!isDeviceInDirectBootMode) {
            checkUseEnglish()
        }
        setupEmojiCompat()
    }

    private fun setupEmojiCompat() {
        val config = BundledEmojiCompatConfig(this)
        EmojiCompat.init(config)
    }
}
