package com.clhs.score.shared.app

/**
 * Narrow boundary between the native iOS UI and the shared core.
 *
 * The demo implementation serves deterministic synthetic data. The production
 * implementation will sit behind the same interface once the school
 * repositories have moved into commonMain.
 */
interface PocketDataSource {
    val isDemo: Boolean
    fun profile(): PocketProfile
    fun home(clock: PocketClock): PocketHome
    fun schedule(scope: PocketScheduleScope, clock: PocketClock): PocketSchedule
    fun gradeTerms(): List<PocketGradeTerm>
    fun defaultExamValue(): String
    fun grades(examValue: String): PocketGradeReport
    fun campus(clock: PocketClock): PocketCampus
}

object PocketEnvironment {
    fun demo(): PocketDataSource = DemoPocketDataSource()
}
