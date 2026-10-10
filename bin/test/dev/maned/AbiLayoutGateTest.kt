package dev.maned

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.lang.foreign.MemoryLayout
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Gate 2 of MANEDKT_PLAN.md §6: the hand-written `MemoryLayout` versus what the
 * engine states in `maned_c_abi.json`.
 *
 * This duplicates the check [Ffi] runs at class-init on purpose. The class-init
 * version protects users; this one reports *which field* moved when a new capi
 * artifact lands, and fails the build rather than a user's process.
 */
class AbiLayoutGateTest {

    private val manifestPath: Path = TestEnv.abiManifest()

    private val manifest: JsonObject =
        Json.parseToJsonElement(Files.readString(manifestPath)).jsonObject

    @Test
    fun `manifest exists beside the library`() {
        assertTrue(Files.isRegularFile(manifestPath), "no maned_c_abi.json at $manifestPath")
    }

    @Test
    fun `sizeof mnd_response matches the manifest`() {
        val declared = manifest["structs"]!!.jsonObject["mnd_response"]!!
            .jsonObject["sizeof"]!!.jsonPrimitive.content.toLong()
        assertEquals(
            declared,
            Ffi.RESPONSE_LAYOUT.byteSize(),
            "sizeof(mnd_response) disagrees with $manifestPath",
        )
        assertEquals(40L, Ffi.RESPONSE_LAYOUT.byteSize(), "LP64 sizeof is 40 (§3.1)")
    }

    @Test
    fun `every field offset matches the manifest`() {
        val fields = manifest["structs"]!!.jsonObject["mnd_response"]!!
            .jsonObject["fields"]!!.jsonObject
        assertEquals(
            setOf("status", "content_type", "body", "body_len", "notes"),
            fields.keys,
            "the manifest describes a different mnd_response than this binding knows",
        )
        for ((name, offset) in fields) {
            assertEquals(
                offset.jsonPrimitive.content.toLong(),
                Ffi.RESPONSE_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement(name)),
                "mnd_response.$name is at the wrong offset — every read past it is garbage",
            )
        }
    }

    @Test
    fun `the padding is where the C compiler puts it`() {
        // int32_t status at 0, then 4 bytes of padding before the first pointer.
        assertEquals(
            0L,
            Ffi.RESPONSE_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("status")),
        )
        assertEquals(
            8L,
            Ffi.RESPONSE_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("content_type")),
            "paddingLayout(4) is missing: content_type would land at 4",
        )
    }

    @Test
    fun `abi version is accepted as a range and the library agrees`() {
        assertEquals(1..1, Ffi.SUPPORTED_ABI)
        assertEquals(1, manifest["abi_version"]!!.jsonPrimitive.content.toInt())
        assertTrue(Maned.abiVersion() in Ffi.SUPPORTED_ABI)
        assertEquals(1, Maned.abiVersion())
    }

    @Test
    fun `a manifest with a shifted offset is refused`() {
        val bad = buildJsonObject {
            put("abi_version", 1)
            put(
                "structs",
                buildJsonObject {
                    put(
                        "mnd_response",
                        buildJsonObject {
                            put("sizeof", 40)
                            put(
                                "fields",
                                buildJsonObject {
                                    put("status", 0)
                                    put("content_type", 4) // the missing-padding bug
                                    put("body", 16)
                                    put("body_len", 24)
                                    put("notes", 32)
                                },
                            )
                        },
                    )
                },
            )
        }
        val e = assertFailsWith<ManedLoadException> { Ffi.assertLayout(bad, "synthetic") }
        assertTrue("content_type" in e.message!!, e.message!!)
        assertTrue("offset 4" in e.message!!, e.message!!)
    }

    @Test
    fun `a manifest with a different sizeof is refused`() {
        val bad = buildJsonObject {
            put("abi_version", 1)
            put(
                "structs",
                buildJsonObject {
                    put(
                        "mnd_response",
                        buildJsonObject {
                            // A 32-bit build: pointers and size_t halve.
                            put("sizeof", 20)
                            put(
                                "fields",
                                buildJsonObject {
                                    put("status", 0)
                                    put("content_type", 4)
                                    put("body", 8)
                                    put("body_len", 12)
                                    put("notes", 16)
                                },
                            )
                        },
                    )
                },
            )
        }
        val e = assertFailsWith<ManedLoadException> { Ffi.assertLayout(bad, "synthetic-32bit") }
        assertTrue("sizeof" in e.message!!, e.message!!)
    }

    @Test
    fun `the engine reports a version string`() {
        assertTrue(Maned.version().isNotBlank(), "mnd_version returned an empty string")
    }
}
