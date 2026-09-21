package dev.maned

import org.junit.jupiter.api.io.TempDir
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandleProxies
import java.math.BigDecimal
import java.nio.file.Path
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * **The JVM-side regression gate for the engine's locale hardening.**
 *
 * History, because the assertions below only make sense with it. The engine used
 * to parse request-body numbers through the *process* C locale. The JVM calls
 * `setlocale(LC_ALL, "")` during startup and adopts `$LANG`/`$LC_NUMERIC` from
 * the environment, so on a comma-decimal host (`pt_BR`, `de_DE`, `fr_FR`, ...)
 * the number scanner consumed the element separator as a decimal point:
 * `[[3,1],[2,4]]` arrived as `[[3],[2]]` and the request returned **200 with
 * silently wrong values**. A C++ host that never calls `setlocale` stays in the
 * "C" locale, which is why nothing in the engine's own suites ever saw it — it
 * took a binding embedded in a host runtime to surface it.
 *
 * `maned_lang fe3ebc8` fixed it: `handle()` and `loadServerScript()` now pin
 * their thread to the C locale with POSIX `uselocale` for the duration of the
 * call. So the contract this file asserts is the strong one — **the engine's
 * answers do not depend on the host's locale at all** — and it is asserted from
 * the side that can actually break it.
 *
 * There is deliberately **no `LC_ALL=C`** in the Gradle test task any more, and
 * no `setlocale` call anywhere in `dev.maned` outside this test. If a future
 * engine build regresses, these tests go red on any machine, not only on one
 * that happens to be configured in Portuguese.
 */
class LocaleIndependenceTest {

    private val linker = Linker.nativeLinker()

    /** `LC_ALL`: 0 on Darwin, 6 on glibc. */
    private val lcAll = if (System.getProperty("os.name").lowercase().contains("mac")) 0 else 6

    private val originalJvmLocale: Locale = Locale.getDefault()

    /**
     * The process C locale as this class found it — **not** assumed to be "C".
     * On a machine with `LANG=pt_BR.UTF-8` the JVM has already called
     * `setlocale(LC_ALL, "")` and adopted it long before libmaned_c is dlopened
     * (verified: the separator is `,` before the first `Maned` call). This class
     * puts it back exactly as it was, so the *rest* of the suite keeps running in
     * the inherited locale rather than in a "C" locale this file left behind.
     */
    private val originalCLocale: String = setCLocale(null) ?: "C"

    /** Every comma-decimal locale that is actually installed on this machine. */
    private val commaLocales: List<String> by lazy {
        listOf("pt_BR.UTF-8", "de_DE.UTF-8", "fr_FR.UTF-8").filter { candidate ->
            val applied = setCLocale(candidate)
            setCLocale(originalCLocale)
            applied != null
        }
    }

    @AfterTest
    fun restore() {
        setCLocale(originalCLocale)
        Locale.setDefault(originalJvmLocale)
    }

    private fun setCLocale(name: String?): String? {
        val handle = linker.downcallHandle(
            linker.defaultLookup().find("setlocale").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
        )
        val setlocale: SetLocaleFn =
            MethodHandleProxies.asInterfaceInstance(SetLocaleFn::class.java, handle)
        Arena.ofConfined().use { arena ->
            val argument = if (name == null) MemorySegment.NULL else arena.allocateFrom(name)
            val result = setlocale.call(lcAll, argument)
            return if (result.address() == 0L) null
            else result.reinterpret(Long.MAX_VALUE).getString(0)
        }
    }

