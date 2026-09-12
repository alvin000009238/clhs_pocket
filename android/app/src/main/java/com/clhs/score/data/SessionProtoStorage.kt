package com.clhs.score.data

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.core.Serializer
import androidx.datastore.dataStore
import com.clhs.score.data.proto.EncryptedSessionPayload
import com.clhs.score.data.proto.SessionStorage
import com.google.protobuf.ByteString
import com.google.protobuf.InvalidProtocolBufferException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import java.io.InputStream
import java.io.OutputStream

internal const val SESSION_DATASTORE_FILE = "session_storage.pb"

internal val Context.sessionDataStore: DataStore<SessionStorage> by dataStore(
    fileName = SESSION_DATASTORE_FILE,
    serializer = SessionStorageSerializer,
    corruptionHandler = ReplaceFileCorruptionHandler {
        SessionStorage.getDefaultInstance()
    },
)

internal object SessionStorageSerializer : Serializer<SessionStorage> {
    override val defaultValue: SessionStorage = SessionStorage.getDefaultInstance()

    override suspend fun readFrom(input: InputStream): SessionStorage = try {
        SessionStorage.parseFrom(input)
    } catch (error: InvalidProtocolBufferException) {
        throw CorruptionException("Cannot read encrypted session storage", error)
    }

    override suspend fun writeTo(t: SessionStorage, output: OutputStream) {
        t.writeTo(output)
    }
}

@Serializable
private data class SerializedSession(
    val version: Int,
    val session: AuthenticatedSession,
    val expiresAtMillis: Long? = null,
)

internal data class DecodedSession(
    val session: AuthenticatedSession,
    val expiresAtMillis: Long?,
)

internal object SessionSerializer {
    private const val CURRENT_VERSION = 1

    fun serialize(session: AuthenticatedSession, expiresAtMillis: Long? = null): ByteArray {
        validate(session)
        if (expiresAtMillis != null && expiresAtMillis <= 0L) {
            throw IllegalArgumentException("Reminder expiry must be positive")
        }
        return SchoolJson.encodeToString(
            SerializedSession(
                version = CURRENT_VERSION,
                session = session,
                expiresAtMillis = expiresAtMillis,
            ),
        ).encodeToByteArray()
    }

    fun deserialize(bytes: ByteArray): DecodedSession {
        val stored = try {
            SchoolJson.decodeFromString<SerializedSession>(bytes.decodeToString())
        } catch (error: SerializationException) {
            throw SessionCorruptedException("Malformed session plaintext", error)
        } catch (error: IllegalArgumentException) {
            throw SessionCorruptedException("Malformed session plaintext", error)
        }
        if (stored.version != CURRENT_VERSION) {
            throw UnsupportedSessionPayloadException("Unsupported serialized session version")
        }
        validate(stored.session)
        return DecodedSession(stored.session, stored.expiresAtMillis)
    }

    private fun validate(session: AuthenticatedSession) {
        if (session.studentNo.isBlank() || session.apiToken.isBlank() ||
            session.cookies.isEmpty() || session.cookies.any { (name, value) -> name.isBlank() || value.isBlank() }
        ) {
            throw SessionCorruptedException("Session fields are incomplete")
        }
    }
}

internal fun EncryptedPayload.toProto(): EncryptedSessionPayload =
    EncryptedSessionPayload.newBuilder()
        .setVersion(version)
        .setKeyVersion(keyVersion)
        .setIv(ByteString.copyFrom(iv))
        .setCiphertext(ByteString.copyFrom(ciphertext))
        .build()

internal fun EncryptedSessionPayload.toDomain(): EncryptedPayload = EncryptedPayload(
    version = this.version,
    keyVersion = this.keyVersion,
    iv = this.iv.toByteArray(),
    ciphertext = this.ciphertext.toByteArray(),
)
