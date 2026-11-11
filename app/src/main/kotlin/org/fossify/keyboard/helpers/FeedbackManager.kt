package org.fossify.keyboard.helpers

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * High-performance feedback manager responsible for keypress haptics and audio effects.
 *
 * Requirements:
 * 1. Haptics:
 *    - Lightweight trigger on DOWN using View.performHapticFeedback(KEYBOARD_TAP).
 *    - Fallback to short Vibrator effect.
 *    - Respects system haptics setting (if disabled in system, does nothing).
 *    - Adjustable intensity setting where the device vibrator supports amplitude control.
 * 2. Sound:
 *    - Preloaded SoundPool with low latency (never MediaPlayer).
 *    - Respects system volume and in-app sound volume setting.
 * 3. Zero allocations in hot path.
 */
class FeedbackManager(private val context: Context) {

    private val settingsCache = SettingsCache.getInstance(context)

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vibratorManager?.defaultVibrator ?: (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private val hasAmplitudeControl: Boolean = vibrator?.hasAmplitudeControl() == true

    private var soundPool: SoundPool? = null
    private var soundStandardId: Int = 0
    private var soundSpaceId: Int = 0
    private var soundDeleteId: Int = 0
    private var soundReturnId: Int = 0

    init {
        initSoundPool()
    }

    private fun initSoundPool() {
        try {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            soundPool = SoundPool.Builder()
                .setMaxStreams(4)
                .setAudioAttributes(audioAttributes)
                .build()

            // Preload click audio files: try system files first, fallback to synthesized PCM clicks
            loadSounds()
        } catch (e: Exception) {
            soundPool = null
        }
    }

    private fun loadSounds() {
        val pool = soundPool ?: return

        val systemStandard = File("/system/media/audio/ui/KeypressStandard.ogg")
        val systemSpace = File("/system/media/audio/ui/KeypressSpacebar.ogg")
        val systemDelete = File("/system/media/audio/ui/KeypressDelete.ogg")
        val systemReturn = File("/system/media/audio/ui/KeypressReturn.ogg")

        if (systemStandard.exists() && systemStandard.canRead()) {
            soundStandardId = pool.load(systemStandard.absolutePath, 1)
            soundSpaceId = if (systemSpace.exists()) pool.load(systemSpace.absolutePath, 1) else soundStandardId
            soundDeleteId = if (systemDelete.exists()) pool.load(systemDelete.absolutePath, 1) else soundStandardId
            soundReturnId = if (systemReturn.exists()) pool.load(systemReturn.absolutePath, 1) else soundStandardId
        } else {
            // Synthesize low-latency PCM WAV click files locally so SoundPool works guaranteed
            try {
                val soundDir = File(context.cacheDir, "sounds")
                if (!soundDir.exists()) soundDir.mkdirs()

                val stdWav = File(soundDir, "click_std.wav")
                if (!stdWav.exists()) generateClickWav(stdWav, frequency = 3200f, decay = 0.85f, durationMs = 12)
                soundStandardId = pool.load(stdWav.absolutePath, 1)

                val spaceWav = File(soundDir, "click_space.wav")
                if (!spaceWav.exists()) generateClickWav(spaceWav, frequency = 1800f, decay = 0.88f, durationMs = 15)
                soundSpaceId = pool.load(spaceWav.absolutePath, 1)

                val delWav = File(soundDir, "click_del.wav")
                if (!delWav.exists()) generateClickWav(delWav, frequency = 2400f, decay = 0.85f, durationMs = 12)
                soundDeleteId = pool.load(delWav.absolutePath, 1)

                val retWav = File(soundDir, "click_ret.wav")
                if (!retWav.exists()) generateClickWav(retWav, frequency = 2800f, decay = 0.86f, durationMs = 14)
                soundReturnId = pool.load(retWav.absolutePath, 1)
            } catch (e: Exception) {
                // If synthesis or caching fails, AudioManager.playSoundEffect will serve as fallback
            }
        }
    }

    /**
     * Synthesizes a crisp, lightweight mechanical keyboard click as a 16-bit 44.1kHz mono WAV file.
     */
    private fun generateClickWav(target: File, frequency: Float, decay: Float, durationMs: Int) {
        val sampleRate = 44100
        val numSamples = (sampleRate * (durationMs / 1000f)).toInt()
        val dataSize = numSamples * 2
        val totalSize = 36 + dataSize

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(totalSize)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16) // Subchunk1Size (16 for PCM)
        header.putShort(1.toShort()) // AudioFormat (1 for PCM)
        header.putShort(1.toShort()) // NumChannels (Mono)
        header.putInt(sampleRate) // SampleRate
        header.putInt(sampleRate * 2) // ByteRate (SampleRate * NumChannels * BitsPerSample/8)
        header.putShort(2.toShort()) // BlockAlign (NumChannels * BitsPerSample/8)
        header.putShort(16.toShort()) // BitsPerSample
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataSize)

