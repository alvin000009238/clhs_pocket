package com.clhs.score.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ScoreSimulatorStateRestorationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun editorStateSurvivesRestorationAndResetsForAnotherReport() {
        val restorationTester = StateRestorationTester(composeRule)
        val firstDefaults = ScoreSimulatorEditorState(
            adjustedScores = mapOf("國文" to 80.0, "英文" to 75.0),
            checkedSubjects = setOf("國文", "英文"),
        )
        val secondDefaults = ScoreSimulatorEditorState(
            adjustedScores = mapOf("數學" to 90.0),
            checkedSubjects = setOf("數學"),
        )
        val edited = firstDefaults.copy(
            adjustedScores = mapOf("國文" to 92.0, "英文" to 75.0),
            isCustomGroupEnabled = true,
            isTargetReversalEnabled = true,
            checkedSubjects = setOf("國文"),
            lockedSubjects = setOf("英文"),
            targetAverage = "88.5",
        )
        var reportIdentity by mutableStateOf("report-a")
        var observedState: ScoreSimulatorEditorState? = null
        var updateState: (ScoreSimulatorEditorState) -> Unit = {}

        restorationTester.setContent {
            var editorState by rememberSaveable(
                reportIdentity,
                stateSaver = ScoreSimulatorEditorStateSaver,
            ) {
                mutableStateOf(if (reportIdentity == "report-a") firstDefaults else secondDefaults)
            }
            SideEffect {
                observedState = editorState
                updateState = { editorState = it }
            }
        }

        composeRule.runOnIdle { updateState(edited) }
        restorationTester.emulateSavedInstanceStateRestore()
        composeRule.runOnIdle { assertEquals(edited, observedState) }

        composeRule.runOnIdle { reportIdentity = "report-b" }
        composeRule.runOnIdle { assertEquals(secondDefaults, observedState) }
    }
}
