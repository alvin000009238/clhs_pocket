package com.clhs.score.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import java.io.IOException

private val announcementBookmarkJson = Json { ignoreUnknownKeys = true }

@Serializable
data class AnnouncementBookmark(
    val id: String,
    val title: String,
    val date: String,
    val category: String,
    val unit: String,
    val issuer: String,
    val isPinned: Boolean = false,
    val contentType: String = "content",
    val externalUrl: String? = null,
) {
    fun toAnnouncement(): SchoolAnnouncement = SchoolAnnouncement(
        id = id,
        title = title,
        date = date,
        category = category,
        unit = unit,
        issuer = issuer,
        isPinned = isPinned,
        contentType = contentType,
        externalUrl = externalUrl?.let(::safeAnnouncementWebUrl),
    )

    companion object {
        fun from(announcement: SchoolAnnouncement) = AnnouncementBookmark(
            id = announcement.id,
            title = announcement.title,
            date = announcement.date,
            category = announcement.category,
            unit = announcement.unit,
            issuer = announcement.issuer,
            isPinned = announcement.isPinned,
            contentType = announcement.contentType,
            externalUrl = announcement.externalUrl?.let(::safeAnnouncementWebUrl),
        )
    }
}

private val Context.announcementBookmarksDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "announcement_bookmarks",
)

class AnnouncementBookmarkRepository internal constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val bookmarks: Flow<List<AnnouncementBookmark>> = dataStore.data.map { preferences ->
        decodeBookmarks(preferences[BOOKMARKS])
    }

    val campusPin: Flow<AnnouncementBookmark?> = dataStore.data.map { preferences ->
        decodeCampusPin(preferences[CAMPUS_PIN])?.takeIf { pin ->
            decodeBookmarks(preferences[BOOKMARKS]).any { it.id == pin.id }
        }
    }

    suspend fun pinToCampus(id: String): Boolean {
        var saved = false
        dataStore.edit { preferences ->
            val bookmark = decodeBookmarks(preferences[BOOKMARKS]).firstOrNull { it.id == id }
            if (bookmark != null) {
                preferences[CAMPUS_PIN] = announcementBookmarkJson.encodeToString(bookmark)
                saved = true
            }
        }
        return saved
    }

    suspend fun removeCampusPin(id: String) {
        dataStore.edit { preferences ->
            if (decodeCampusPin(preferences[CAMPUS_PIN])?.id == id) {
                preferences.remove(CAMPUS_PIN)
            }
        }
    }

    suspend fun toggle(announcement: SchoolAnnouncement): Boolean {
        require(announcement.id.isNotBlank()) { "Announcement id is required" }
        var removedCampusPin = false
        dataStore.edit { preferences ->
            val current = decodeBookmarks(preferences[BOOKMARKS])
            val wasBookmarked = current.any { it.id == announcement.id }
            if (decodeCampusPin(preferences[CAMPUS_PIN])?.id == announcement.id) {
                preferences.remove(CAMPUS_PIN)
                removedCampusPin = wasBookmarked
            }
            val next = if (wasBookmarked) {
                current.filterNot { it.id == announcement.id }
            } else {
                listOf(AnnouncementBookmark.from(announcement)) + current
            }
            preferences[BOOKMARKS] = announcementBookmarkJson.encodeToString(next)
        }
        return removedCampusPin
    }

    companion object {
        private val BOOKMARKS = stringPreferencesKey("bookmarks_v1")
        private val CAMPUS_PIN = stringPreferencesKey("campus_pin_v1")

        @Volatile
        private var instance: AnnouncementBookmarkRepository? = null

        fun get(context: Context): AnnouncementBookmarkRepository = instance ?: synchronized(this) {
            instance ?: AnnouncementBookmarkRepository(
                context.applicationContext.announcementBookmarksDataStore,
            ).also { instance = it }
        }
    }
}

private fun decodeCampusPin(encoded: String?): AnnouncementBookmark? {
    if (encoded == null) return null
    return try {
        announcementBookmarkJson.decodeFromString<AnnouncementBookmark>(encoded).takeIf { it.id.isNotBlank() }
    } catch (_: SerializationException) {
        null
    }
}

private fun decodeBookmarks(encoded: String?): List<AnnouncementBookmark> {
    if (encoded.isNullOrBlank()) return emptyList()
    return try {
        announcementBookmarkJson.decodeFromString<List<AnnouncementBookmark>>(encoded)
            .filter { it.id.isNotBlank() }
            .distinctBy(AnnouncementBookmark::id)
    } catch (error: SerializationException) {
        throw IOException("Saved announcement bookmarks could not be read", error)
    }
}