    /**
     * Puts the whole process — the C locale *and* `java.util.Locale` — into
     * [name], runs [block], and puts it back whatever happens.
     */
    private fun <T> inLocale(name: String, jvmTag: String, block: () -> T): T {
        val applied = setCLocale(name)
        checkNotNull(applied) { "locale '$name' is not installed on this machine" }
        val previousJvm = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag(jvmTag))
        try {
            return block()
        } finally {
            Locale.setDefault(previousJvm)
            setCLocale(originalCLocale)
        }
    }

    private fun jvmTagFor(cLocale: String) = cLocale.substringBefore('.').replace('_', '-')

    // -- the gate ---------------------------------------------------------------

    @Test
    fun `at least one comma-decimal locale is installed to test against`() {
        assertTrue(
            commaLocales.isNotEmpty(),
            "none of pt_BR/de_DE/fr_FR are installed — this gate cannot prove anything " +
                "on this machine. Install one, or run the suite somewhere that has it.",
        )
        assertTrue("pt_BR.UTF-8" in commaLocales, "pt_BR is the locale the defect was found in")
    }

    /** The exact bytes the shared conformance corpus expects, under each locale. */
    @Test
    fun `run_json is byte-identical in every comma-decimal locale`() {
        val expected = """{"outputs":{"a":[[7,9],[2,4]]},"notes":[]}"""
        val script = TestEnv.conformanceDir().resolve("01_local_matmul.mnd").toString()

        // Establish the reference answer in the C locale first. Entered
        // explicitly: the ambient locale is whatever $LANG says, which on this
        // machine is already comma-decimal.
        inLocale("C", "en-US") {
            assertEquals(".", Ffi.cDecimalSeparator())
            Maned.load(script, reload = true).use {
                assertEquals(expected, it.run("""{"h0":[[3,1],[2,4]]}""").text)
            }
        }

        for (locale in commaLocales) {
            inLocale(locale, jvmTagFor(locale)) {
                // Prove the flip actually took, or the assertion below is vacuous.
                assertEquals(
                    ",",
                    Ffi.cDecimalSeparator(),
                    "setlocale($locale) did not change the process decimal separator",
                )
                assertEquals(locale.substringBefore('.').replace('_', '-'), Locale.getDefault().toLanguageTag())

                Maned.load(script, reload = true).use { s ->
                    val result = s.run("""{"h0":[[3,1],[2,4]]}""")
                    assertEquals(200, result.status, "under $locale")
                    assertEquals(expected, result.text, "run.json bytes moved under $locale")
                    assertTrue(
                        expected.toByteArray(Charsets.UTF_8).contentEquals(result.bodyBytes),
                        "run.json bytes moved under $locale (byte compare)",
                    )
                    // The specific corruption the fix was for: a row of 1.
                    assertNotEquals(
                        """{"outputs":{"a":[[7],[2]]},"notes":[]}""",
                        result.text,
                        "the comma was consumed as a decimal point again under $locale",
                    )
                    assertContentEquals(
                        longArrayOf(7, 9, 2, 4).toList(),
                        result.tensor("a").data.toList(),
                    )
                }
            }
        }
    }

    /**
     * The other direction: the engine *prints* fixed-point text at
     * `quantres > 0`, and that text must keep its `.` no matter what the host
     * thinks a decimal point is — otherwise `BigDecimal(String)` would blow up
     * (or, worse, `3,00` would parse as something else entirely).
     */
    @Test
    fun `quantised output prints dot decimals in every comma-decimal locale`(@TempDir dir: Path) {
        val script = TestEnv.writeScript(dir, "quantised_locale", ResultTest.QUANTISED_SCRIPT)

        inLocale("C", "en-US") {
            Maned.load(script.toString(), reload = true).use {
                assertEquals("""{"outputs":{"y":3.00},"notes":[]}""", it.run("""{"x": 1.5}""").text)
            }
        }

        for (locale in commaLocales) {
            inLocale(locale, jvmTagFor(locale)) {
                assertEquals(",", Ffi.cDecimalSeparator())

                Maned.load(script.toString(), reload = true).use { s ->
                    val result = s.run("""{"x": 1.5}""")

                    assertEquals(
                        """{"outputs":{"y":3.00},"notes":[]}""",
                        result.text,
                        "the engine printed a locale-dependent decimal under $locale",
                    )
                    assertTrue("3.00" in result.text, "no dot decimal under $locale")
                    assertTrue("3,00" !in result.text, "a comma decimal leaked out under $locale")

                    // And it still parses exactly, scale intact (§4.2).
                    val exact = result.decimal("y")
                    assertEquals(BigDecimal("3.00"), exact, "under $locale")
                    assertEquals(2, exact.scale(), "the quantres-2 scale was lost under $locale")
                    assertEquals("3.00", exact.toPlainString())
                    assertEquals(3.0, result.double("y"))
                }
            }
        }
    }

    /** A quantised *request* body must also be read the same way. */
    @Test
    fun `quantised input is read identically in every comma-decimal locale`(@TempDir dir: Path) {
        val script = TestEnv.writeScript(dir, "quantised_input", ResultTest.QUANTISED_SCRIPT)
        val expected = """{"outputs":{"y":[[3.00,0.50]]},"notes":[]}"""

        inLocale("C", "en-US") {
            Maned.load(script.toString(), reload = true).use {
                assertEquals(expected, it.run("""{"x": [[1.5, 0.25]]}""").text)
            }
        }

        for (locale in commaLocales) {
            inLocale(locale, jvmTagFor(locale)) {
                Maned.load(script.toString(), reload = true).use { s ->
                    assertEquals(expected, s.run("""{"x": [[1.5, 0.25]]}""").text, "under $locale")
                    assertEquals(
                        listOf(BigDecimal("3.00"), BigDecimal("0.50")),
                        s.run("""{"x": [[1.5, 0.25]]}""").decimals("y"),
                        "under $locale",
                    )
                }
            }
        }
    }

    /** `/frame` goes through a different code path, so it gets its own row. */
    @Test
    fun `the frame route is byte-identical in every comma-decimal locale`() {
        val expected = """{"t":[{"a":1,"b":2},{"a":3,"b":4}]}"""
        val body = """{"t": [{"a": 1, "b": 2}, {"a": 3, "b": 4}]}"""
        val script = TestEnv.conformanceDir().resolve("03_frame_records.mnd").toString()

        for (locale in commaLocales) {
            inLocale(locale, jvmTagFor(locale)) {
                Maned.load(script, reload = true).use { s ->
                    val result = s.request("POST", "/frame", null, body.toByteArray(Charsets.UTF_8))
                    assertEquals(200, result.status, "under $locale")
                    assertEquals(expected, result.text, "frame bytes moved under $locale")
                }
            }
        }
    }

    /** Loading is hardened too (`loadServerScript`), not only requests. */
    @Test
    fun `a script loads identically in every comma-decimal locale`() {
        val script = TestEnv.conformanceDir().resolve("04_multi_output.mnd").toString()
        val expected = """{"outputs":{"a":[[7,9],[2,4]],"t":[[7,2],[9,4]]},"notes":[]}"""

        for (locale in commaLocales) {
            // Load AND run entirely inside the foreign locale, so mnd_open's own
            // parsing of the script's numeric literals is covered.
            inLocale(locale, jvmTagFor(locale)) {
                Maned.load(script, reload = true).use { s ->
                    assertEquals(emptyList(), s.loadNotes)
                    assertEquals(expected, s.run("""{"h0":[[3,1],[2,4]]}""").text, "under $locale")
                }
            }
        }
    }

    @Test
    fun `inLocale leaves the process exactly as it found it`() {
        val before = Ffi.cDecimalSeparator()
        inLocale("pt_BR.UTF-8", "pt-BR") { assertEquals(",", Ffi.cDecimalSeparator()) }
        assertEquals(before, Ffi.cDecimalSeparator(), "inLocale leaked a locale change")
        assertEquals(originalJvmLocale, Locale.getDefault())
        assertEquals(originalCLocale, setCLocale(null))
    }

    /**
     * The premise of the whole file, asserted rather than assumed: this JVM
     * really did adopt a comma-decimal C locale from its environment, which is
     * what makes the other 100 tests in this suite a locale test too — they all
     * run in it.
     */
    @Test
    fun `this JVM inherited its C locale from the environment`() {
        val lang = System.getenv("LANG")
        if (lang == null || "UTF-8" !in lang || lang.startsWith("C") || lang.startsWith("en_US")) {
            return // nothing to prove on a dot-decimal host
        }
        assertEquals(
            lang,
            originalCLocale,
            "the JVM did not adopt \$LANG — the rest of this suite is not a locale test",
        )
        assertEquals(",", Ffi.cDecimalSeparator(), "expected $lang to be comma-decimal")
    }

    /**
     * The binding no longer warns about the host locale. It used to, and the
     * advice it gave ("start the JVM with LC_ALL=C") would now be stale guidance
     * about a bug that is fixed — so the detection was dropped rather than left
     * to misinform. `Ffi.cDecimalSeparator()` survives only as this file's probe.
     */
    @Test
    fun `no locale advice is left in the binding`() {
        assertTrue(Ffi.cDecimalSeparator() != null, "the probe itself still works")
        val ffiSource = Class.forName("dev.maned.Ffi")
        assertTrue(
            ffiSource.declaredMethods.none { it.name.contains("warnIfDecimal", ignoreCase = true) },
            "the stale LC_ALL=C advice is still compiled in",
        )
    }
}

internal fun interface SetLocaleFn {
    /** libc `char *setlocale(int category, const char *locale)`. */
    fun call(category: Int, locale: MemorySegment): MemorySegment
}
