package dev.maned

import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestMethodOrder
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandleProxies
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * **An engine defect this binding detects and refuses to paper over.**
 *
 * The engine's JSON request parser reads numbers through the process C locale.
 * In a comma-decimal locale (`pt_BR`, `de_DE`, `fr_FR`, ...) the number scanner
 * treats the element separator as a decimal point, so `[[3,1],[2,4]]` parses as
 * `[[3],[2]]`: a **200 response with silently wrong values**, no error, no note.
 *
 * It lands on the JVM and not on the engine's own C++ suites because the JVM
 * calls `setlocale(LC_ALL, "")` during startup and adopts `$LANG`/`$LC_NUMERIC`
 * from the environment, while a C++ host that never calls `setlocale` stays in
 * the "C" locale.
 *
 * maned-kotlin does **not** call `setlocale` to compensate — a process-global,
 * thread-unsafe write into a host runtime, hiding a data-corruption bug — it
 * logs one actionable line ([Ffi.cDecimalSeparator], `warnIfDecimalSeparatorIsNotADot`).
 * The Gradle test task pins `LC_ALL=C` so the conformance corpus is walked under
 * the locale its expectations were generated in.
 *
 * This test flips `LC_NUMERIC` in-process to prove the diagnosis, then restores
 * it. **When the engine stops being locale-sensitive, the `mis-parses` assertion
 * below will fail; delete it then rather than re-pinning it.**
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class LocaleHazardTest {

    private val linker = Linker.nativeLinker()

    // macOS and glibc agree on these category numbers for LC_NUMERIC.
    private val lcNumeric = if (System.getProperty("os.name").lowercase().contains("mac")) 4 else 1

    private fun setLocale(name: String?): String? {
        val handle = linker.downcallHandle(
            linker.defaultLookup().find("setlocale").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
        )
        val setlocale: SetLocaleFn =
            MethodHandleProxies.asInterfaceInstance(SetLocaleFn::class.java, handle)
        Arena.ofConfined().use { arena ->
            val argument = if (name == null) MemorySegment.NULL else arena.allocateFrom(name)
            val result = setlocale.call(lcNumeric, argument)
            return if (result.address() == 0L) null
            else result.reinterpret(Long.MAX_VALUE).getString(0)
        }
    }

    @Test
    @Order(1)
    fun `the test JVM runs in the C locale, which is what the corpus expects`() {
        assertEquals(".", Ffi.cDecimalSeparator())
        assertEquals("C", setLocale(null))
    }

    @Test
    @Order(2)
    fun `a comma-decimal locale silently mis-parses a request body`() {
        val ptBr = setLocale("pt_BR.UTF-8")
        if (ptBr == null) {
            // No pt_BR locale installed; nothing to prove here.
            return
        }
        try {
            assertEquals(",", Ffi.cDecimalSeparator(), "setlocale did not take effect")

            Maned.load(
                TestEnv.conformanceDir().resolve("01_local_matmul.mnd").toString(),
                reload = true,
            ).use { script ->
                val result = script.run("""{"h0":[[3,1],[2,4]]}""")

                // The failure mode that makes this worth a test: a 200.
                assertEquals(200, result.status, "the engine did not even report an error")
                assertNotEquals(
                    """{"outputs":{"a":[[7,9],[2,4]]},"notes":[]}""",
                    result.text,
                    "the engine is no longer locale-sensitive — delete this test",
                )
                assertEquals(
                    """{"outputs":{"a":[[7],[2]]},"notes":[]}""",
                    result.text,
                    "the mis-parse changed shape",
                )
                assertTrue(result.notes.isEmpty(), "no note warned about it either")
            }
        } finally {
            setLocale("C")
        }
        assertEquals(".", Ffi.cDecimalSeparator(), "the locale was not restored")
    }

    @Test
    @Order(3)
    fun `the detector reads the C locale, not java util Locale`() {
        // java.util.Locale can be pt_BR while the C locale is "C" — which is
        // exactly the situation in this test run, and exactly why the gate calls
        // localeconv() instead of asking the JVM.
        assertEquals(".", Ffi.cDecimalSeparator())
    }

    @Test
    @Order(4)
    fun `the corpus still walks clean after the locale was restored`() {
        Maned.load(
            TestEnv.conformanceDir().resolve("01_local_matmul.mnd").toString(),
            reload = true,
        ).use {
            assertEquals(
                """{"outputs":{"a":[[7,9],[2,4]]},"notes":[]}""",
                it.run("""{"h0":[[3,1],[2,4]]}""").text,
            )
        }
    }
}

internal fun interface SetLocaleFn {
    /** libc `char *setlocale(int category, const char *locale)`. */
    fun call(category: Int, locale: MemorySegment): MemorySegment
}
