package dev.maned

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * MANEDKT_PLAN.md §4.1 — the `io` sugar, the search path, and the cache policy.
 *
 * The cache key is stated here rather than inferred because the three bindings
 * deliberately differ: manedpy caches by mtime, maned-rs does not cache at all,
 * and *"a binding that silently picks a third behavior is a support ticket."*
 */
class ManedFacadeTest {

    @AfterTest
    fun reset() {
        Maned.searchPath.clear()
        Maned.clearCache()
    }

    private val payload = """{"h0":[[3,1],[2,4]]}"""

    // -- the io sugar -----------------------------------------------------------

    @Test
    fun `io get finds a script on Maned searchPath`() {
        Maned.searchPath.add(TestEnv.conformanceDir().toString())
        Maned.io["01_local_matmul"].use { s ->
            assertEquals("""{"outputs":{"a":[[7,9],[2,4]]},"notes":[]}""", s.run(payload).text)
        }
    }

    @Test
    fun `io get accepts a name that already carries the extension`() {
        Maned.searchPath.add(TestEnv.conformanceDir().toString())
        Maned.io["01_local_matmul.mnd"].use { s ->
            assertEquals(
                TestEnv.conformanceDir().resolve("01_local_matmul.mnd").toRealPath().toString(),
                s.path,
            )
        }
    }

    @Test
    fun `io get finds a script in the current directory`(@TempDir dir: Path) {
        // user.dir is a startup snapshot the JVM cannot change (§5.4), so exercise
        // "." by naming it on the search path instead of pretending to chdir.
        TestEnv.writeScript(dir, "local", ResultTest.QUANTISED_SCRIPT)
        Maned.searchPath.add(dir.toString())
        Maned.io["local"].use { assertEquals("""{"outputs":{"y":6.00},"notes":[]}""", it.run("""{"x":3}""").text) }
    }

    @Test
    fun `a miss names every directory tried`() {
        Maned.searchPath.add("/somewhere/that/does/not/exist")
        val e = assertFailsWith<ManedLoadException> { Maned.io["nope"] }
        val message = e.message!!
        assertTrue("nope.mnd" in message, message)
        assertTrue("/somewhere/that/does/not/exist/nope.mnd" in message, message)
        // CWD and ./mnd/ are the last two rungs of the ladder and must be named.
        assertTrue(message.contains("mnd/nope.mnd"), message)
        assertTrue("Maned.searchPath" in message, message)
        assertTrue("MANEDPATH" in message, message)
    }

    @Test
    fun `the search order is MANEDPATH then searchPath then CWD then dot-mnd`() {
        Maned.searchPath.add("/a")
        Maned.searchPath.add("/b")
        val dirs = Maned.searchDirectories()
        assertEquals(listOf("/a", "/b", ".", "./mnd"), dirs.takeLast(4))
    }

    // -- caching ----------------------------------------------------------------

    @Test
    fun `the same path with the same flags hands back the same handle`() {
        val path = TestEnv.conformanceDir().resolve("01_local_matmul.mnd").toString()
        val a = Maned.load(path)
        val b = Maned.load(path)
        assertSame(a, b, "the (realpath, mtime, size, flags) cache did not hit")
        a.close()
    }

    @Test
    fun `different flags are different cache entries`() {
        val path = TestEnv.conformanceDir().resolve("09_synthesized.mnd").toString()
        val a = Maned.load(path, synthesizeOutputs = true)
        val b = Maned.load(path, synthesizeOutputs = true)
        assertSame(a, b)
        // Without the flag the same file does not even load, which is itself
        // proof that flags are part of the key rather than an afterthought.
        assertFailsWith<ManedLoadException> { Maned.load(path) }
        a.close()
    }

    @Test
    fun `concurrent is part of the cache key`() {
        val path = TestEnv.conformanceDir().resolve("01_local_matmul.mnd").toString()
        val locked = Maned.load(path)
        val free = Maned.load(path, concurrent = true)
        assertNotSame(locked, free)
        assertTrue(free.concurrent)
        locked.close()
        free.close()
    }

    @Test
    fun `reload is the edit-and-rerun escape hatch`() {
        val path = TestEnv.conformanceDir().resolve("01_local_matmul.mnd").toString()
        val a = Maned.load(path)
        val b = Maned.load(path, reload = true)
        assertNotSame(a, b, "reload = true returned the cached handle")
        a.close()
        b.close()
    }

    /**
     * The key includes mtime and size, so editing a script and re-running picks
     * up the edit — the behaviour an unkeyed cache would break in exactly the way
     * it breaks in a notebook.
     */
    @Test
    fun `touching a script invalidates its cache entry`(@TempDir dir: Path) {
        val script = TestEnv.writeScript(dir, "edited", ResultTest.QUANTISED_SCRIPT)
        val first = Maned.load(script.toString())
        assertSame(first, Maned.load(script.toString()))

        Files.setLastModifiedTime(script, FileTime.fromMillis(Files.getLastModifiedTime(script).toMillis() + 5_000))

        val second = Maned.load(script.toString())
        assertNotSame(first, second, "an mtime change did not invalidate the cache")
        first.close()
        second.close()
    }

    @Test
    fun `clearCache drops the entries without closing live handles`() {
        val path = TestEnv.conformanceDir().resolve("01_local_matmul.mnd").toString()
        val a = Maned.load(path)
        Maned.clearCache()
        val b = Maned.load(path)
        assertNotSame(a, b)
        // a is still usable: clearCache drops the reference, it does not free.
        assertEquals("""{"outputs":{"a":[[7,9],[2,4]]},"notes":[]}""", a.run(payload).text)
        a.close()
        b.close()
    }

    @Test
    fun `a closed handle is not served from the cache`() {
        val path = TestEnv.conformanceDir().resolve("01_local_matmul.mnd").toString()
        val a = Maned.load(path)
        a.close()
        val b = Maned.load(path)
        assertNotSame(a, b, "the cache handed back a closed handle")
        b.close()
    }

    @Test
    fun `a relative and an absolute spelling of one file share a cache entry`() {
        val absolute = TestEnv.conformanceDir().resolve("01_local_matmul.mnd")
        val viaDots = TestEnv.conformanceDir().resolve("..").resolve("conformance")
            .resolve("01_local_matmul.mnd")
        val a = Maned.load(absolute.toString())
        val b = Maned.load(viaDots.toString())
        assertSame(a, b, "the key is not the realpath")
        a.close()
    }

    // -- process-global surface -------------------------------------------------

    @Test
    fun `version and abiVersion come off the library`() {
        assertTrue(Maned.version().isNotBlank())
        assertEquals(1, Maned.abiVersion())
    }

    @Test
    fun `the open flags match the header`() {
        assertEquals(1, Maned.SYNTHESIZE_OUTPUTS)
        assertEquals(2, Maned.NO_VM_FALLBACK)
    }
}
