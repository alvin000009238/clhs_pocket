package com.clhs.score.shared.repo

/** In-memory stores with switchable failures, to prove that storage errors reach the caller. */
internal class FakeSecureStore : SecureStore {
    val values = HashMap<String, String>()
    var failRead = false
    var failWrite = false
    var failDelete = false
    /** Runs inside the next read or write, to interleave another operation with it. */
    var onRead: (() -> Unit)? = null
    var onWrite: (() -> Unit)? = null

    override fun read(key: String): StoreResult {
        onRead?.also { onRead = null }?.invoke()
        return if (failRead) StoreResult.failure("read") else values[key]?.let { StoreResult.value(it, 0L) } ?: StoreResult.ok()
    }

    override fun write(key: String, value: String): StoreResult {
        onWrite?.also { onWrite = null }?.invoke()
        if (failWrite) return StoreResult.failure("write")
        values[key] = value
        return StoreResult.ok()
    }

    override fun delete(key: String): StoreResult {
        if (failDelete) return StoreResult.failure("delete")
        values.remove(key)
        return StoreResult.ok()
    }
}

internal class FakeFileStore : FileStore {
    val files = HashMap<String, Pair<String, Long>>()
    /** Paths starting with one of these prefixes fail the matching operation. */
    val failWrites = HashSet<String>()
    val failDeletes = HashSet<String>()
    val failReads = HashSet<String>()
    /** The next write below one of these prefixes stores the file and then reports failure. */
    val partialWriteOnce = HashSet<String>()
    /** Runs inside the next write whose path starts with the key, before the file is stored. */
    val onWrite = HashMap<String, () -> Unit>()

    private fun Set<String>.matches(path: String) = any { path.startsWith(it) }

    override fun read(path: String): StoreResult =
        if (failReads.matches(path)) StoreResult.failure("read")
        else files[path]?.let { StoreResult.value(it.first, it.second) } ?: StoreResult.ok()

    override fun write(path: String, text: String, modifiedAtEpochMillis: Long): StoreResult {
        onWrite.keys.firstOrNull { path.startsWith(it) }?.let { onWrite.remove(it)?.invoke() }
        partialWriteOnce.firstOrNull { path.startsWith(it) }?.let { prefix ->
            partialWriteOnce.remove(prefix)
            files[path] = text to modifiedAtEpochMillis
            return StoreResult.failure("attributes")
        }
        if (failWrites.matches(path)) return StoreResult.failure("write")
        files[path] = text to modifiedAtEpochMillis
        return StoreResult.ok()
    }

    override fun delete(path: String): StoreResult {
        if (failDeletes.matches(path)) return StoreResult.failure("delete")
        files.remove(path)
        return StoreResult.ok()
    }

    override fun deleteTree(directory: String): StoreResult {
        if (failDeletes.matches(directory)) return StoreResult.failure("delete")
        files.keys.filter { it.startsWith("$directory/") }.forEach(files::remove)
        return StoreResult.ok()
    }

    fun area(name: String): Set<String> = files.keys.filterTo(HashSet()) { it.startsWith("$name/") }
}
