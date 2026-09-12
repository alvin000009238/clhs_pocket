package com.clhs.score.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generateStartup() = baselineProfileRule.collect(
        packageName = TARGET_PACKAGE,
        includeInStartupProfile = true,
        profileBlock = {
            ensureDemoSession()
            startActivityAndWait()
        },
    )

    @Test
    fun generate() = baselineProfileRule.collect(
        packageName = TARGET_PACKAGE,
        profileBlock = {
            ensureDemoSession()
            startActivityAndWait()
            if (openDemoDestination("課表", "時間")) {
                scrollContent()
            }
            if (openDemoDestination("成績", "科目")) {
                scrollGradesContent()
            }
        },
    )
}
