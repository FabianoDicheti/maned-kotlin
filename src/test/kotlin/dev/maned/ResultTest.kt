package dev.maned

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * MANEDKT_PLAN.md §4.2 — the response type: exact `Long`s, exact `BigDecimal`s,
 * and a ragged array rejected rather than flattened.
 */
class ResultTest {

    private fun resultOf(body: String, status: Int = 200) = Result(
        status = status,
        contentType = if (status in 200..299) "application/json" else "text/plain; charset=utf-8",
        bodyBytes = body.toByteArray(Charsets.UTF_8),
        notes = emptyList(),
        script = "synthetic",
    )

    // -- tensors ----------------------------------------------------------------

    @Test
    fun `a 2x2 output becomes an exact LongArray`() {
        val t = resultOf("""{"outputs":{"a":[[7,9],[2,4]]},"notes":[]}""").tensor("a")
        assertContentEquals(longArrayOf(2, 2).toList(), t.shape.toList())
        assertContentEquals(longArrayOf(7, 9, 2, 4).toList(), t.data.toList())
        assertEquals(9L, t[0, 1])
        assertEquals(2, t.rank)
    }

    /** `int64_t` in, `Long` out — the mapping is lossless in both directions. */
    @Test
    fun `int64 extremes survive the round trip`() {
        val body = """{"outputs":{"a":[${Long.MIN_VALUE},${Long.MAX_VALUE}]},"notes":[]}"""
        val t = resultOf(body).tensor("a")
        assertContentEquals(longArrayOf(Long.MIN_VALUE, Long.MAX_VALUE).toList(), t.data.toList())
        // The same values through a Double would not survive: anything past 2^53
        // loses its low bits, which is why the mapping is Long and not Double.
        val pastDoublePrecision = (1L shl 53) + 1
        assertNotEquals(pastDoublePrecision, pastDoublePrecision.toDouble().toLong())
        assertEquals(
            pastDoublePrecision,
            resultOf("""{"outputs":{"a":$pastDoublePrecision},"notes":[]}""").tensor("a").data[0],
        )
    }

    @Test
    fun `a 1x1 result that collapsed to a scalar is a rank-0 tensor`() {
        val t = resultOf("""{"outputs":{"a":42},"notes":[]}""").tensor("a")
        assertEquals(0, t.rank)
        assertContentEquals(longArrayOf(42).toList(), t.data.toList())
    }

    @Test
    fun `a ragged array is rejected, not flattened`() {
        val e = assertFailsWith<ManedInputException> {
            resultOf("""{"outputs":{"a":[[7,9],[2]]},"notes":[]}""").tensor("a")
        }
        assertTrue("ragged" in e.message!!, e.message!!)
        assertTrue("'a'" in e.message!!, e.message)
        // The §4.3 supertype bet: this is catchable as an IllegalArgumentException.
        assertTrue((e as Throwable) is IllegalArgumentException)
    }

    @Test
    fun `a row that is longer than the first is also ragged`() {
        assertFailsWith<ManedInputException> {
            resultOf("""{"outputs":{"a":[[1],[2,3]]},"notes":[]}""").tensor("a")
        }
    }

    @Test
    fun `a scalar where a row was expected is ragged`() {
        assertFailsWith<ManedInputException> {
            resultOf("""{"outputs":{"a":[[1,2],3]},"notes":[]}""").tensor("a")
        }
    }

    @Test
    fun `an unknown output name lists what is there`() {
        val e = assertFailsWith<ManedInputException> {
            resultOf("""{"outputs":{"a":[1]},"notes":[]}""").tensor("nope")
        }
        assertTrue("'a'" in e.message!!, e.message!!)
    }

    @Test
    fun `tensor refuses fixed-point text and points at decimal`() {
        val e = assertFailsWith<ManedInputException> {
            resultOf("""{"outputs":{"y":[[3.00]]},"notes":[]}""").tensor("y")
        }
        assertTrue("decimal(" in e.message!!, e.message!!)
    }

    @Test
    fun `Tensor rejects a shape that disagrees with its data`() {
        assertFailsWith<IllegalArgumentException> {
            Tensor(longArrayOf(2, 2), longArrayOf(1, 2, 3))
        }
    }

    // -- decimals ---------------------------------------------------------------

