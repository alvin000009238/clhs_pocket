package com.clhs.score.data

import com.clhs.score.shared.time.Instant
import com.clhs.score.shared.time.epochMillis
import kotlinx.serialization.Serializable

@Serializable
data class YouBikeStation(val id: String, val name: String, val latitude: Double? = null, val longitude: Double? = null, val district: String = "", val address: String = "") {
    companion object { val SCHOOL = YouBikeStation("500304002", "中壢高中", 24.9621, 121.2112) }
}

data class YouBikeSnapshot(
    val availableBikes: Int,
    val emptyDocks: Int,
    val totalDocks: Int,
    val ordinaryBikes: Int?,
    val electricBikes: Int?,
    val isOperating: Boolean,
    val updatedAt: Instant,
    val fetchedAt: Instant,
) {
    fun isStale(now: Instant): Boolean = (now.epochMillis() - updatedAt.epochMillis()).let { elapsedMillis ->
        now < updatedAt || elapsedMillis > STALE_AFTER_MILLIS
    }

    private companion object {
        const val STALE_AFTER_MILLIS = 5 * 60 * 1000L
    }
}

data class YouBikeState(
    val snapshot: YouBikeSnapshot? = null,
    val isRefreshing: Boolean = false,
    val failed: Boolean = false,
    val isManuallyRefreshing: Boolean = false,
)
