package com.clhs.score.ui.navigation

import com.clhs.score.shared.PocketRoot
import org.junit.Assert.assertEquals
import org.junit.Test

/** Contract test: keep Android's existing UI labels and authentication gates unchanged. */
class KmpRootContractTest {
    @Test
    fun nativeAndroidNavigationMatchesSharedCrossPlatformContract() {
        val androidRoots = TopLevelDestination.entries
        val sharedRoots = PocketRoot.entries

        assertEquals(androidRoots.size, sharedRoots.size)
        androidRoots.zip(sharedRoots).forEach { (android, shared) ->
            assertEquals(shared.zhTwLabel, android.label)
            assertEquals(
                shared.requiresSchoolSession,
                android.route.authRequirement() == AuthRequirement.Session,
            )
        }
    }
}
