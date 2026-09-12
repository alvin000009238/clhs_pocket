package com.clhs.score.benchmark

import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun coldStartup() = measureStartup(StartupMode.COLD)

    @Test
    fun warmStartup() = measureStartup(StartupMode.WARM)

    @Test
    fun hotStartup() = measureStartup(StartupMode.HOT)

    @Test
    fun topLevelNavigationAndScheduleScroll() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        iterations = 5,
        startupMode = StartupMode.WARM,
        setupBlock = { ensureDemoSession() },
    ) {
        startActivityAndWait()
        if (openDemoDestination("課表", "時間")) {
            scrollContent()
        }
        if (openDemoDestination("成績", "科目")) {
            scrollGradesContent()
        }
        if (openDemoDestination("課表", "時間")) {
            scrollContent()
        }
    }

    private fun measureStartup(startupMode: StartupMode) = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        iterations = 5,
        startupMode = startupMode,
        setupBlock = { ensureDemoSession() },
    ) {
        startActivityAndWait()
    }
}
