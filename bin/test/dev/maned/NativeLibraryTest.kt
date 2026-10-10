package dev.maned

import org.junit.jupiter.api.io.TempDir
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandleProxies
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * MANEDKT_PLAN.md §4.6 — native library discovery, the genuinely new problem.
 *
 * **A JAR entry is not a file.** These tests exercise the extraction path even
 * though the rest of the suite reaches the library through
 * `-Dmaned.library.path`: the extraction code is what a *published* jar uses, so
 * it has to be covered here or it is covered nowhere.
 *
 * The Gradle `stageNativeForTests` task copies the frozen `libmaned_c` into
 * `src/test/resources/native/<target>/` so there is something to extract from.
 * The binary is gitignored — this repo does not vendor the capi artifact.
 */
class NativeLibraryTest {

    private val target: String = TestEnv.target()

    @Test
    fun `the resource staged by the build is on the test classpath`() {
        val resource = NativeLibrary.resourceName(target)
        assertEquals("native/$target/libmaned_c.dylib", resource)
        assertTrue(
            javaClass.classLoader.getResource(resource) != null,
            "no $resource on the test classpath — did stageNativeForTests run?",
        )
    }

    @Test
    fun `the build and the binding agree on the target triple`() {
        assertEquals(NativeLibrary.currentTarget(), TestEnv.target())
    }

