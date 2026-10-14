package com.clhs.score.domain.overview

import java.time.Instant

/** Native source periods; precipitation is never interpolated to a different interval. */
data class WeatherForecastPeriod(
    val startsAt: Instant,
    val endsAtExclusive: Instant,
    val temperatureCelsius: Int?,
    val condition: String?,
    val precipitationProbability: Int?,
)
