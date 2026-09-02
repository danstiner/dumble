package me.danielstiner.dumble.telecom

import android.content.Context
import android.telecom.PhoneAccount
import android.telecom.TelecomManager
import androidx.core.telecom.CallsManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowTelecomManager

/**
 * Everything about the suppression that can be checked off a device.
 *
 * `TelecomCall` re-registers core-telecom's account to attach
 * `EXTRA_ADD_SELF_MANAGED_CALLS_TO_INCALLSERVICE`, and the handle it uses is a copy of two values
 * the library keeps `internal`. Get either wrong and nothing fails: a second, unused account is
 * registered, the real one stays visible to every dialer that opts into self-managed calls, and
 * the bug is back with no signal.
 *
 * What no test here can reach is Telecom's own response to the extra — that it then withholds the
 * call from such a dialer. That needs a device and a stand-in InCallService; see the PR and
 * TODO.md. Both sides of the API 34 fork, where the component name and capabilities differ.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [31, 34])
class CoreTelecomAccountTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun ourAccountReplacesTheOneCoreTelecomRegisters() {
        CallsManager(context).registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE)
        val shadow = Shadow.extract<ShadowTelecomManager>(
            context.getSystemService(TelecomManager::class.java)
        )
        assertEquals(
            listOf(coreTelecomPhoneAccount(context).accountHandle),
            shadow.allPhoneAccounts.map { it.accountHandle },
        )
    }

    @Test fun theAccountAsksTelecomToWithholdOurCallsFromInCallServices() {
        val extras = coreTelecomPhoneAccount(context).extras
        assertTrue(
            "the key must be present, not merely absent-and-false",
            extras.containsKey(PhoneAccount.EXTRA_ADD_SELF_MANAGED_CALLS_TO_INCALLSERVICE),
        )
        assertFalse(
            extras.getBoolean(PhoneAccount.EXTRA_ADD_SELF_MANAGED_CALLS_TO_INCALLSERVICE),
        )
    }

    /** Drop either and core-telecom's own `addCall` stops working against the account we left. */
    @Test fun theAccountKeepsTheCapabilitiesCoreTelecomNeeds() {
        val account = coreTelecomPhoneAccount(context)
        assertTrue(account.hasCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED))
        assertEquals(
            usesTransactionalCalls(),
            account.hasCapabilities(PhoneAccount.CAPABILITY_SUPPORTS_TRANSACTIONAL_OPERATIONS),
        )
    }
}
