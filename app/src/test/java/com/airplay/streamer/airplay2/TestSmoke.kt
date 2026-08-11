package com.airplay.streamer.airplay2

import com.airplay.streamer.airplay2.util.Ap2Log
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Smoke test proving the JVM unit-test task compiles and runs for the ported
 * airplay2 package, and that Ap2Log's JVM fallback executes without touching
 * android.* classes (which do not exist on the unit-test JVM classpath).
 */
class TestSmoke {

    @Test
    fun smoke() {
        // Exercise the full Ap2Log surface: a "not mocked"/NoClassDefFoundError
        // here would fail the whole todo's verification, so this is a real
        // check of the JVM-safe logging path, not a no-op.
        Ap2Log.log("TestSmoke: log via Ap2Log (JVM fallback)")
        Ap2Log.d("TestSmoke", "debug via Ap2Log")
        Ap2Log.e("TestSmoke", "error via Ap2Log")
        assertTrue(true)
    }
}
