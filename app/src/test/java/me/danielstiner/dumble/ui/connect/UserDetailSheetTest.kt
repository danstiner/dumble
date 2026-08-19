package me.danielstiner.dumble.ui.connect

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Robolectric for the reason [ChannelTreeViewTest] gives: CI runs testDebugUnitTest only. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class UserDetailSheetTest {

    @get:Rule val compose = createComposeRule()

    @Test fun showsTheTargetInMillis() {
        compose.setContent { UserDetailSheet("alice", playoutTargetMillis = 120, isSelf = false) {} }
        compose.onNodeWithText("alice").assertExists()
        // Named for the buffer, not for delay: it is only the buffer's share of the total.
        compose.onNodeWithText("Jitter buffer").assertExists()
        compose.onNodeWithText("120 ms").assertExists()
    }

    /**
     * A retired speaker has no reading, and a dash has to be what says so — "0 ms" would claim the
     * estimator published a target it can never publish.
     */
    @Test fun noReadingIsADashNotAZero() {
        compose.setContent { UserDetailSheet("alice", playoutTargetMillis = null, isSelf = false) {} }
        compose.onNodeWithText("—").assertExists()
        compose.onNodeWithText("0 ms").assertDoesNotExist()
    }

    /**
     * Our own audio is never decoded locally, so this one can never have a value. A dash would say
     * "not right now" and leave someone waiting for a number that is never coming.
     */
    @Test fun ourOwnRowSaysNotApplicableRatherThanNoReadingYet() {
        compose.setContent { UserDetailSheet("dan", playoutTargetMillis = null, isSelf = true) {} }
        compose.onNodeWithText("n/a").assertExists()
        compose.onNodeWithText("—").assertDoesNotExist()
    }
}
