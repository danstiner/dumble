package me.danielstiner.dumble.ui.connect

import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
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
}
