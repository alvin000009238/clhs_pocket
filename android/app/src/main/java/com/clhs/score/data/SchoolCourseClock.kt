package com.clhs.score.data

import java.time.Clock
import java.time.ZoneId

internal val SCHOOL_COURSE_ZONE: ZoneId = ZoneId.of("Asia/Taipei")

internal fun resolveCourses(report: ScheduleReport?, clock: Clock, remindBeforeClass: Boolean = false): CourseResolution =
    resolveCourses(report, clock.instant(), remindBeforeClass)
