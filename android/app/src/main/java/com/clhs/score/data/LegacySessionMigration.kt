package com.clhs.score.data

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import java.security.GeneralSecurityException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher

internal data class BiometricSessionRecord(
    val sessionCiphertext: String,
    val sessionIv: String,
    val sessionSalt: String,
    val pinCiphertext: String,
    val pinIv: String,
)

internal interface LegacySessionSource {
    fun clearGeneral()
    fun clearReminder()
    fun clearBiometric()
    fun clearAll()
}

internal class LegacySessionPreferences(
    private val deletePreferences: () -> Boolean,
) : LegacySessionSource {
    constructor(context: Context) : this({ context.applicationContext.deleteSharedPreferences("score_session") })

    // No legacy credential is supported, so every cleanup deletes the entire obsolete store.
    override fun clearGeneral() = clearAll()
    override fun clearReminder() = clearAll()
    override fun clearBiometric() = clearAll()

    override fun clearAll() {
        val deleted = try {
            deletePreferences()
        } catch (_: SecurityException) {
            false
        }
        if (!deleted) throw SessionCleanupException(setOf(SessionCleanupFailure.LEGACY_SESSION_DELETE_FAILED))
    }
}

internal interface BiometricSessionStorage {
    fun save(session: AuthenticatedSession, pin: String, cipher: Cipher)
    fun load(cipher: Cipher): AuthenticatedSession?
    fun loadWithPin(pin: String): AuthenticatedSession?
    fun hasSession(): Boolean
    fun pinIv(): ByteArray?
    fun clear()
}

@SuppressLint("UseKtx") // Privacy writes must report commit failure.
internal class SharedPreferencesBiometricSessionStorage(
    context: Context,
    private val legacySource: LegacySessionSource,
    private val deleteBiometricKey: () -> Unit = BiometricHelper::deleteSecretKey,
) : BiometricSessionStorage {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()
    private val secureRandom = java.security.SecureRandom()

    override fun save(session: AuthenticatedSession, pin: String, cipher: Cipher) = synchronized(lock) {
        val salt = ByteArray(16).also(secureRandom::nextBytes)
        val encryptedSession = BiometricHelper.encryptWithPin(session, pin, salt)
        val encryptedPin = BiometricHelper.encryptPin(pin, cipher)
        write(
            BiometricSessionRecord(
                sessionCiphertext = encryptedSession.cipherTextBase64,
                sessionIv = encryptedSession.ivBase64,
                sessionSalt = Base64.encodeToString(salt, Base64.NO_WRAP),
                pinCiphertext = encryptedPin.cipherTextBase64,
                pinIv = encryptedPin.ivBase64,
            ),
        )
    }

    override fun load(cipher: Cipher): AuthenticatedSession? = synchronized(lock) {
        val record = readCurrent() ?: return@synchronized null
        try {
            val pin = BiometricHelper.decryptPin(record.pinCiphertext, cipher)
            decryptSession(record, pin)
        } catch (error: AEADBadTagException) {
            throw SessionCorruptedException("Biometric session authentication failed", error)
        } catch (error: GeneralSecurityException) {
            throw SessionCorruptedException("Biometric session cannot be decrypted", error)
        } catch (error: IllegalArgumentException) {
            throw SessionCorruptedException("Biometric session is malformed", error)
        }
    }

    override fun loadWithPin(pin: String): AuthenticatedSession? = synchronized(lock) {
        val record = readCurrent() ?: return@synchronized null
        try {
            decryptSession(record, pin)
        } catch (_: AEADBadTagException) {
            null
        } catch (error: GeneralSecurityException) {
            throw SessionCorruptedException("Biometric session cannot be decrypted", error)
        } catch (error: IllegalArgumentException) {
            throw SessionCorruptedException("Biometric session is malformed", error)
        }
    }

    override fun hasSession(): Boolean = synchronized(lock) { readCurrent() != null }

    override fun pinIv(): ByteArray? = synchronized(lock) {
        readCurrent()?.let {
            try {
                Base64.decode(it.pinIv, Base64.NO_WRAP)
            } catch (error: IllegalArgumentException) {
                throw SessionCorruptedException("Biometric IV is malformed", error)
            }
        }
    }

    override fun clear() {
        synchronized(lock) {
            val failures = linkedSetOf<SessionCleanupFailure>()
            if (!prefs.edit().clear().commit()) {
                failures += SessionCleanupFailure.BIOMETRIC_DELETE_FAILED
            }
            try {
                legacySource.clearBiometric()
            } catch (_: SessionStorageException) {
                failures += SessionCleanupFailure.LEGACY_SESSION_DELETE_FAILED
            }
            try {
                deleteBiometricKey()
            } catch (_: GeneralSecurityException) {
                failures += SessionCleanupFailure.BIOMETRIC_KEY_DELETE_FAILED
            } catch (_: java.io.IOException) {
                failures += SessionCleanupFailure.BIOMETRIC_KEY_DELETE_FAILED
            }
            if (failures.isNotEmpty()) throw SessionCleanupException(failures)
        }
    }

    private fun decryptSession(record: BiometricSessionRecord, pin: String): AuthenticatedSession {
        val salt = Base64.decode(record.sessionSalt, Base64.NO_WRAP)
        return BiometricHelper.decryptWithPin(
            record.sessionCiphertext,
            record.sessionIv,
            pin,
            salt,
        )
    }

    private fun readCurrent(): BiometricSessionRecord? {
        val values = KEYS.map { prefs.getString(it, null) }
        if (values.all { it == null }) return null
        if (values.any { it.isNullOrBlank() }) throw SessionCorruptedException("Biometric session is incomplete")
        return BiometricSessionRecord(
            sessionCiphertext = values[0]!!,
            sessionIv = values[1]!!,
            sessionSalt = values[2]!!,
            pinCiphertext = values[3]!!,
            pinIv = values[4]!!,
        )
    }

    private fun write(record: BiometricSessionRecord) {
        val editor = prefs.edit()
            .putString(KEY_SESSION_CIPHER_TEXT, record.sessionCiphertext)
            .putString(KEY_SESSION_IV, record.sessionIv)
            .putString(KEY_SESSION_SALT, record.sessionSalt)
            .putString(KEY_PIN_CIPHER_TEXT, record.pinCiphertext)
            .putString(KEY_PIN_IV, record.pinIv)
        if (!editor.commit()) throw SessionStorageUnavailableException()
    }

    private companion object {
        const val PREFS_NAME = "score_biometric_session"
        const val KEY_SESSION_CIPHER_TEXT = "session_ciphertext"
        const val KEY_SESSION_IV = "session_iv"
        const val KEY_SESSION_SALT = "session_salt"
        const val KEY_PIN_CIPHER_TEXT = "pin_ciphertext"
        const val KEY_PIN_IV = "pin_iv"
        val KEYS = listOf(
            KEY_SESSION_CIPHER_TEXT,
            KEY_SESSION_IV,
            KEY_SESSION_SALT,
            KEY_PIN_CIPHER_TEXT,
            KEY_PIN_IV,
        )
    }
}
