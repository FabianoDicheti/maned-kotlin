package dev.maned

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * MANEDKT_PLAN.md §4.3 — the status -> exception table, and the rule that error
 * bodies are **plain text** (`src/serve/server.cpp:154`), so the status is what
 * you branch on, never a parse.
 *
 * The dual-inheritance rows are the bet this file exists to protect:
 * `catch (e: IllegalArgumentException)` must do something sensible for someone
 * who never read the docs.
 */
class ErrorMappingTest {

    private val dir: Path = TestEnv.conformanceDir()

    private fun load(case: String) = Maned.load(dir.resolve("$case.mnd").toString(), reload = true)

    // -- the table, against live responses --------------------------------------

    @Test
    fun `400 ragged input becomes a ManedInputException`() {
        load("05_err_ragged").use { s ->
            val e = assertFailsWith<ManedInputException> { s.run("""{"h0": [[3,1],[2]]}""") }
            assertEquals(400, e.status)
            assertTrue(
                (e as Throwable) is IllegalArgumentException,
                "the §4.3 supertype is wrong",
            )
            assertTrue("ragged" in e.body!!, e.body)
            assertEquals(dir.resolve("05_err_ragged.mnd").toRealPath().toString(), e.script)
            assertTrue("400" in e.message!!, e.message!!)
        }
    }

    @Test
    fun `400 unknown input becomes a ManedInputException`() {
        load("06_err_unknown_input").use { s ->
            val e = assertFailsWith<ManedInputException> { s.run("""{"h1": [[1,0],[0,1]]}""") }
            assertEquals(400, e.status)
            assertTrue("names no request input" in e.body!!, e.body)
        }
    }

    @Test
    fun `404 becomes a ManedRouteException, and the raw hatch returns it as data`() {
        load("07_err_bad_route").use { s ->
            // request() does NOT map: an error is data there.
            val raw = s.request("GET", "/nope")
            assertEquals(404, raw.status)
            assertEquals("text/plain; charset=utf-8", raw.contentType)
            assertTrue(raw.isError)

            // But the mapped path does throw. /run.json on a GET-only miss is a
            // different shape, so drive the mapper directly for the 404 row.
            val e = manedExceptionFor(404, raw.text, "s")
            assertTrue(e is ManedRouteException, "404 must map to ManedRouteException, got $e")
            assertEquals(404, e.status)
        }
    }

    @Test
    fun `500 runtime error becomes a ManedRuntimeException`() {
        load("08_err_runtime").use { s ->
            val e = assertFailsWith<ManedRuntimeException> {
                s.run("""{"h0": [[1,2,3],[4,5,6],[7,8,9]]}""")
            }
            assertEquals(500, e.status)
            assertTrue((e as Throwable) is IllegalStateException, "the §4.3 supertype is wrong")
            assertTrue("matmul inner dimensions disagree" in e.body!!, e.body)
        }
    }

    @Test
    fun `a 200 maps to nothing`() {
        assertNull(manedExceptionFor(200, """{"outputs":{}}""", "s"))
        assertNull(manedExceptionFor(204, "", "s"))
    }

    // -- the rows that need no engine to be worth pinning -----------------------

    @Test
    fun `the whole table maps as written`() {
        assertTrue(manedExceptionFor(400, "b", "s") is ManedInputException)
        assertTrue(manedExceptionFor(413, "b", "s") is ManedInputException)
        assertTrue(manedExceptionFor(431, "b", "s") is ManedInputException)
        assertTrue(manedExceptionFor(404, "b", "s") is ManedRouteException)
        assertTrue(manedExceptionFor(405, "b", "s") is ManedRouteException)
        assertTrue(manedExceptionFor(500, "b", "s") is ManedRuntimeException)
        assertTrue(manedExceptionFor(501, "b", "s") is ManedUnsupportedException)
        assertTrue(manedExceptionFor(0, "b", "s") is ManedInternalException)
    }

    @Test
    fun `the JVM-conventional supertypes are what the table promises`() {
        assertTrue(manedExceptionFor(400, "b", "s") is IllegalArgumentException)
        assertTrue(manedExceptionFor(500, "b", "s") is IllegalStateException)
        assertTrue(manedExceptionFor(501, "b", "s") is UnsupportedOperationException)
        assertTrue(manedExceptionFor(404, "b", "s") is RuntimeException)
        assertTrue(manedExceptionFor(0, "b", "s") is RuntimeException)
    }

    /** Adding a route never bumps the ABI, so unlisted statuses must map by class. */
    @Test
    fun `statuses outside the table map by class rather than falling through`() {
        assertTrue(manedExceptionFor(429, "b", "s") is ManedInputException)
        assertTrue(manedExceptionFor(503, "b", "s") is ManedRuntimeException)
    }

    @Test
    fun `status 0 is distinct from every HTTP code including 5xx`() {
        val internal = manedExceptionFor(0, "boom", "s") as ManedInternalException
        assertEquals(0, internal.status)
        assertTrue("status 0" in internal.message!!, internal.message!!)
        assertTrue("escaped" in internal.message!!, internal.message!!)
        assertTrue(manedExceptionFor(500, "boom", "s") !is ManedInternalException)
    }

    @Test
    fun `every exception carries status body and script`() {
        val e = manedExceptionFor(400, "the body", "the/script.mnd") as ManedException
        assertEquals(400, e.status)
        assertEquals("the body", e.body)
        assertEquals("the/script.mnd", e.script)
    }

    // -- load failures ----------------------------------------------------------

    @Test
    fun `a missing file refuses by name before touching the engine`() {
        val e = assertFailsWith<ManedLoadException> { Maned.load("/no/such/script.mnd") }
        assertTrue("/no/such/script.mnd" in e.message!!, e.message!!)
        assertNull(e.status)
    }

    @Test
    fun `a broken script fails to load with the engine's own message`(@TempDir dir: Path) {
        val bad = TestEnv.writeScript(dir, "broken", "this is not a maned script at all\n")
        val e = assertFailsWith<ManedLoadException> { Maned.load(bad.toString()) }
        assertEquals(bad.toRealPath().toString(), e.script)
        // §5.4: the load refusal carries the working-directory clarification,
        // because a JVM caller can neither chdir nor trust user.dir.
        assertTrue("working directory" in e.message!!, e.message!!)
        assertTrue("absolute paths" in e.message!!, e.message!!)
    }

    @Test
    fun `a throwing script raises in Kotlin instead of aborting the JVM`() {
        // Gate 7 of §6, cheaply: the 500 path has already thrown above, and the
        // JVM is still here to run this assertion.
        load("08_err_runtime").use { s ->
            repeat(50) {
                assertFailsWith<ManedRuntimeException> {
                    s.run("""{"h0": [[1,2,3],[4,5,6],[7,8,9]]}""")
                }
            }
        }
        assertTrue(Files.isRegularFile(TestEnv.libraryPath()))
    }
}