    /**
     * The §4.2 argument, executed against a live engine: at `quantres = 2` the
     * wire carries `3.00`, and `BigDecimal(String)` keeps it — scale and all.
     * manedpy has to pass `parse_float=` to get this; here it is the default.
     */
    @Test
    fun `a quantres 2 script yields an exact BigDecimal with its scale preserved`(
        @TempDir dir: Path,
    ) {
        val script = TestEnv.writeScript(dir, "quantised", QUANTISED_SCRIPT)
        Maned.load(script.toString(), reload = true).use { s ->
            val result = s.run("""{"x": 1.5}""")

            // The bytes on the wire really do say "3.00", not "3" and not "3.0".
            assertEquals("""{"outputs":{"y":3.00},"notes":[]}""", result.text)

            val exact = result.decimal("y")
            assertEquals(BigDecimal("3.00"), exact)
            assertEquals(2, exact.scale(), "the quantres-2 scale was lost")
            assertEquals("3.00", exact.toPlainString())

            // equals() on BigDecimal compares scale too, which is the point:
            // BigDecimal("3.00") != BigDecimal("3") even though they compare 0.
            assertNotEquals(BigDecimal("3"), exact)
            assertEquals(0, BigDecimal("3").compareTo(exact))

            // The lossy opt-in still works, and is explicitly the lossy one.
            assertEquals(3.0, result.double("y"))
        }
    }

    @Test
    fun `decimals reads every element of a quantised matrix`(@TempDir dir: Path) {
        val script = TestEnv.writeScript(dir, "quantised2", QUANTISED_SCRIPT)
        Maned.load(script.toString(), reload = true).use { s ->
            val result = s.run("""{"x": [[1.5, 0.25]]}""")
            assertEquals("""{"outputs":{"y":[[3.00,0.50]]},"notes":[]}""", result.text)
            assertEquals(listOf(BigDecimal("3.00"), BigDecimal("0.50")), result.decimals("y"))
            assertEquals(2, result.decimals("y")[1].scale())
        }
    }

    @Test
    fun `decimal on a matrix points at decimals`() {
        val e = assertFailsWith<ManedInputException> {
            resultOf("""{"outputs":{"y":[[3.00]]},"notes":[]}""").decimal("y")
        }
        assertTrue("decimals(" in e.message!!, e.message!!)
    }

    /**
     * The loss this accessor exists to avoid, demonstrated: 0.1 + 0.2 as text is
     * exact, as a binary double it is not.
     */
    @Test
    fun `BigDecimal keeps what Double would round`() {
        val r = resultOf("""{"outputs":{"y":0.30},"notes":[]}""")
        assertEquals(BigDecimal("0.30"), r.decimal("y"))
        assertNotEquals(0.1 + 0.2, r.double("y"))
    }

    // -- document shape ---------------------------------------------------------

    @Test
    fun `outputs profiles and frames are read off the document`() {
        val r = resultOf("""{"outputs":{"a":[1]},"profiles":{"x":{"ok":true}},"notes":[]}""")
        assertEquals(1, r.outputs.size)
        assertEquals(true, r.profiles!!["x"]!!.let { it as kotlinx.serialization.json.JsonObject }["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(null, r.frames)
        assertTrue(!r.isError)
    }

    @Test
    fun `a plain-text error body is not parsed as JSON`() {
        val r = resultOf("no route for '/nope'; routes: GET /health\n", status = 404)
        assertTrue(r.isError)
        assertEquals("no route for '/nope'; routes: GET /health\n", r.text)
        val e = assertFailsWith<ManedInputException> { r.json }
        assertTrue("plain-text" in e.message!!, e.message!!)
    }

    @Test
    fun `json serialisation of a payload round-trips through run`(@TempDir dir: Path) {
        val script = TestEnv.writeScript(dir, "quantised3", QUANTISED_SCRIPT)
        Maned.load(script.toString(), reload = true).use { s ->
            val payload = buildJsonObject {
                putJsonArray("x") {
                    add(buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(2)) })
                }
            }
            assertEquals("""{"outputs":{"y":[[4.00]]},"notes":[]}""", s.run(payload).text)
        }
    }

    @Test
    fun `notes are parsed from the JSON array the ABI returns`() {
        assertEquals(emptyList(), Ffi.parseNotes("[]"))
        assertEquals(emptyList(), Ffi.parseNotes(""))
        assertEquals(listOf("a", "b"), Ffi.parseNotes("""["a","b"]"""))
        // A malformed note must never take down a successful call.
        assertEquals(listOf("not json"), Ffi.parseNotes("not json"))
    }

    internal companion object {
        const val QUANTISED_SCRIPT: String = """
mnd::quantmax=100;
mnd::quantmin=-100;
mnd::quantres=2;

calc::lambda_flow f(x) {
    x x add y =
} return y;

out::y = csv("y.csv");
"""
    }
}
