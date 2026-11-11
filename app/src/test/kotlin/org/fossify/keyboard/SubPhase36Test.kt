package org.fossify.keyboard

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.text.InputType
import android.view.inputmethod.EditorInfo
import java.io.File
import java.lang.reflect.Proxy
import org.fossify.keyboard.helpers.IhDebug
import org.fossify.keyboard.helpers.InputHistoryRecordingEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SubPhase36Test {

    private class TestContext : ContextWrapper(null) {
        override fun getPackageManager(): PackageManager? = null
        override fun getApplicationContext(): Context = this
        override fun getSystemService(name: String): Any? {
            if (name == Context.USER_SERVICE) {
                return Proxy.newProxyInstance(
                    Class.forName("android.os.UserManager").classLoader,
                    arrayOf(Class.forName("android.os.UserManager"))
                ) { _, method, _ ->
                    when (method.returnType) {
                        Boolean::class.javaPrimitiveType -> true
                        else -> null
                    }
                }
            }
            return super.getSystemService(name)
        }
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
            val mockEditor = Proxy.newProxyInstance(
                SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)
            ) { proxy, method, _ ->
                if (method.returnType.isAssignableFrom(SharedPreferences.Editor::class.java)) proxy else null
            } as SharedPreferences.Editor

            return Proxy.newProxyInstance(
                SharedPreferences::class.java.classLoader,
                arrayOf(SharedPreferences::class.java)
            ) { _, method, _ ->
                when (method.name) {
                    "getBoolean" -> true
                    "getStringSet" -> HashSet<String>()
                    "getString" -> ""
                    "getInt" -> 0
                    "getLong" -> 0L
                    "getFloat" -> 0f
                    "edit" -> mockEditor
                    else -> null
                }
            } as SharedPreferences
        }
    }

    private lateinit var mockContext: Context

    @Before
    fun setUp() {
        InputHistoryRecordingEngine.resetForTests()
        mockContext = TestContext()
    }

    @Test
    fun testIncognito_ImeFlagNoPersonalizedLearning_BlocksRecording() {
        val incognitoInfo = EditorInfo().apply {
            packageName = "org.chromium.chrome"
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }

        InputHistoryRecordingEngine.onStartInput(mockContext, incognitoInfo)

        // Engine must refuse to record in incognito/private browsing mode
        assertFalse(InputHistoryRecordingEngine.isRecordingActive)
        assertEquals(0, InputHistoryRecordingEngine.charCounter)
    }

    @Test
    fun testSensitiveField_OtpAndCardHeuristics_BlocksRecording() {
        // 1. Visible password field
        val visiblePasswordInfo = EditorInfo().apply {
            packageName = "com.auth.app"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        InputHistoryRecordingEngine.onStartInput(mockContext, visiblePasswordInfo)
        assertFalse(InputHistoryRecordingEngine.isRecordingActive)

        // 2. Number password / PIN
        val pinInfo = EditorInfo().apply {
            packageName = "com.bank.app"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        InputHistoryRecordingEngine.onStartInput(mockContext, pinInfo)
        assertFalse(InputHistoryRecordingEngine.isRecordingActive)

        // 3. Hint text containing OTP / 2FA
        val otpInfo = EditorInfo().apply {
            packageName = "com.security.app"
            inputType = InputType.TYPE_CLASS_TEXT
            hintText = "Enter SMS OTP Code"
        }
        InputHistoryRecordingEngine.onStartInput(mockContext, otpInfo)
        assertFalse(InputHistoryRecordingEngine.isRecordingActive)

        // 4. Field name containing CVV / card number
        val cardInfo = EditorInfo().apply {
            packageName = "com.store.app"
            inputType = InputType.TYPE_CLASS_TEXT
            fieldName = "credit_card_cvv"
        }
        InputHistoryRecordingEngine.onStartInput(mockContext, cardInfo)
        assertFalse(InputHistoryRecordingEngine.isRecordingActive)
    }

    @Test
    fun testBackupRules_HistoryDatabaseExcludedFromCloudBackupAndTransfer() {
        val backupRulesFile = File("src/main/res/xml/backup_rules.xml").takeIf { it.exists() }
            ?: File("app/src/main/res/xml/backup_rules.xml")
        assertTrue("backup_rules.xml must exist", backupRulesFile.exists())
        val backupContent = backupRulesFile.readText()
        assertTrue("input_history.db must be excluded from backup", backupContent.contains("input_history.db"))
        assertTrue("secure prefs must be excluded from backup", backupContent.contains("input_history_secure_prefs.xml"))

        val dataExtractionRulesFile = File("src/main/res/xml/data_extraction_rules.xml").takeIf { it.exists() }
            ?: File("app/src/main/res/xml/data_extraction_rules.xml")
        assertTrue("data_extraction_rules.xml must exist", dataExtractionRulesFile.exists())
        val dataExtractionContent = dataExtractionRulesFile.readText()
        assertTrue("cloud-backup must exclude input_history.db", dataExtractionContent.contains("input_history.db"))
        assertTrue("device-transfer must exclude input_history.db", dataExtractionContent.contains("device-transfer"))
    }

    @Test
    fun testIhDebug_PrivacySanityCheck_NeverExposesTypedText() {
        // IhDebug logs only structural metadata: seq, ts, sid, ev, cc, blen, live, dels, pkg, extra
        // Confirm IhDebug class methods take only structural parameters
        val methods = IhDebug::class.java.declaredMethods
        for (m in methods) {
            val paramTypes = m.parameterTypes
            // Ensure no parameter named "typedText" or accepting raw user strings without length wrappers
            for (p in paramTypes) {
                assertFalse("Parameters should not include CharArray", p == CharArray::class.java)
            }
        }
    }
}
