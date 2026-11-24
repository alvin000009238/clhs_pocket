package com.clhs.score.domain.overview

import com.clhs.score.shared.time.Instant

/** Native source periods; precipitation is never interpolated to a different interval. */
data class WeatherForecastPeriod(
    val startsAt: Instant,
    val endsAtExclusive: Instant,
    val temperatureCelsius: Int?,
    val condition: String?,
    val precipitationProbability: Int?,
)
