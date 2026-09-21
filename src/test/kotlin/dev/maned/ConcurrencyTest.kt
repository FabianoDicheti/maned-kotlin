package dev.maned

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * MANEDKT_PLAN.md §4.5 — the threading contract, both halves.
 *
 * `maned_c.h`: *"Concurrent mnd_request calls on ONE handle are safe if and only
 * if mnd_script_is_routed(s) == 0."* So `concurrent = true` is a load-time
 * decision gated on that call, and this is the JVM port of the engine's own
 * `testConcurrentHandleIsIdenticalGap025` (`tests/serve/test_serve.cpp:920`):
 * 8 threads x 25 calls on one handle, every response byte-identical.
 */
class ConcurrencyTest {

    private val payload = """{"h0":[[3,1],[2,4]]}"""
    private val expected = """{"outputs":{"a":[[7,9],[2,4]]},"notes":[]}"""

    private fun localScript(): Path = TestEnv.conformanceDir().resolve("01_local_matmul.mnd")

    @Test
    fun `eight threads times twenty-five calls on one concurrent handle agree byte-for-byte`() {
        Maned.load(localScript().toString(), concurrent = true, reload = true).use { script ->
            assertFalse(script.isRouted, "01_local_matmul routes nowhere")
            assertTrue(script.concurrent)

            val threads = 8
            val perThread = 25
            val start = CountDownLatch(1)
            val bodies = CopyOnWriteArrayList<String>()
            val failures = CopyOnWriteArrayList<Throwable>()

            val pool = Executors.newFixedThreadPool(threads)
            try {
                repeat(threads) {
                    pool.submit {
                        try {
                            start.await()
                            repeat(perThread) { bodies.add(script.run(payload).text) }
                        } catch (t: Throwable) {
                            failures.add(t)
                        }
                    }
                }
                start.countDown()
                pool.shutdown()
                assertTrue(
                    pool.awaitTermination(120, TimeUnit.SECONDS),
                    "concurrent calls did not finish — a deadlock, or a call that hung",
                )
            } finally {
                pool.shutdownNow()
            }

            assertTrue(failures.isEmpty(), "concurrent calls threw: ${failures.firstOrNull()}")
            assertEquals(threads * perThread, bodies.size)
            assertEquals(setOf(expected), bodies.toSet(), "responses diverged under concurrency")
        }
    }

    @Test
    fun `the same load with the per-script lock is also correct`() {
        Maned.load(localScript().toString(), reload = true).use { script ->
            assertFalse(script.concurrent, "the lock is the default")
            val pool = Executors.newFixedThreadPool(8)
            val bodies = CopyOnWriteArrayList<String>()
            try {
                repeat(8) { pool.submit { repeat(25) { bodies.add(script.run(payload).text) } } }
                pool.shutdown()
                assertTrue(pool.awaitTermination(120, TimeUnit.SECONDS))
            } finally {
                pool.shutdownNow()
            }
            assertEquals(200, bodies.size)
            assertEquals(setOf(expected), bodies.toSet())
        }
    }

    /**
     * A routed script may not drop its lock. The device is `127.0.0.1:1`, where
     * nothing listens — on purpose: `mnd_open` **plans, it does not dial**, so
     * this proves the refusal comes off the plan rather than off a failed
     * connection.
     */
    @Test
    fun `concurrent is refused for a routed script`(@TempDir dir: Path) {
        val script = TestEnv.writeScript(dir, "routed", ROUTED_SCRIPT)

        // It loads fine without concurrent = true, and it loads *fast*, because
        // no socket is opened.
        val before = System.nanoTime()
        Maned.load(script.toString(), reload = true).use { s ->
            assertTrue(s.isRouted, "mnd_script_is_routed should be 1 for an @device flow")
            assertFalse(s.concurrent)
        }
        val elapsedMs = (System.nanoTime() - before) / 1_000_000
        assertTrue(elapsedMs < 5_000, "load took ${elapsedMs}ms — it dialled 127.0.0.1:1")

        val e = assertFailsWith<ManedLoadException> {
            Maned.load(script.toString(), concurrent = true, reload = true)
        }
        assertTrue("concurrent" in e.message!!, e.message!!)
        assertTrue("mnd_script_is_routed" in e.message!!, e.message!!)
    }

    @Test
    fun `a non-routed script reports is_routed false`() {
        Maned.load(localScript().toString(), reload = true).use {
            assertFalse(it.isRouted)
        }
    }

    /**
     * Process-global, not per-classloader (`src/interp/parallel.cpp:16`). Only
     * asserts it is callable and validated — it changes timing, never values.
     */
    @Test
    fun `setKernelThreads is accepted and validated`() {
        Maned.setKernelThreads(2)
        Maned.load(localScript().toString(), reload = true).use {
            assertEquals(expected, it.run(payload).text)
        }
        Maned.setKernelThreads(0) // 0 = let the engine decide
        assertFailsWith<IllegalArgumentException> { Maned.setKernelThreads(-1) }
    }

    internal companion object {
        /**
         * Verbatim in shape from `maned-rs/maned/tests/script_api.rs:53-66` and
         * `maned_lang/tests/serve/test_serve.cpp:1075`. Port 1 is chosen because
         * nothing listens there — and nothing connects here either.
         */
        const val ROUTED_SCRIPT: String = """
mnd::quantmax=1000;
mnd::quantmin=-1000;
mnd::quantres=0;

device::rex { host = "127.0.0.1"; port = 1; token = env("MANED_KT_TEST_TOKEN"); }

calc::lambda_flow f(x) @device(rex) {
    x x add r =
} return r;

out::r = csv("r.csv");
"""
    }
}
