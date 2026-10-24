package com.clhs.score.data

import kotlinx.serialization.Serializable

@Serializable
data class SavedSimulatorCredits(
    val subjectNames: List<String>,
    val credits: Map<String, Int>,
    val source: String,
    val version: Int = 1,
) {
    init {
        require(version == 1) { "Unsupported simulator credit settings version" }
        require(source == "manual" || source == "inferred" || source == "default") { "Invalid simulator credit source" }
        require(subjectNames.size in 1..64) { "Invalid simulator subject count" }
        require(subjectNames == subjectNames.distinct().sorted()) { "Subject names must be unique and sorted" }
        require(subjectNames.all { it.isNotBlank() && it == cleanSubjectName(it) }) {
            "Subject names must be exact cleaned names"
        }
        require(credits.keys == subjectNames.toSet()) { "Credits must match the subject signature" }
        require(credits.values.all { it in 1..SimulatorCredits.MAX_CREDITS }) {
            "Credits must be in 1..${SimulatorCredits.MAX_CREDITS}"
        }
    }
}
