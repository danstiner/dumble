package me.danielstiner.dumble.ui.about

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * MIT permits use only if "the above copyright notice" ships, and that notice names one holder,
 * so the three MIT components each need their own text. A single shared MIT body showed every one
 * of them under whichever holder's copy happened to ship.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class LicenseTextTest {

    private val resources = ApplicationProvider.getApplicationContext<Context>().resources

    private fun textOf(license: License) =
        resources.openRawResource(license.rawResId()).bufferedReader().use { it.readText() }

    @Test fun mitTextNamesEveryMitHolder() {
        val text = textOf(License.MIT)
        for (holder in listOf("QOS.ch", "The Legion of the Bouncy Castle", "Silero Team")) {
            assertTrue("no notice for $holder in license_mit.txt", holder in text)
        }
    }

    /** Catches an MIT dependency added to [ALL_ATTRIBUTIONS] without its notice. */
    @Test fun mitTextHasASectionPerMitComponent() {
        val rules = textOf(License.MIT).lines().count { it.startsWith("====") }
        assertEquals(2 * ALL_ATTRIBUTIONS.count { it.license == License.MIT }, rules)
    }
}
