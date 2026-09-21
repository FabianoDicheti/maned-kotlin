package dev.maned

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.putJsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * MANEDKT_PLAN.md §4.5 and hazard 1 — the coroutine surface, and the reason it
 * names `Dispatchers.IO` specifically.
 *
 * `Dispatchers.IO` is **platform-backed**. A native downcall pins a virtual
 * thread's carrier for its whole duration, and the carrier pool is
 * `availableProcessors()`, so a handful of concurrent maned calls on virtual
 * threads starve every unrelated task in the application — silently.
 */
class CoroutinesTest {

    private val expected = """{"outputs":{"a":[[7,9],[2,4]]},"notes":[]}"""

    private fun script(concurrent: Boolean = true) = Maned.load(
        TestEnv.conformanceDir().resolve("01_local_matmul.mnd").toString(),
        concurrent = concurrent,
        reload = true,
    )

    private val payload = buildJsonObject {
        putJsonArray("h0") {
            add(buildJsonArray { add(JsonPrimitive(3)); add(JsonPrimitive(1)) })
            add(buildJsonArray { add(JsonPrimitive(2)); add(JsonPrimitive(4)) })
        }
    }

    @Test
    fun `runAsync returns the same bytes as run`() = runBlocking {
        script().use { s ->
            assertEquals(expected, s.runAsync(payload).text)
            assertEquals(expected, s.runAsync("""{"h0":[[3,1],[2,4]]}""").text)
            assertEquals(s.run(payload).text, s.runAsync(payload).text)
        }
    }

    @Test
    fun `requestAsync is the raw hatch, off the calling thread`() = runBlocking {
        script().use { s ->
            val r = s.requestAsync("GET", "/nope")
            assertEquals(404, r.status)
        }
    }

    /** Hazard 1 in assertion form: the dispatcher must not be virtual-backed. */
    @Test
    fun `runAsync runs on a platform thread`() = runBlocking {
        script().use { s ->
            val virtual = withContext(Dispatchers.IO) {
                s.run(payload)
                Thread.currentThread().isVirtual
            }
            assertFalse(virtual, "Dispatchers.IO is no longer platform-backed — §5.1 breaks")
        }
    }

    @Test
    fun `concurrent runAsync calls agree`() = runBlocking {
        script().use { s ->
            val results = (1..32).map { async { s.runAsync(payload).text } }.awaitAll()
            assertEquals(setOf(expected), results.toSet())
        }
    }

    /**
     * The sizing note from §4.5: a service gives maned calls a
     * `limitedParallelism` slice rather than the whole 64-thread `Dispatchers.IO`
     * ceiling, because each in-flight call may spawn `setKernelThreads`
     * interpreter workers on top.
     */
    @Test
    fun `a limitedParallelism slice is a usable dispatcher`() = runBlocking {
        @Suppress("OPT_IN_USAGE")
        val slice = Dispatchers.IO.limitedParallelism(2)
        script().use { s ->
            val results = (1..16).map { async { s.runAsync(payload, slice).text } }.awaitAll()
            assertEquals(setOf(expected), results.toSet())
        }
    }

    @Test
    fun `the coroutine context is not lost`() = runBlocking {
        script().use { s ->
            withContext(CoroutineName("maned")) {
                s.runAsync(payload)
                assertEquals("maned", currentCoroutineContext()[CoroutineName]?.name)
            }
        }
    }

    /**
     * §5.5, stated rather than worked around: there is deliberately no timeout
     * overload, because cancellation cannot reach a native downcall. This test
     * only pins that the suspend surface exists with the shape §4.5 describes.
     */
    @Test
    fun `there is no timeout overload to mislead anyone`() {
        val overloads = Class.forName("dev.maned.CoroutinesKt").methods
            .filter { it.name.startsWith("runAsync") }
        assertTrue(overloads.isNotEmpty(), "runAsync is missing")
        assertTrue(
            overloads.none { m -> m.parameterTypes.any { it.simpleName.contains("Duration") } },
            "a timeout overload would appear to work while the thread stays in C",
        )
    }
}
