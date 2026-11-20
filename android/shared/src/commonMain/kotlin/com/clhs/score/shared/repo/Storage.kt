package com.clhs.score.shared.repo

/**
 * Outcome of one platform storage call. Platform code reports failures as values, never by throwing,
 * so a failed write or delete always reaches shared code and can never look like success.
 */
class StoreResult private constructor(
    val succeeded: Boolean,
    /** Stored text for a successful read; null when the entry does not exist. */
    val text: String?,
    val modifiedAtEpochMillis: Long,
    val errorMessage: String?,
) {
    companion object {
        fun ok(): StoreResult = StoreResult(true, null, 0L, null)
        fun value(text: String, modifiedAtEpochMillis: Long): StoreResult = StoreResult(true, text, modifiedAtEpochMillis, null)
        fun failure(message: String): StoreResult = StoreResult(false, null, 0L, message)
    }
}

/** Device-only secret storage (iOS Keychain). Values never sync and never go into backups. */
interface SecureStore {
    fun read(key: String): StoreResult
    fun write(key: String, value: String): StoreResult
    /** Deleting a missing key succeeds. */
    fun delete(key: String): StoreResult
}

/**
 * Text files below an app-private root. The first path segment selects the protection level on the
 * platform: [PRIVATE] holds school data, [PUBLIC] holds public caches, [SETTINGS] holds preferences.
 */
interface FileStore {
    fun read(path: String): StoreResult
    fun write(path: String, text: String, modifiedAtEpochMillis: Long): StoreResult
    /** Deleting a missing file succeeds. */
    fun delete(path: String): StoreResult
    /** Deletes a directory with everything below it; a missing directory succeeds. */
    fun deleteTree(directory: String): StoreResult

    companion object {
        const val PRIVATE = "private"
        const val PUBLIC = "public"
        const val SETTINGS = "settings"
    }
}

open class StorageException(message: String) : Exception(message)

/** The settings file exists but cannot be decoded or holds values no screen can produce. */
class SettingsUnreadableException : StorageException("Settings are unreadable")

internal class StoredText(val text: String, val modifiedAtEpochMillis: Long)

internal fun StoreResult.orThrow(action: String): StoreResult {
    if (!succeeded) throw StorageException("$action failed")
    return this
}

internal fun FileStore.readOrNull(path: String): StoredText? =
    read(path).orThrow("Read").let { result -> result.text?.let { StoredText(it, result.modifiedAtEpochMillis) } }

internal fun SecureStore.readOrNull(key: String): String? = read(key).orThrow("Secure read").text

/** Hex of the UTF-8 bytes: a reversible, path-safe file name component for short keys. */
internal fun fileKey(value: String): String {
    val bytes = value.encodeToByteArray()
    require(bytes.size in 1..96) { "Cache key is too long" }
    return bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}

/** Fixed-length, path-safe name for keys of any length: two independent 64-bit FNV-1a hashes in hex. */
internal fun hashedFileKey(value: String): String {
    val bytes = value.encodeToByteArray()
    fun fnv(seed: ULong): String {
        var hash = seed
        for (byte in bytes) {
            hash = hash xor (byte.toUByte().toULong())
            hash *= 0x100000001b3UL
        }
        return hash.toString(16).padStart(16, '0')
    }
    return fnv(0xcbf29ce484222325UL) + fnv(0x84222325cbf29ce4UL)
}
