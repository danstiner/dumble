package me.danielstiner.dumble.mumble.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.shadows.ShadowNetwork

/** The glue to ConnectivityManager, against Robolectric's: the seam's three answers. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [31, 34])
// Robolectric installs Conscrypt as a JVM-wide security provider for a test unless told not to,
// and this class runs before the transport tests in the same package, whose TLS test server is
// JSSE: under Conscrypt they hang or fail, on the Linux runner only, since its Conscrypt carries
// no macOS ARM native and stays out of the JVM here.
@ConscryptMode(ConscryptMode.Mode.OFF)
class AndroidNetworkWatchTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    @Test
    fun currentIsTheActiveNetworkAndUpFollowsWhatThePlatformStillHas() {
        val watch = AndroidNetworkWatch(context)
        val active = connectivity.activeNetwork!!

        assertEquals(active, watch.current)
        assertTrue("the active network is up", watch.isUp(active))
        // A network the platform has no capabilities for is one it no longer has.
        val gone = ShadowNetwork.newInstance(99)
        assertFalse(watch.isUp(gone))
        shadowOf(connectivity).setNetworkCapabilities(gone, NetworkCapabilities())
        assertTrue("capabilities, however few, mean the network exists", watch.isUp(gone))
    }

    @Test
    fun everyArrivalAndLossOfADefaultNetworkIsReported() {
        val watch = AndroidNetworkWatch(context)
        var changes = 0
        watch.start { changes += 1 }

        val callback = shadowOf(connectivity).networkCallbacks.single()
        val network = ShadowNetwork.newInstance(7)
        callback.onAvailable(network)
        callback.onLost(network)

        assertEquals(2, changes)
    }
}
