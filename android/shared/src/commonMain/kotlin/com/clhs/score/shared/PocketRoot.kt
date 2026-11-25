package com.clhs.score.shared

/**
 * Cross-platform contract for the four existing root destinations.
 *
 * Do not use this type to store navigation stacks: each native UI owns its stack.
 * The order, labels and guest access mirror the current Android app.
 */
enum class PocketRoot(
    val zhTwLabel: String,
    val requiresSchoolSession: Boolean,
) {
    Overview("首頁", false),
    Schedule("課表", true),
    Grades("成績", true),
    Campus("校園", false),
}

/** Primitive-only Swift/Objective-C bridge, independent of UI frameworks. */
object PocketRootCatalog {
    fun rootCount(): Int = PocketRoot.entries.size
    fun titleAt(index: Int): String = PocketRoot.entries[index].zhTwLabel
    fun requiresSessionAt(index: Int): Boolean = PocketRoot.entries[index].requiresSchoolSession
}
