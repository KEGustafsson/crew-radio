package fi.crewradio.transport

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WaiterTest {

    @Test
    fun aWakeMadeBeforeTheWaitSkipsIt() {
        val w = Waiter()
        w.wake()
        val t0 = System.nanoTime()
        assertTrue(w.await(5_000))
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 1_000)
        assertFalse(w.await(20))                              // consumed: the next wait runs its course
    }

    @Test
    fun aWakeFromAnotherThreadCutsTheWaitShort() {
        val w = Waiter()
        val t = Thread { Thread.sleep(50); w.wake() }
        t.start()
        val t0 = System.nanoTime()
        assertTrue(w.await(5_000))
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 2_000)
        t.join()
    }

    @Test
    fun withoutAWakeTheWaitTimesOut() {
        val w = Waiter()
        val t0 = System.nanoTime()
        assertFalse(w.await(30))
        assertTrue((System.nanoTime() - t0) / 1_000_000 >= 25)
    }
}
