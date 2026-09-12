package com.clhs.score.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class EncryptedPayload(
    val version: Int,
    val keyVersion: Int,
    val iv: ByteArray,
    val ciphertext: ByteArray,
) {
    fun copy(
        version: Int = this.version,
        keyVersion: Int = this.keyVersion,
        iv: ByteArray = this.iv,
        ciphertext: ByteArray = this.ciphertext,
    ): EncryptedPayload = EncryptedPayload(version, keyVersion, iv, ciphertext)

    override fun equals(other: Any?): Boolean =
        other is EncryptedPayload &&
            version == other.version &&
            keyVersion == other.keyVersion &&
            iv.contentEquals(other.iv) &&
            ciphertext.contentEquals(other.ciphertext)

    override fun hashCode(): Int =
        (((version * 31 + keyVersion) * 31 + iv.contentHashCode()) * 31 + ciphertext.contentHashCode())
}

internal interface SessionCipher {
    suspend fun encrypt(plaintext: ByteArray, associatedData: ByteArray): EncryptedPayload
    suspend fun decrypt(payload: EncryptedPayload, associatedData: ByteArray): ByteArray
}

internal interface SessionKeyProvider {
    fun getOrCreateEncryptionKey(version: Int): SecretKey
    fun getDecryptionKey(version: Int): SecretKey
}

internal open class SessionStorageException(message: String, cause: Throwable? = null) : Exception(message, cause)

internal open class SessionCorruptedException(message: String, cause: Throwable? = null) :
    SessionStorageException(message, cause)

internal class UnsupportedSessionPayloadException(message: String) : SessionCorruptedException(message)

internal class SessionKeyUnavailableException(cause: Throwable? = null) :
    SessionStorageException("Session encryption key is unavailable", cause)

internal class SessionStorageUnavailableException(cause: Throwable? = null) :
    SessionStorageException("Session storage is unavailable", cause)

internal class AesGcmSessionCipher(
    private val keyProvider: SessionKeyProvider,
) : SessionCipher {
    override suspend fun encrypt(
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): EncryptedPayload = withContext(Dispatchers.IO) {
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, keyProvider.getOrCreateEncryptionKey(CURRENT_KEY_VERSION))
            cipher.updateAAD(associatedData)
            EncryptedPayload(
                version = CURRENT_PAYLOAD_VERSION,
                keyVersion = CURRENT_KEY_VERSION,
                iv = cipher.iv,
                ciphertext = cipher.doFinal(plaintext),
            )
        } catch (error: SessionStorageException) {
            throw error
        } catch (error: GeneralSecurityException) {
            throw SessionKeyUnavailableException(error)
        }
    }

    override suspend fun decrypt(
        payload: EncryptedPayload,
        associatedData: ByteArray,
    ): ByteArray = withContext(Dispatchers.IO) {
        validate(payload)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                keyProvider.getDecryptionKey(payload.keyVersion),
                GCMParameterSpec(TAG_LENGTH_BITS, payload.iv),
            )
            cipher.updateAAD(associatedData)
            cipher.doFinal(payload.ciphertext)
        } catch (error: AEADBadTagException) {
            throw SessionCorruptedException("Session authentication failed", error)
        } catch (error: SessionStorageException) {
            throw error
        } catch (error: GeneralSecurityException) {
            throw SessionKeyUnavailableException(error)
        }
    }

    private fun validate(payload: EncryptedPayload) {
        if (payload.version != CURRENT_PAYLOAD_VERSION) {
            throw UnsupportedSessionPayloadException("Unsupported session payload version")
        }
        if (payload.keyVersion != CURRENT_KEY_VERSION) {
            throw UnsupportedSessionPayloadException("Unsupported session key version")
        }
        if (payload.iv.size != IV_SIZE_BYTES || payload.ciphertext.size < TAG_SIZE_BYTES) {
            throw SessionCorruptedException("Malformed encrypted session payload")
        }
    }

    internal companion object {
        const val CURRENT_PAYLOAD_VERSION = 1
        const val CURRENT_KEY_VERSION = 1
        const val IV_SIZE_BYTES = 12
        const val TAG_SIZE_BYTES = 16
        const val TAG_LENGTH_BITS = TAG_SIZE_BYTES * 8
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

internal class AndroidKeystoreSessionKeyProvider(
    private val aliasPrefix: String = "clhs_session_key_v",
) : SessionKeyProvider {
    override fun getOrCreateEncryptionKey(version: Int): SecretKey {
        requireSupported(version)
        return try {
            val keyStore = keyStore()
            (keyStore.getKey(alias(version), null) as? SecretKey)?.let { return it }
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEY_PROVIDER).run {
                init(
                    KeyGenParameterSpec.Builder(
                        alias(version),
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setKeySize(256)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build(),
                )
                generateKey()
            }
        } catch (error: SessionStorageException) {
            throw error
        } catch (error: GeneralSecurityException) {
            throw SessionKeyUnavailableException(error)
        }
    }

    override fun getDecryptionKey(version: Int): SecretKey {
        requireSupported(version)
        return try {
            keyStore().getKey(alias(version), null) as? SecretKey
                ?: throw SessionKeyUnavailableException()
        } catch (error: SessionStorageException) {
            throw error
        } catch (error: GeneralSecurityException) {
            throw SessionKeyUnavailableException(error)
        }
    }

    private fun keyStore(): KeyStore = try {
        KeyStore.getInstance(KEY_PROVIDER).apply { load(null) }
    } catch (error: GeneralSecurityException) {
        throw SessionKeyUnavailableException(error)
    } catch (error: IOException) {
        throw SessionKeyUnavailableException(error)
    }

    private fun requireSupported(version: Int) {
        if (version != AesGcmSessionCipher.CURRENT_KEY_VERSION) {
            throw UnsupportedSessionPayloadException("Unsupported session key version")
        }
    }

    private fun alias(version: Int): String = "$aliasPrefix$version"

    private companion object {
        const val KEY_PROVIDER = "AndroidKeyStore"
    }
}
