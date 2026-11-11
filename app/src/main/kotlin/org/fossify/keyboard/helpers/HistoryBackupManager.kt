package org.fossify.keyboard.helpers

import android.content.Context
import android.util.Base64
import com.google.gson.Gson
import org.fossify.keyboard.databases.InputHistoryDatabase
import org.fossify.keyboard.models.DecryptedBackupPayload
import org.fossify.keyboard.models.EncryptedBackupContainer
import org.fossify.keyboard.models.SessionWithDeletions
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object HistoryBackupManager {

    const val BACKUP_FORMAT = "fossify_input_history_encrypted"
    const val CURRENT_SCHEMA_VERSION = 1
    const val KDF_ALGORITHM = "PBKDF2WithHmacSHA256"
    const val PBKDF2_ITERATIONS = 100000
    const val KEY_LENGTH_BITS = 256
    const val SALT_LENGTH_BYTES = 16
    const val GCM_IV_LENGTH_BYTES = 12
    const val GCM_TAG_LENGTH_BITS = 128

    private val gson = Gson()

    fun exportEncryptedHistory(
        context: Context,
        outputStream: OutputStream,
        password: String
    ): Int {
        val db = InputHistoryDatabase.getInstance(context)
        val dao = db.inputHistoryDao()
        val deletionDao = db.deletionEventDao()

        val allSessions = dao.getAllSessionsList()
        if (allSessions.isEmpty()) {
            return 0
        }

        val sessionsWithDeletions = allSessions.map { session ->
            val deletions = deletionDao.getDeletionsForSession(session.id)
            SessionWithDeletions(session, deletions)
        }

        val payload = DecryptedBackupPayload(
            schema_version = CURRENT_SCHEMA_VERSION,
            exported_at = System.currentTimeMillis(),
            sessions = sessionsWithDeletions
        )
        val payloadJson = gson.toJson(payload)
        val plaintextBytes = payloadJson.toByteArray(Charsets.UTF_8)

        // 1. Generate random salt and IV
        val random = SecureRandom()
        val salt = ByteArray(SALT_LENGTH_BYTES).also { random.nextBytes(it) }
        val iv = ByteArray(GCM_IV_LENGTH_BYTES).also { random.nextBytes(it) }

        // 2. Derive 256-bit AES key using PBKDF2 with >= 100,000 iterations
        val keySpec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        val keyFactory = SecretKeyFactory.getInstance(KDF_ALGORITHM)
        val keyBytes = keyFactory.generateSecret(keySpec).encoded
        val secretKey = SecretKeySpec(keyBytes, "AES")

        // 3. Encrypt using AES-256-GCM
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec)
        val ciphertext = cipher.doFinal(plaintextBytes)

        // 4. Package container
        val container = EncryptedBackupContainer(
            format = BACKUP_FORMAT,
            version = CURRENT_SCHEMA_VERSION,
            kdf = KDF_ALGORITHM,
            iterations = PBKDF2_ITERATIONS,
            salt = Base64.encodeToString(salt, Base64.NO_WRAP),
            iv = Base64.encodeToString(iv, Base64.NO_WRAP),
            ciphertext = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        )

        val containerJson = gson.toJson(container)
        outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write(containerJson)
        }

        return sessionsWithDeletions.size
    }

    fun importEncryptedHistory(
        context: Context,
        inputStream: InputStream,
        password: String
    ): ImportResult {
        val containerJson = inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }

        val container = try {
            gson.fromJson(containerJson, EncryptedBackupContainer::class.java)
        } catch (e: Exception) {
            return ImportResult.Error("Invalid file structure. Not a valid backup.")
        }

        if (container.format != BACKUP_FORMAT) {
            return ImportResult.Error("Unsupported backup format: ${container.format}")
        }

        if (container.version > CURRENT_SCHEMA_VERSION) {
            return ImportResult.Error("Unsupported backup version: ${container.version}. Please update the application.")
        }

        val salt = try {
            Base64.decode(container.salt, Base64.NO_WRAP)
        } catch (e: Exception) {
            return ImportResult.Error("Corrupted backup file: invalid salt")
        }

        val iv = try {
            Base64.decode(container.iv, Base64.NO_WRAP)
        } catch (e: Exception) {
            return ImportResult.Error("Corrupted backup file: invalid IV")
        }

        val ciphertext = try {
            Base64.decode(container.ciphertext, Base64.NO_WRAP)
        } catch (e: Exception) {
            return ImportResult.Error("Corrupted backup file: invalid ciphertext")
        }

        val iterations = container.iterations.coerceAtLeast(100000)

        // 1. Derive key with salt and iterations
        val keySpec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_LENGTH_BITS)
        val keyFactory = SecretKeyFactory.getInstance(container.kdf.ifEmpty { KDF_ALGORITHM })
        val keyBytes = keyFactory.generateSecret(keySpec).encoded
        val secretKey = SecretKeySpec(keyBytes, "AES")

        // 2. Decrypt with AES-GCM
        val decryptedBytes = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)
            cipher.doFinal(ciphertext)
        } catch (e: AEADBadTagException) {
            return ImportResult.Error("Incorrect password or corrupted backup file.")
        } catch (e: Exception) {
            return ImportResult.Error("Decryption failed: ${e.localizedMessage ?: "Unknown error"}")
        }

        val payloadJson = String(decryptedBytes, Charsets.UTF_8)
        val payload = try {
            gson.fromJson(payloadJson, DecryptedBackupPayload::class.java)
        } catch (e: Exception) {
            return ImportResult.Error("Invalid payload schema.")
        }

        if (payload.schema_version > CURRENT_SCHEMA_VERSION) {
            return ImportResult.Error("Payload schema version ${payload.schema_version} is not supported.")
        }

        // 3. Merge records into local database, deduping by session ID
        val db = InputHistoryDatabase.getInstance(context)
        val dao = db.inputHistoryDao()
        val deletionDao = db.deletionEventDao()

        var mergedCount = 0
        db.runInTransaction {
            for (item in payload.sessions) {
                val session = item.session
                val deletions = item.deletions

                // Insert or replace session by ID
                dao.insertSession(session)

                // Replace / merge deletions for this session ID
                if (deletions.isNotEmpty()) {
                    deletionDao.insertAll(deletions)
                }
                mergedCount++
            }
        }

        return ImportResult.Success(mergedCount)
    }

    sealed class ImportResult {
        data class Success(val importedCount: Int) : ImportResult()
        data class Error(val message: String) : ImportResult()
    }
}
