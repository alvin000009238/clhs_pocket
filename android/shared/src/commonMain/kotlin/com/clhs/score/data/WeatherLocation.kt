package com.clhs.score.data

import kotlinx.serialization.Serializable

@Serializable
data class WeatherLocation(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val timezoneId: String = "Asia/Taipei",
    val countryCode: String = "TW",
    val county: String = "",
    val cwaDataset: String? = null,
    val cwaLocationName: String? = null,
) {
    val supportsCwa: Boolean get() = countryCode == "TW" && cwaDataset != null && cwaLocationName != null

    companion object {
        val SCHOOL = WeatherLocation(
            id = "clhs", name = "中壢區", latitude = 24.9621, longitude = 121.2112,
            county = "桃園市", cwaDataset = "F-D0047-005", cwaLocationName = "中壢區",
        )
    }
}
