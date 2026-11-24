package me.danielstiner.dumble

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import me.danielstiner.dumble.ui.latency.LatencyCheckActivity
import me.danielstiner.dumble.ui.latency.LatencyCheckState
import me.danielstiner.dumble.ui.latency.LatencyCheckViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Nothing listens on port 9, so each check ends at connect with a "failed" result. */
class LatencyCheckActivityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val pkg = instrumentation.targetContext.packageName

    @Before fun clearLog() {
        shell("logcat -c")
    }

    @Test fun theShellIsAdmittedThroughTheGuardAndTheCheckRunsToAResult() {
        val started = instrumentation.addMonitor(ACTIVITY, null, false)
        val out = shell("am start -W --activity-clear-task -n $pkg/$ACTIVITY --es host 127.0.0.1 --ei port 9 --es fingerprint 00")
        try {
            assertTrue(out, "Status: ok" in out)
            awaitResults(1)
        } finally {
            started.waitForActivityWithTimeout(10_000)?.let { instrumentation.runOnMainSync(it::finish) }
            instrumentation.removeMonitor(started)
        }
    }

    /** The shell can start any exported activity, and a test launches from the app's own uid:
     *  neither run shows the guard is there. */
    @Test fun onlyAHolderOfDumpCanStartACheck() {
        val activity = instrumentation.targetContext.packageManager.getActivityInfo(ComponentName(pkg, ACTIVITY), 0)
        assertEquals(Manifest.permission.DUMP, activity.permission)
    }

    @Test fun recreatingTheActivityDoesNotStartASecondCheck() {
        val intent = Intent().setClassName(pkg, ACTIVITY)
            .putExtra("host", "127.0.0.1").putExtra("port", 9).putExtra("fingerprint", "00")
        ActivityScenario.launch<LatencyCheckActivity>(intent).use { scenario ->
            awaitResults(1)
            scenario.recreate()
            // A second start would publish Running synchronously in onCreate, before recreate returns.
            scenario.onActivity {
                val state = ViewModelProvider(it)[LatencyCheckViewModel::class.java].state.value
                assertTrue("$state", state is LatencyCheckState.Done)
            }
            assertEquals(1, results())
        }
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .use { it.readBytes().decodeToString() }

    private fun results() = shell("logcat -d -s LatencyCheck").lines().count { "results:" in it }

    private companion object {
        val ACTIVITY: String = LatencyCheckActivity::class.java.name
    }

    // The timeout only catches a hang.
    private fun awaitResults(count: Int) {
        val deadline = System.nanoTime() + 60_000_000_000L
        while (results() < count) {
            check(System.nanoTime() < deadline) { "no results line" }
            Thread.sleep(100)
        }
    }
}
