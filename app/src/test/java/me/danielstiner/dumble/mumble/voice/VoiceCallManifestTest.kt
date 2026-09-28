package me.danielstiner.dumble.mumble.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class VoiceCallManifestTest {
    private val requested: List<String> = ApplicationProvider.getApplicationContext<Context>().let {
        it.packageManager.getPackageInfo(it.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toList()
    }

    /**
     * Without it setMode is ignored, silently — measured, the mode stayed NORMAL — and no library
     * merges it in, so the app's own manifest has to.
     */
    @Test fun declaresModifyAudioSettings() {
        assertTrue(Manifest.permission.MODIFY_AUDIO_SETTINGS in requested)
    }

    /** Nothing uses either: there is no Telecom call, and the route names come from AudioManager. */
    @Test fun declaresNoTelecomPermissions() {
        assertFalse(Manifest.permission.MANAGE_OWN_CALLS in requested)
        assertFalse(Manifest.permission.BLUETOOTH_CONNECT in requested)
    }
}
