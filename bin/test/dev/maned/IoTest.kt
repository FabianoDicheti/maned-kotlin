package dev.maned

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The file routes — `POST /run` as multipart, `/frame`, `/frame.csv` — and the
 * `GET /health` contract they read.
 *
 * The multipart encoding is asserted **byte for byte** against the body manedpy
 * produces for the same inputs. The three bindings are one contract; a body that
 * differs by a header is a second one, and the difference would surface only
 * against an engine version that stops being lenient.
 */
class IoTest {

    private fun corpus(name: String) = TestEnv.conformanceDir().resolve(name).toString()

    // -- encoding ---------------------------------------------------------------

    /**
     * The exact bytes `manedpy.script.encode_multipart("abc123", {"h0": ...})`
     * returns. Frozen here deliberately: this is the parity gate, so it must
     * fail when the encoder drifts rather than follow it.
     */
    private val manedpyBody =
        (
            "--abc123\r\n" +
                "Content-Disposition: form-data; name=\"h0\"; filename=\"h0\"\r\n" +
                "Content-Type: application/octet-stream\r\n\r\n" +
                "3,1\n2,4\n\r\n" +
                "--abc123--\r\n"
            ).toByteArray(Charsets.UTF_8)

    @Test
    fun `encode is byte-identical to manedpy`() {
        val body = Multipart.encode("abc123", mapOf("h0" to "3,1\n2,4\n".toByteArray()))
        assertContentEquals(
            manedpyBody.toList(),
            body.toList(),
            "multipart encoding drifted from manedpy's:\n${body.decodeToString()}",
        )
    }

    @Test
    fun `a round trip survives NULs and CRLFs`() {
        // A PNG signature carries both a NUL and a CRLF, and this payload ends
        // with the same CRLF that terminates a part — the case a decoder that
        // trims greedily gets wrong.
        val png = byteArrayOf(
            0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(),
            '\r'.code.toByte(), '\n'.code.toByte(), 0x1a, '\n'.code.toByte(),
            0, 0, 'b'.code.toByte(), 'i'.code.toByte(), 'n'.code.toByte(),
            '\r'.code.toByte(), '\n'.code.toByte(),
        )
        val csv = "1,2\n".toByteArray()
        val body = Multipart.encode("abc123", linkedMapOf("img" to png, "csv" to csv))

        val parts = Multipart.decode("multipart/form-data; boundary=abc123", body)
        assertEquals(setOf("img", "csv"), parts.keys)
        assertContentEquals(png.toList(), parts.getValue("img").toList())
        assertContentEquals(csv.toList(), parts.getValue("csv").toList())
    }

    @Test
    fun `decode reads a quoted boundary and ignores other parameters`() {
        val body = Multipart.encode("abc123", mapOf("a" to "1".toByteArray()))
        val parts = Multipart.decode(
            "multipart/form-data; charset=utf-8; boundary=\"abc123\"",
            body,
        )
        assertEquals("1", parts.getValue("a").decodeToString())
    }

    @Test
    fun `decode refuses a body with no boundary`() {
        val e = assertFailsWith<ManedInputException> {
            Multipart.decode("application/json", "{}".toByteArray())
        }
        assertTrue("no boundary" in e.message!!, "unhelpful message: ${e.message}")
    }

    @Test
    fun `a boundary never occurs in the payload`() {
        val boundary = Multipart.boundary()
        assertTrue(boundary.startsWith("manedkt"))
        assertNotEquals(boundary, Multipart.boundary(), "boundaries repeat")

        // The degenerate input: a part that already contains a boundary this
        // generator could have produced.
        val poisoned = "--${Multipart.boundary()}\r\n".toByteArray()
        val chosen = Multipart.boundaryFor(mapOf("x" to poisoned))
        assertTrue(chosen !in poisoned.decodeToString())
    }

    // -- the file route ---------------------------------------------------------

    @Test
    fun `runFiles round-trips raw bytes`() {
        Maned.load(corpus("01_local_matmul.mnd")).use { script ->
            val parts = script.runFiles("h0" to "3,1\n2,4\n".toByteArray())
            // One output, so the bare body is keyed by the single out:: name.
            assertEquals(setOf("a"), parts.keys)
            assertEquals("7,9\n2,4\n", parts.getValue("a").decodeToString())
        }
    }

    @Test
    fun `runFiles returns every part of a multi-output script`() {
        Maned.load(corpus("04_multi_output.mnd")).use { script ->
            val parts = script.runFiles("h0" to "3,1\n2,4\n".toByteArray())
            assertEquals("7,9\n2,4\n", parts.getValue("a").decodeToString())
            assertEquals("7,2\n9,4\n", parts.getValue("t").decodeToString())
            assertEquals(2, parts.size)
        }
    }

    @Test
    fun `runFiles maps a refusal to an exception`() {
        Maned.load(corpus("01_local_matmul.mnd")).use { script ->
            assertFailsWith<ManedInputException> {
                script.runFiles("nosuch" to "1\n".toByteArray())
            }
        }
    }

    // -- frame ------------------------------------------------------------------

    @Test
    fun `frame and frameCsv agree with manedpy`() {
        val payload = buildJsonObject {
            putJsonArray("t") {
                add(buildJsonObject { put("a", 1); put("b", 2) })
                add(buildJsonObject { put("a", 3); put("b", 4) })
            }
        }
        Maned.load(corpus("03_frame_records.mnd")).use { script ->
            // The frame document IS the body on this route — not nested.
            assertEquals(
                Json.parseToJsonElement(payload.toString()),
                script.frame(payload).json,
            )
            assertEquals("a,b\n1,2\n3,4\n", script.frameCsv(payload).decodeToString())
        }
    }

    // -- the contract -----------------------------------------------------------

    @Test
    fun `health reports the io contract without the fake metric`() {
        Maned.load(corpus("04_multi_output.mnd")).use { script ->
            assertEquals(listOf("h0", "w"), script.inputs.map { it.name })
            assertEquals("csv", script.inputs[0].format)
            assertEquals(false, script.inputs[0].default)
            assertEquals(true, script.inputs[1].default, "w has a literal default")

            assertEquals(listOf("a", "t"), script.outputs.map { it.name })
            assertNull(script.outputs[0].default, "an out:: cannot have a default")

            assertTrue("POST /run" in script.routes)
            assertNull(
                script.health["requests_served"],
                "requests_served counts this handle's calls, not a server's",
            )
        }
    }

    @Test
    fun `frame routes are advertised only by a frame script`() {
        Maned.load(corpus("01_local_matmul.mnd")).use { plain ->
            assertTrue("POST /frame" !in plain.routes)
        }
        Maned.load(corpus("03_frame_records.mnd")).use { framed ->
            assertTrue("POST /frame" in framed.routes)
        }
    }

    /** The contract is frozen at `mnd_open`, so it is fetched exactly once. */
    @Test
    fun `health is cached across accesses`() {
        Maned.load(corpus("04_multi_output.mnd")).use { script ->
            val first = script.health
            script.runFiles("h0" to "3,1\n2,4\n".toByteArray())
            assertTrue(first === script.health)
        }
    }
}
