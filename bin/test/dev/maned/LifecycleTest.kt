package dev.maned

import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * MANEDKT_PLAN.md §4.4 and hazard 7 — close() is idempotent **and draining**,
 * the Cleaner is the backstop, and a shutdown hook covers everything except
 * `Runtime.halt()`.
 *
 * The drain is the part that matters: with `concurrent = true` another thread
 * may be inside `mnd_request` right now, and `mnd_close` under it is a
 * use-after-free in C. Both sibling bindings added this after a review found
 * exactly that bug.
 */
class LifecycleTest {

    private fun script(concurrent: Boolean = false) = Maned.load(
        TestEnv.conformanceDir().resolve("01_local_matmul.mnd").toString(),
        concurrent = concurrent,
        reload = true,
    )

    @Test
    fun `close is idempotent`() {
        val s = script()
        assertFalse(s.isClosed)
        s.close()
        assertTrue(s.isClosed)
        s.close()
        s.close()
        assertTrue(s.isClosed)
    }

    @Test
    fun `use closes exactly once and the handle is then refused`() {
        val s = script()
        s.use { it.run("""{"h0":[[3,1],[2,4]]}""") }
        assertTrue(s.isClosed)
        val e = assertFailsWith<IllegalStateException> { s.run("""{"h0":[[3,1],[2,4]]}""") }
        assertTrue("closed" in e.message!!, e.message!!)
    }

    /**
     * The drain, at the counter level. A `.mnd` that runs long enough to race
     * against by wall clock would be slow and flaky, so the in-flight counter is
     * driven directly through the package-visible hooks the real call path uses.
     */
    @Test
    fun `close waits for an in-flight call before freeing the handle`() {
        val s = script(concurrent = true)

        s.beginForTest() // stand in for a thread parked inside mnd_request
        assertEquals(1, s.inFlightCount)

        val closeEntered = CountDownLatch(1)
        val closeReturned = CountDownLatch(1)
        val thrown = AtomicReference<Throwable?>()
        val closer = Thread({
            try {
                closeEntered.countDown()
                s.close()
            } catch (t: Throwable) {
                thrown.set(t)
            } finally {
                closeReturned.countDown()
            }
        }, "closer")
        closer.start()

        assertTrue(closeEntered.await(5, TimeUnit.SECONDS))
        // close() must NOT return while a call is in flight.
        assertFalse(
            closeReturned.await(300, TimeUnit.MILLISECONDS),
            "close() freed the handle with a call still in flight — use-after-free",
        )
        assertFalse(s.isClosed, "mnd_close ran before the drain finished")

        // A call that has not started yet is refused from the moment close begins.
        assertFailsWith<IllegalStateException> { s.run("""{"h0":[[3,1],[2,4]]}""") }

        s.endForTest() // the in-flight call returns

        assertTrue(closeReturned.await(5, TimeUnit.SECONDS), "close() never woke up")
        closer.join(5_000)
        assertNull(thrown.get())
        assertTrue(s.isClosed)
        assertEquals(0, s.inFlightCount)
    }

    /**
     * An interrupted closer must keep waiting, not spin: re-setting the interrupt
     * flag inside the drain loop would make every `wait()` throw immediately.
     */
    @Test
    fun `an interrupted close still drains instead of spinning`() {
        val s = script(concurrent = true)
        s.beginForTest()

        val done = CountDownLatch(1)
        val stillInterrupted = AtomicReference(false)
        val closer = Thread({
            s.close()
            stillInterrupted.set(Thread.currentThread().isInterrupted)
            done.countDown()
        }, "interrupted-closer")
        closer.start()

        Thread.sleep(100)
        closer.interrupt()
        Thread.sleep(200)
        assertFalse(done.await(0, TimeUnit.MILLISECONDS), "close() returned under interrupt")
        assertFalse(s.isClosed, "the handle was freed with a call in flight")

        s.endForTest()
        assertTrue(done.await(5, TimeUnit.SECONDS), "close() never finished")
        assertTrue(s.isClosed)
        assertTrue(stillInterrupted.get(), "the interrupt was swallowed instead of restored")
    }

    @Test
    fun `the in-flight counter returns to zero after a normal call`() {
        script().use { s ->
            assertEquals(0, s.inFlightCount)
            s.run("""{"h0":[[3,1],[2,4]]}""")
            assertEquals(0, s.inFlightCount)
        }
    }

    @Test
    fun `the counter returns to zero even when the call throws`() {
        Maned.load(
            TestEnv.conformanceDir().resolve("08_err_runtime.mnd").toString(),
            reload = true,
        ).use { s ->
            assertFailsWith<ManedRuntimeException> {
                s.run("""{"h0": [[1,2,3],[4,5,6],[7,8,9]]}""")
            }
            assertEquals(0, s.inFlightCount, "the finally in Script.request did not run")
        }
    }

    /**
     * Loads a handle, uses it, and returns only a weak reference — the local
     * lives in this frame, which is gone by the time the caller runs a GC.
     */
    private fun loadAndDrop(): java.lang.ref.WeakReference<Script> {
        val s = Maned.load(
            TestEnv.conformanceDir().resolve("01_local_matmul.mnd").toString(),
            reload = true,
        )
        s.run("""{"h0":[[3,1],[2,4]]}""")
        // Drop it from the cache too, or it stays strongly reachable — which is
        // the documented cost of caching (§4.1).
        Maned.clearCache()
        return java.lang.ref.WeakReference(s)
    }

    /**
     * A handle the caller forgot must be freed eventually rather than leaked for
     * the process lifetime — the JVM analogue of manedpy's `__del__` guard.
     */
    @Test
    fun `a dropped handle is reclaimed by the Cleaner`() {
        val reference = loadAndDrop()

        var collected = false
        for (attempt in 0 until 100) {
            System.gc()
            Thread.sleep(20)
            if (reference.get() == null) {
                collected = true
                break
            }
        }
        assertTrue(collected, "a dropped Script was never made unreachable")
        // The Cleaner thread then runs mnd_close off-heap; there is nothing
        // observable to assert beyond "the JVM is still running", which reaching
        // this line demonstrates. Give it a moment to actually fire.
        Thread.sleep(100)
    }

    @Test
    fun `closeAll closes every live handle`() {
        val a = script()
        val b = script(concurrent = true)
        Maned.closeAll()
        assertTrue(a.isClosed)
        assertTrue(b.isClosed)
    }

    @Test
    fun `a shutdown hook is registered`() {
        // Hazard 7: registered so a normal exit does not orphan a bark-vm child.
        // It cannot cover Runtime.halt(), and nothing can.
        val hook = assertNotNull(Maned.shutdownHook, "no shutdown hook was registered")
        assertEquals("maned-shutdown", hook.name)
        // removeShutdownHook returns true only for a hook that IS registered.
        assertTrue(
            Runtime.getRuntime().removeShutdownHook(hook),
            "the hook exists but was never handed to the runtime",
        )
        Runtime.getRuntime().addShutdownHook(hook)
    }

    @Test
    fun `loadNotes are exposed`() {
        script().use { assertEquals(emptyList(), it.loadNotes) }
    }

    @Test
    fun `toString says what state the handle is in`() {
        val s = script()
        assertTrue("routed=false" in s.toString())
        assertTrue("closed=false" in s.toString())
        s.close()
        assertTrue("closed=true" in s.toString())
    }

    @Test
    fun `the script path is the realpath`() {
        val expected: Path =
            TestEnv.conformanceDir().resolve("01_local_matmul.mnd").toRealPath()
        script().use { assertEquals(expected.toString(), it.path) }
    }
}