        val samples = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
        var envelope = 1.0f
        for (i in 0 until numSamples) {
            val t = i.toFloat() / sampleRate
            val wave = Math.sin(2.0 * Math.PI * frequency * t).toFloat()
            // Add subtle noise click transient at the very onset
            val noise = if (i < 80) ((Math.random().toFloat() * 2f - 1f) * 0.4f) else 0f
            val sampleVal = ((wave * 0.8f + noise) * envelope * 26000f).toInt().coerceIn(-32768, 32767)
            samples.putShort(sampleVal.toShort())
            envelope *= decay
        }

        FileOutputStream(target).use { fos ->
            fos.write(header.array())
            fos.write(samples.array())
            fos.flush()
        }
    }

    /**
     * Checks if haptic feedback is globally enabled in system settings.
     */
    private fun isSystemHapticFeedbackEnabled(): Boolean {
        return try {
            Settings.System.getInt(
                context.contentResolver,
                Settings.System.HAPTIC_FEEDBACK_ENABLED,
                1
            ) != 0
        } catch (e: Exception) {
            true
        }
    }

    /**
     * Checks if sound effects are globally enabled in system settings.
     */
    private fun isSystemSoundEffectsEnabled(): Boolean {
        return try {
            Settings.System.getInt(
                context.contentResolver,
                Settings.System.SOUND_EFFECTS_ENABLED,
                1
            ) != 0
        } catch (e: Exception) {
            true
        }
    }

    /**
     * Performs haptic feedback on key DOWN.
     * Sequence requirement: Visual -> Haptic -> Sound.
     */
    fun performHaptic(view: View) {
        if (!settingsCache.vibrateOnKeypress) return
        if (!isSystemHapticFeedbackEnabled()) return

        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return

        if (hasAmplitudeControl && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Precise custom amplitude haptic feedback (1..255)
            val amplitude = (settingsCache.hapticStrength * 255 / 100).coerceIn(1, 255)
            try {
                vib.vibrate(VibrationEffect.createOneShot(12L, amplitude))
                return
            } catch (e: Exception) {
                // fallback to view haptics below
            }
        }

        // Lightest reliable standard mechanism: View.performHapticFeedback
        val performed = view.performHapticFeedback(
            HapticFeedbackConstants.KEYBOARD_TAP,
            HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
        )
        if (!performed) {
            // Fallback to short vibrator effect
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vib.vibrate(VibrationEffect.createOneShot(12L, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vib.vibrate(12L)
                }
            } catch (ignored: Exception) {
            }
        }
    }

    /**
     * Performs sound feedback on key DOWN using preloaded SoundPool.
     */
    fun performSound(keyCode: Int) {
        val soundMode = settingsCache.soundOnKeypress
        if (soundMode == SOUND_NONE) return

        if (soundMode == SOUND_SYSTEM) {
            // Respect ringer mode and system sound effects toggle
            if (audioManager.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
            if (!isSystemSoundEffectsEnabled()) return
        }

        // Compute volume: system volume ratio * user-configured in-app sound volume
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_SYSTEM).toFloat()
        val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_SYSTEM).toFloat()
        val systemRatio = if (maxVol > 0f) currentVol / maxVol else 0.5f

        val effectiveVolume = (systemRatio * settingsCache.soundVolume).coerceIn(0f, 1f)
        if (effectiveVolume <= 0.001f) return

        val sampleId = when (keyCode) {
            MyKeyboard.KEYCODE_DELETE -> if (soundDeleteId != 0) soundDeleteId else soundStandardId
            MyKeyboard.KEYCODE_SPACE -> if (soundSpaceId != 0) soundSpaceId else soundStandardId
            MyKeyboard.KEYCODE_ENTER -> if (soundReturnId != 0) soundReturnId else soundStandardId
            else -> soundStandardId
        }

        val pool = soundPool
        if (pool != null && sampleId != 0) {
            pool.play(sampleId, effectiveVolume, effectiveVolume, 1, 0, 1.0f)
        } else {
            // Fallback to AudioManager system sound effects if SoundPool failed
            val effect = when (keyCode) {
                MyKeyboard.KEYCODE_DELETE -> AudioManager.FX_KEYPRESS_DELETE
                MyKeyboard.KEYCODE_ENTER -> AudioManager.FX_KEYPRESS_RETURN
                MyKeyboard.KEYCODE_SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
                else -> AudioManager.FX_KEYPRESS_STANDARD
            }
            audioManager.playSoundEffect(effect, effectiveVolume)
        }
    }

    /**
     * Performs haptic feedback for cursor handle movement.
     */
    fun performHapticHandleMove(view: View) {
        if (!settingsCache.vibrateOnKeypress) return
        if (!isSystemHapticFeedbackEnabled()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            @Suppress("DEPRECATION")
            view.performHapticFeedback(
                HapticFeedbackConstants.TEXT_HANDLE_MOVE,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
            )
        }
    }

    /**
     * Executes haptic then sound feedback in strict sequential order.
     * Visual pressed state must already be set prior to calling this method.
     */
    fun performKeypressFeedback(view: View, keyCode: Int) {
        performHaptic(view)
        performSound(keyCode)
    }

    /**
     * Releases SoundPool resources on keyboard destruction.
     */
    fun release() {
        soundPool?.release()
        soundPool = null
    }
}
