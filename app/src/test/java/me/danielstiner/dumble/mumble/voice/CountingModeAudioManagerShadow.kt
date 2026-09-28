package me.danielstiner.dumble.mumble.voice

import android.media.AudioManager
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAudioManager

/**
 * Counts every setMode call, repeats included: Robolectric fires OnModeChangedListener only on a
 * change (checked in shadows-framework 4.15.1).
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
