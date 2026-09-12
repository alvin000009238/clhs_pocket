package com.clhs.score.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException

private const val MAX_CWA_API_KEY_BYTES = 256

internal class CwaApiKeyStore internal constructor(
    private val file: File,
    private val cipher: SessionCipher,
) {
    constructor(context: Context) : this(
        File(context.noBackupFilesDir, FILE_NAME),
        AesGcmSessionCipher(AndroidKeystoreSessionKeyProvider("clhs_weather_credential_key_v")),
    )
    private val mutex = Mutex()

    suspend fun hasKey(): Boolean = mutex.withLock { file.isFile }

    suspend fun load(): String? = mutex.withLock {
        if (!file.isFile) return@withLock null
        val payload = withContext(Dispatchers.IO) { decode(file.readBytes()) }
        cipher.decrypt(payload, AAD).decodeToString().takeIf(String::isNotBlank)
    }

    suspend fun save(rawKey: String): Unit = mutex.withLock {
        val key = validateCwaApiKey(rawKey)
        val payload = cipher.encrypt(key.encodeToByteArray(), AAD)
        withContext(Dispatchers.IO) {
            val directory = requireNotNull(file.parentFile)
            directory.mkdirs()
            val temporary = Files.createTempFile(directory.toPath(), "$FILE_NAME-", ".tmp")
            try {
                temporary.toFile().writeBytes(encode(payload))
                try {
                    Files.move(
                        temporary,
                        file.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary, file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(temporary)
            }
        }
    }

    suspend fun clear() = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (file.exists() && !file.delete()) error("無法刪除中央氣象署授權碼")
        }
    }

    private fun encode(payload: EncryptedPayload): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(payload.version)
            output.writeInt(payload.keyVersion)
            output.writeInt(payload.iv.size)
            output.write(payload.iv)
            output.writeInt(payload.ciphertext.size)
            output.write(payload.ciphertext)
        }
        bytes.toByteArray()
    }

    private fun decode(bytes: ByteArray): EncryptedPayload {
        require(bytes.size in 40..MAX_FILE_BYTES) { "Invalid credential file" }
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val version = input.readInt()
            val keyVersion = input.readInt()
            val ivSize = input.readInt()
            require(ivSize == AesGcmSessionCipher.IV_SIZE_BYTES)
            val iv = ByteArray(ivSize).also(input::readFully)
            val ciphertextSize = input.readInt()
            require(ciphertextSize in AesGcmSessionCipher.TAG_SIZE_BYTES..MAX_CWA_API_KEY_BYTES + 32)
            val ciphertext = ByteArray(ciphertextSize).also(input::readFully)
            require(input.read() == -1)
            EncryptedPayload(version, keyVersion, iv, ciphertext)
        }
    }

    private companion object {
        const val FILE_NAME = "weather_credentials.pb"
        const val MAX_FILE_BYTES = 512
        val AAD = "clhs-pocket:cwa-api-key:v1".encodeToByteArray()
    }
}

internal fun validateCwaApiKey(rawKey: String): String {
    val key = rawKey.trim()
    require(rawKey.none { Character.isISOControl(it) }) {
        "請輸入有效的中央氣象署授權碼"
    }
    require(key.isNotEmpty() && key.encodeToByteArray().size <= MAX_CWA_API_KEY_BYTES) {
        "請輸入有效的中央氣象署授權碼"
    }
    return key
}
