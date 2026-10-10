package dev.maned

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.streams.asSequence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Gate 3 of MANEDKT_PLAN.md §6: the shared conformance corpus (§3.3), walked.
 *
 * The corpus is defined ONCE, in the maned repo, and ships in the capi tarball
 * so that the in-repo `capi` suite, manedpy, maned-kotlin and maned-rs all walk
 * the same fixtures rather than each forking the parity corpus. A binding costs
 * one harness — this file — not one corpus.
 *
 * Bodies are compared **byte-for-byte**, per the corpus README. That is the whole
 * point: `/run.json` through `mnd_request` must be byte-identical to what
 * `maned-serve` answers over HTTP.
 */
class ConformanceTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val dir: Path = TestEnv.conformanceDir()

    private fun cases(): List<String> =
        Files.list(dir).use { stream ->
            stream.asSequence()
                .map { it.name }
                .filter { it.endsWith(".request.json") }
                .map { it.removeSuffix(".request.json") }
                .sorted()
                .toList()
        }

    @Test
    fun `the corpus is present and complete`() {
        val cases = cases()
        assertTrue(cases.size >= 9, "expected the 9 shipped fixture triples, found ${cases.size}")
        for (case in cases) {
            assertTrue(Files.isRegularFile(dir.resolve("$case.mnd")), "$case.mnd missing")
            assertTrue(Files.isRegularFile(dir.resolve("$case.expect.json")), "$case.expect.json missing")
        }
        // Every error class the corpus promises to cover (§3.3).
        val statuses = cases.map { expectationOf(it).status }.toSet()
        assertTrue(200 in statuses)
        assertTrue(400 in statuses, "no 400 fixture — ragged / unknown input")
        assertTrue(404 in statuses, "no 404 fixture — bad route")
        assertTrue(500 in statuses, "no 500 fixture — runtime error")
    }

    @TestFactory
    fun conformance(): List<DynamicTest> = cases().map { case ->
        DynamicTest.dynamicTest(case) { runCase(case) }
    }

    private fun runCase(case: String) {
        val request = json.parseToJsonElement(Files.readString(dir.resolve("$case.request.json")))
            .jsonObject
        val expect = expectationOf(case)

        val method = request.getValue("method").jsonPrimitive.content
        val target = request.getValue("target").jsonPrimitive.content
        // `body` is the EXACT raw request body as a JSON string ("" for none).
        val body = (request["body"]?.jsonPrimitive?.content ?: "").toByteArray(Charsets.UTF_8)
        val flags = flagsOf(request["flags"] as? JsonArray)

        Maned.load(
            path = dir.resolve("$case.mnd").toString(),
            synthesizeOutputs = flags.synthesizeOutputs,
            noVmFallback = flags.noVmFallback,
            reload = true,
        ).use { script ->
            // The raw escape hatch, not run(): a conformance walker must see the
            // error responses as data, and reaches routes like GET /nope.
            val result = script.request(method, target, contentTypeFor(target), body)

            assertEquals(expect.status, result.status, "$case: status")
            assertEquals(expect.contentType, result.contentType, "$case: content_type")
            assertEquals(
                expect.body,
                result.bodyBytes.decodeToString(),
                "$case: body (byte-for-byte)",
            )
            assertTrue(
                expect.body.toByteArray(Charsets.UTF_8).contentEquals(result.bodyBytes),
                "$case: body bytes differ even though the decoded text matched",
            )
        }
    }

    /** Only multipart /run and /frame.csv need one; JSON routes do not. */
    private fun contentTypeFor(target: String): String? =
        if (target == "/run.json") "application/json" else null

    private class Expectation(val status: Int, val contentType: String, val body: String)

    private fun expectationOf(case: String): Expectation {
        val e: JsonObject =
            json.parseToJsonElement(Files.readString(dir.resolve("$case.expect.json"))).jsonObject
        return Expectation(
            status = e.getValue("status").jsonPrimitive.content.toInt(),
            contentType = e.getValue("content_type").jsonPrimitive.content,
            body = e.getValue("body").jsonPrimitive.content,
        )
    }

    private class Flags(val synthesizeOutputs: Boolean, val noVmFallback: Boolean)

    private fun flagsOf(array: JsonArray?): Flags {
        val names = array?.map { it.jsonPrimitive.content }?.toSet() ?: emptySet()
        names.forEach {
            check(it == "synthesize_outputs" || it == "no_vm_fallback") {
                "unknown mnd_open flag name in the corpus: '$it'"
            }
        }
        return Flags(
            synthesizeOutputs = "synthesize_outputs" in names,
            noVmFallback = "no_vm_fallback" in names,
        )
    }

    @Test
    fun `the flag names map onto the header constants`() {
        assertEquals(1, Maned.SYNTHESIZE_OUTPUTS, "MND_SYNTHESIZE_OUTPUTS is (1u << 0)")
        assertEquals(2, Maned.NO_VM_FALLBACK, "MND_NO_VM_FALLBACK is (1u << 1)")
    }

    /**
     * The synthesized fixture is the one case whose behaviour depends on a flag,
     * so prove the flag is actually load-bearing rather than incidentally passing.
     */
    @Test
    fun `the synthesize_outputs fixture fails to load without the flag`() {
        val script = dir.resolve("09_synthesized.mnd")
        val e = kotlin.runCatching {
            Maned.load(script.toString(), reload = true).close()
        }.exceptionOrNull()
        assertTrue(
            e is ManedLoadException,
            "09_synthesized.mnd declares no out::/profile::/frame:: and must not load " +
                "without MND_SYNTHESIZE_OUTPUTS; got $e",
        )
        assertTrue(
            "out::" in e.message!! || "outputs" in e.message!!,
            "the load refusal should name the missing directive: ${e.message}",
        )
    }

    /** Cheap version of Gate 9: the heap must not grow with repeated calls. */
    @Test
    fun `repeated calls do not leak the response struct`() {
        Maned.load(dir.resolve("01_local_matmul.mnd").toString(), reload = true).use { script ->
            val payload = """{"h0": [[3,1],[2,4]]}"""
            var last = ""
            repeat(2_000) {
                last = script.run(payload).text
            }
            assertEquals("""{"outputs":{"a":[[7,9],[2,4]]},"notes":[]}""", last)
        }
    }
}
