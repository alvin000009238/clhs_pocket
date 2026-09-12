package com.clhs.score.data

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.biometric.BiometricManager
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object BiometricHelper {
    private const val KEY_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "CLHSBiometricSessionKey"
    private const val TRANSFORMATION = "${KeyProperties.KEY_ALGORITHM_AES}/${KeyProperties.BLOCK_MODE_GCM}/${KeyProperties.ENCRYPTION_PADDING_NONE}"
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val PIN_DERIVATION_ITERATIONS = 100_000
    private const val PIN_KEY_SIZE_BITS = 256

    const val STRONG_BIOMETRIC_AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_STRONG

    fun canAuthenticate(context: Context): Boolean {
        val biometricManager = BiometricManager.from(context)
        return biometricManager.canAuthenticate(STRONG_BIOMETRIC_AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS
    }

    private fun getSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEY_PROVIDER).apply { load(null) }
        val existingKey = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existingKey != null) {
            return existingKey
        }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEY_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setUserAuthenticationParameters(
                        0,
                        KeyProperties.AUTH_BIOMETRIC_STRONG,
                    )
                } else {
                    @Suppress("DEPRECATION")
                    setUserAuthenticationValidityDurationSeconds(-1)
                }
            }
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    fun deleteSecretKey() {
        val keyStore = KeyStore.getInstance(KEY_PROVIDER).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) {
            keyStore.deleteEntry(KEY_ALIAS)
        }
    }

    fun getEncryptCipher(): Cipher {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        return try {
            cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())
            cipher
        } catch (error: Exception) {
            if (!isKeyPermanentlyInvalidated(error)) {
                throw error
            }
            deleteSecretKey()
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, getSecretKey())
            }
        }
    }

    fun getDecryptCipher(iv: ByteArray): Cipher {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), spec)
        return cipher
    }

    fun deriveKeyFromPin(pin: String, salt: ByteArray): SecretKey {
        val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = javax.crypto.spec.PBEKeySpec(
            pin.toCharArray(),
            salt,
            PIN_DERIVATION_ITERATIONS,
            PIN_KEY_SIZE_BITS,
        )
        return try {
            javax.crypto.spec.SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    fun encryptWithPin(session: AuthenticatedSession, pin: String, salt: ByteArray): EncryptedData {
        val key = deriveKeyFromPin(pin, salt)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val json = SchoolJson.encodeToString(session)
        val cipherText = cipher.doFinal(json.toByteArray(Charsets.UTF_8))
        return EncryptedData(
            cipherTextBase64 = Base64.encodeToString(cipherText, Base64.NO_WRAP),
            ivBase64 = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        )
    }

    fun decryptWithPin(encryptedTextBase64: String, ivBase64: String, pin: String, salt: ByteArray): AuthenticatedSession {
        val key = deriveKeyFromPin(pin, salt)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)
        
        val cipherText = Base64.decode(encryptedTextBase64, Base64.NO_WRAP)
        val plainTextBytes = cipher.doFinal(cipherText)
        val json = String(plainTextBytes, Charsets.UTF_8)
        return SchoolJson.decodeFromString(json)
    }

    fun encryptPin(pin: String, cipher: Cipher): EncryptedData {
        val cipherText = cipher.doFinal(pin.toByteArray(Charsets.UTF_8))
        return EncryptedData(
            cipherTextBase64 = Base64.encodeToString(cipherText, Base64.NO_WRAP),
            ivBase64 = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        )
    }

    fun decryptPin(encryptedTextBase64: String, cipher: Cipher): String {
        val cipherText = Base64.decode(encryptedTextBase64, Base64.NO_WRAP)
        val plainTextBytes = cipher.doFinal(cipherText)
        return String(plainTextBytes, Charsets.UTF_8)
    }

    fun isKeyPermanentlyInvalidated(error: Throwable): Boolean {
        var current: Throwable? = error
        repeat(5) {
            when (current) {
                null -> return false
                is KeyPermanentlyInvalidatedException -> return true
            }
            current = current.cause
        }
        return false
    }

    data class EncryptedData(
        val cipherTextBase64: String,
        val ivBase64: String
    )
}
