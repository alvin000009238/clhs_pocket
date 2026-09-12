package com.clhs.score.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.clhs.score.ui.navigation.AuthRequirement
import com.clhs.score.viewmodel.AuthState
import org.junit.Rule
import org.junit.Test

class AuthGateTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun restorationDelaysLoadingAndNeverShowsLoginOrPrivateContent() {
        val auth = mutableStateOf<AuthState>(AuthState.Restoring)
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            MaterialTheme {
                AuthGate(auth.value, AuthRequirement.Session, {}) { Text("private content") }
            }
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithText("使用學校帳號登入").assertDoesNotExist()
        composeRule.onNodeWithText("private content").assertDoesNotExist()
        val progress = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)
        composeRule.onNode(progress).assertDoesNotExist()
        composeRule.mainClock.advanceTimeBy(200)
        composeRule.onNode(progress).assertDoesNotExist()
        composeRule.mainClock.advanceTimeBy(150)
        composeRule.onNode(progress).assertExists()
        composeRule.runOnUiThread { auth.value = AuthState.Guest }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithText("使用學校帳號登入").assertExists()
        composeRule.onNode(progress).assertDoesNotExist()
    }

    @Test
    fun publicContentIsVisibleWhileRestoring() {
        composeRule.setContent {
            AuthGate(AuthState.Restoring, AuthRequirement.Public, {}) { Text("public content") }
        }
        composeRule.onNodeWithText("public content").assertExists()
    }
}
