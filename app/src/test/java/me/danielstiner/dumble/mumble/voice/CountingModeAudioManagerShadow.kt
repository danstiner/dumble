package me.danielstiner.dumble.mumble.voice

import android.media.AudioManager
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAudioManager

/**
 * Counts every setMode call, including a repeat of the current value. Robolectric's own shadow
 * only fires OnModeChangedListener on a change (measured, javap on shadows-framework 4.15.1), so a
 * call that re-asserts an unchanged mode is otherwise unobservable from a test.
 */
@Implements(AudioManager::class)
class CountingModeAudioManagerShadow : ShadowAudioManager() {
    var setModeCalls = 0
        private set

    @Implementation
    override fun setMode(mode: Int) {
        setModeCalls++
        super.setMode(mode)
    }
}
