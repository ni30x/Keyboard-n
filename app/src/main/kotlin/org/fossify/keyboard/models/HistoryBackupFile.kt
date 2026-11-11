package org.fossify.keyboard.models

data class EncryptedBackupContainer(
    val format: String = "fossify_input_history_encrypted",
    val version: Int = 1,
    val kdf: String = "PBKDF2WithHmacSHA256",
    val iterations: Int = 100000,
    val salt: String,
    val iv: String,
    val ciphertext: String
)

data class DecryptedBackupPayload(
    val schema_version: Int = 1,
    val exported_at: Long = System.currentTimeMillis(),
    val sessions: List<SessionWithDeletions>
)
