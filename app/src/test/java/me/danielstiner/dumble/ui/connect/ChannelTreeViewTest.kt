package me.danielstiner.dumble.ui.connect

import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.danielstiner.dumble.mumble.channeltree.Channel
import me.danielstiner.dumble.mumble.channeltree.ChannelTree
import me.danielstiner.dumble.mumble.channeltree.User
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Robolectric rather than androidTest because CI runs testDebugUnitTest and no instrumented suite.
 * v1 createComposeRule deliberately, matching CallControlsTest — see the TODO.md entry on why the
 * v2 rule's StandardTestDispatcher is not worth adopting for one file.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ChannelTreeViewTest {

    @get:Rule val compose = createComposeRule()

    /** Matches ChannelTreeRowsTest's helper — User has eight positional fields and no defaults. */
    private fun user(session: Int, name: String, channelId: Int) =
        User(session, name, channelId, false, false, false, false, false)

    private val tree = ChannelTree(
        channels = mapOf(0 to Channel(0, null, "Root", 0)),
        users = mapOf(
            7 to user(7, "alice", 0),
            8 to user(8, "bob", 0),
            9 to user(9, "😊bob", 0),
        ),
    )

    private fun roster(speaking: Set<Int>) = compose.setContent {
        ChannelTreeView(tree = tree, mySession = 8, speaking = speaking)
    }

    /**
     * The halo is the only speaking signal now. The width assertion pins the description to the
     * halo box — a revert to the old trailing icon would still pass a plain node count.
     */
    @Test fun onlyTheSpeakingUsersRowIsMarkedSpeaking() {
        roster(speaking = setOf(7))
        assertEquals(1, compose.onAllNodesWithContentDescription("speaking").fetchSemanticsNodes().size)
        // ListItem sets mergeDescendants, so the merged finder resolves to the whole row.
        compose.onNodeWithContentDescription("speaking", useUnmergedTree = true)
            .assertWidthIsEqualTo(48.dp)
    }

    @Test fun nobodyIsMarkedSpeakingWhenTheSetIsEmpty() {
        roster(speaking = emptySet())
        assertEquals(0, compose.onAllNodesWithContentDescription("speaking").fetchSemanticsNodes().size)
    }

    /**
     * Call-site half of the fix: [ChannelTreeViewInitialTest] only covers the extension itself.
     */
    @Test fun anAvatarWithAnEmojiNameShowsTheWholeEmojiAsTheInitial() {
        roster(speaking = emptySet())
        compose.onNodeWithText("😊").assertExists()
    }
}
