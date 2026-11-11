package me.danielstiner.dumble.ui.connect

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.danielstiner.dumble.mumble.connection.ConnectionStatus
import me.danielstiner.dumble.mumble.connection.ErrorKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ConnectScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun portSharesTheServerRow() {
        compose.setContent {
            ConnectScreen(ConnectUiState(), {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
        }
        val server = compose.onNodeWithText("Server").getBoundsInRoot()
        val port = compose.onNodeWithText("Port").getBoundsInRoot()
        assertEquals(server.top.value, port.top.value, 1f)
        assertTrue("port $port right of server $server", port.left > server.right)
    }

    @Test fun aPasswordRejectionShowsUnderThePasswordField() {
        show(ConnectUiState(status = rejected(ErrorKind.WRONG_SERVER_PASSWORD)))
        compose.onNode(hasText("Password") and hasText("This server needs a password"))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
        compose.onNodeWithText("Username").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Error))
        compose.onAllNodesWithText("This server needs a password").assertCountEquals(1)
    }

    @Test fun aRegisteredNameShowsUnderTheUsernameField() {
        show(ConnectUiState(status = rejected(ErrorKind.WRONG_USER_PASSWORD)))
        compose.onNode(hasText("Username") and hasText("This name is registered", substring = true))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
        compose.onNodeWithText("Password").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Error))
    }

    /** Neither under the field nor dropped below Connect. */
    @Test fun anEditedFormShowsNoFieldRejection() {
        show(ConnectUiState(status = rejected(ErrorKind.WRONG_SERVER_PASSWORD), editedSinceConnect = true))
        compose.onNodeWithText("Password").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Error))
        compose.onAllNodesWithText("This server needs a password").assertCountEquals(0)
    }

    private fun rejected(kind: ErrorKind) = ConnectionStatus.Error(kind, "the server's reason")

    private fun show(state: ConnectUiState) = compose.setContent {
        ConnectScreen(state, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
    }
}
