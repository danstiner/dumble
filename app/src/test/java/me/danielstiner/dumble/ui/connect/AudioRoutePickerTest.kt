package me.danielstiner.dumble.ui.connect

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BluetoothAudio
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.PhoneInTalk
import me.danielstiner.dumble.mumble.voice.AudioRoute.Type
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * `routeIcon` is a pure function and directly assertable without Robolectric — `CallControlsTest`
 * covers the caption following `current` (`theRouteButtonNamesTheCurrentRoute`), never the icon.
 */
class AudioRoutePickerTest {

    @Test fun eachTypeGetsItsOwnIcon() {
        assertSame(Icons.Filled.BluetoothAudio, routeIcon(Type.BLUETOOTH))
        assertSame(Icons.Filled.Headset, routeIcon(Type.WIRED_HEADSET))
        assertSame(Icons.Filled.PhoneInTalk, routeIcon(Type.EARPIECE))
    }

    /** Speaker's own glyph doubles as the fallback for anything with no icon of its own. */
    @Test fun speakerStreamingAndUnknownShareTheSpeakerGlyph() {
        assertSame(Icons.AutoMirrored.Filled.VolumeUp, routeIcon(Type.SPEAKER))
        assertSame(Icons.AutoMirrored.Filled.VolumeUp, routeIcon(Type.STREAMING))
        assertSame(Icons.AutoMirrored.Filled.VolumeUp, routeIcon(Type.UNKNOWN))
    }
}