    @Test
    fun `extracting produces a loadable library whose abi version is 1`(@TempDir dir: Path) {
        val cache = dir.resolve("cache")
        val extracted = NativeLibrary.extractFromResources(
            target = target,
            loader = javaClass.classLoader,
            candidates = listOf(cache),
        )

        assertTrue(Files.isRegularFile(extracted), "nothing was written")
        assertEquals(cache, extracted.parent, "extracted outside the candidate directory")
        assertTrue(Files.isExecutable(extracted), "the extracted library is not executable")
        assertEquals(
            Files.size(TestEnv.libraryPath()),
            Files.size(extracted),
            "the extracted copy is a different size from the original",
        )

        // dlopen the EXTRACTED copy (not the one Ffi already holds) and call
        // through it. This is the whole point of §4.6: a jar-shipped library is
        // only useful if the file we wrote can actually be loaded.
        Arena.ofConfined().use { arena ->
            val lookup = SymbolLookup.libraryLookup(extracted, arena)
            val handle = Linker.nativeLinker().downcallHandle(
                lookup.find("mnd_abi_version").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT),
            )
            val abiVersion =
                MethodHandleProxies.asInterfaceInstance(AbiVersionFn::class.java, handle)
            assertEquals(1, abiVersion.call(), "the extracted library reports a different ABI")
            assertTrue(abiVersion.call() in Ffi.SUPPORTED_ABI)
        }
    }

    @Test
    fun `the content-hashed cache reuses the file on a second extraction`(@TempDir dir: Path) {
        val cache = dir.resolve("cache")

        val writesBefore = NativeLibrary.writesPerformed.get()
        val first = NativeLibrary.extractFromResources(
            target = target,
            loader = javaClass.classLoader,
            candidates = listOf(cache),
        )
        val writesAfterFirst = NativeLibrary.writesPerformed.get()
        assertEquals(writesBefore + 1, writesAfterFirst, "the first extraction should write once")

        val stamp = Files.getLastModifiedTime(first)

        val second = NativeLibrary.extractFromResources(
            target = target,
            loader = javaClass.classLoader,
            candidates = listOf(cache),
        )

        assertEquals(first, second, "the cache key is not stable across calls")
        assertEquals(
            writesAfterFirst,
            NativeLibrary.writesPerformed.get(),
            "the second extraction rewrote the file instead of hitting the cache",
        )
        assertEquals(stamp, Files.getLastModifiedTime(second), "the cached file was touched")
        assertEquals(
            1,
            Files.list(cache).use { it.count() },
            "extraction left a temp file behind",
        )
    }

    @Test
    fun `the filename carries a content hash so two versions coexist`() {
        val a = ByteArray(64) { it.toByte() }
        val b = ByteArray(64) { (it + 1).toByte() }

        assertEquals(
            NativeLibrary.hashedName(a, target),
            NativeLibrary.hashedName(a, target),
            "the hash is not deterministic",
        )
        assertNotEquals(
            NativeLibrary.hashedName(a, target),
            NativeLibrary.hashedName(b, target),
            "different bytes produced the same cache name — an upgraded jar would " +
                "load the stale library",
        )
        assertNotEquals(
            NativeLibrary.hashedName(a, target),
            NativeLibrary.hashedName(a, "linux-x86_64"),
            "the target is not part of the cache name",
        )
        assertTrue(NativeLibrary.hashedName(a, target).startsWith("libmaned_c-$target-"))
        assertTrue(NativeLibrary.hashedName(a, target).endsWith(".dylib"))
    }

    /**
     * The `noexec` story, simulated by candidate directories that cannot be
     * written at all. The refusal message *is* the API contract here: someone
     * hitting it is in a container they cannot easily change.
     */
    @Test
    fun `when no candidate directory works the refusal names every path tried`(@TempDir dir: Path) {
        val blocker = dir.resolve("blocked")
        Files.createFile(blocker) // a FILE where a directory is needed
        val alsoBad = blocker.resolve("nested")

        val e = assertFailsWith<ManedLoadException> {
            NativeLibrary.extractFromResources(
                target = target,
                loader = javaClass.classLoader,
                candidates = listOf(blocker, alsoBad),
            )
        }
        val message = e.message!!
        assertTrue(blocker.toString() in message, "the first candidate is not named:\n$message")
        assertTrue(alsoBad.toString() in message, "the second candidate is not named:\n$message")
        assertTrue("noexec" in message, "the noexec hint is missing:\n$message")
        assertTrue("maned.library.path" in message, "the escape hatch is missing:\n$message")
    }

    @Test
    fun `an unknown target refuses by name rather than by NPE`(@TempDir dir: Path) {
        val e = assertFailsWith<ManedLoadException> {
            NativeLibrary.extractFromResources(
                target = "solaris-sparc",
                loader = javaClass.classLoader,
                candidates = listOf(dir),
            )
        }
        assertTrue("solaris-sparc" in e.message!!, e.message!!)
        assertTrue("maned.library.path" in e.message!!, e.message!!)
    }

    @Test
    fun `the cache candidate chain is XDG then home then tmpdir`() {
        val candidates = NativeLibrary.cacheCandidates().map { it.toString() }
        assertTrue(candidates.isNotEmpty())
        assertTrue(
            candidates.last().startsWith(
                Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize().toString()
            ),
            "java.io.tmpdir must be the last resort, got $candidates",
        )
        assertTrue(candidates.all { it.endsWith("maned") }, candidates.toString())
        assertEquals(candidates.distinct(), candidates, "duplicate candidate directories")
    }

    @Test
    fun `a library path pointing at nothing refuses with the path in the message`() {
        val previous = System.getProperty(NativeLibrary.LIBRARY_PATH_PROPERTY)
        try {
            System.setProperty(NativeLibrary.LIBRARY_PATH_PROPERTY, "/no/such/libmaned_c.dylib")
            val e = assertFailsWith<ManedLoadException> { NativeLibrary.resolve() }
            assertTrue("/no/such/libmaned_c.dylib" in e.message!!, e.message!!)
        } finally {
            if (previous != null) System.setProperty(NativeLibrary.LIBRARY_PATH_PROPERTY, previous)
        }
    }

    @Test
    fun `the explicit library path wins and is what Ffi actually loaded`() {
        assertEquals(TestEnv.libraryPath(), Ffi.libraryPath)
        assertEquals(TestEnv.abiManifest(), Ffi.abiManifestPath)
    }
}
