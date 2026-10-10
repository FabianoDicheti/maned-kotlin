package dev.maned

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.math.BigDecimal

/**
 * The /run.json document + Tensor accessors (MANEDKT_PLAN.md §4.2).
 *
 * [bodyBytes] is the response exactly as `maned-serve` would have written it on
 * the wire — a Jackson shop can ignore everything else on this class.
 *
 * `LongArray` is exact, and that is checked, not assumed: `interp::Value` holds
 * `std::vector<int64_t>` for both `dims` and `data`
 * (`include/maned/interp/value.h:23-24`), and `src/serve/json.cpp:341` emits
 * `%lld` when `quantres == 0`. So `Long` <-> `int64_t` is lossless both ways.
 *
 * At `quantres > 0`, `json.cpp:343` emits `%.*f` of `q->decode(iv)` — fixed-point
 * text with exactly `quantres` fraction digits. [decimal] parses that text with
 * [BigDecimal], which preserves it **exactly**, scale included; [double] is the
 * lossy opt-in. manedpy has to reach for `parse_float=` to get this; Kotlin gets
 * it as the default.
 *
 * > Carried over from MANEDPY §2: routed flows execute on bark with mod-2^32
 * > wrap, local flows do not. `Long` faithfully reports whichever you got; it
 * > does not reconcile them.
 */
class Result internal constructor(
    val status: Int,
    val contentType: String,
    val bodyBytes: ByteArray,
    val notes: List<String>,
    val script: String? = null,
) {
    /** The body decoded as UTF-8. Error bodies are plain text, not JSON (§4.3). */
    val text: String by lazy { bodyBytes.decodeToString() }

    /**
     * The body parsed as a JSON object. Only meaningful for a 2xx: an error body
     * is plain text and parsing it throws. Branch on [status] first.
     */
    val json: JsonObject by lazy {
        if (status !in 200..299) {
            throw ManedInputException(
                "response status $status carries a plain-text body, not JSON " +
                    "(src/serve/server.cpp:154). Read .text, or branch on .status first.",
                status = status,
                body = text,
                script = script,
            )
        }
        Json.parseToJsonElement(text).jsonObject
    }

    /** `outputs` from /run.json; empty for a document that has none. */
    val outputs: JsonObject
        get() = json["outputs"]?.jsonObject ?: JsonObject(emptyMap())

    /** `profiles` from /run.json, when the script declares `profile::`. */
    val profiles: JsonObject?
        get() = json["profiles"]?.jsonObject

    /**
     * `frames` from /run.json.
     *
     * Note that a **`POST /frame`** response is the frame document itself — the
     * frames are the whole body, not nested under a key — so for that route read
     * [json] directly.
     */
    val frames: JsonObject?
        get() = json["frames"]?.jsonObject

    /** True when this response is an error (any non-2xx, including status 0). */
    val isError: Boolean get() = status !in 200..299

    internal fun throwIfError() {
        manedExceptionFor(status, text, script)?.let { throw it }
    }

    // -- tensors ----------------------------------------------------------------

    /**
     * A named output as a rectangular integer tensor.
     *
     * A ragged array is rejected rather than silently flattened — the engine
     * itself refuses ragged *input* with a 400, and an output that disagreed with
     * its own declared shape is a bug worth surfacing.
     *
     * A 1x1 result collapses to a bare scalar on the wire; that arrives here as a
     * rank-0 tensor (empty [Tensor.shape], one element).
     */
    fun tensor(name: String): Tensor {
        val element = element(name)
        val shape = mutableListOf<Long>()
        measure(element, 0, shape, name)
        val data = ArrayList<Long>()
        collectLongs(element, 0, shape, data, name)
        return Tensor(shape.toLongArray(), data.toLongArray())
    }

    /**
     * A named **scalar** output as an exact [BigDecimal] — the default accessor
     * for a `quantres > 0` script (§4.2). The scale of the returned value is the
     * script's `quantres`, because the text on the wire already has exactly that
     * many fraction digits: `"3.00"` comes back as `BigDecimal("3.00")`, not `3`.
     */
    fun decimal(name: String): BigDecimal {
        val element = element(name)
        val primitive = element as? JsonPrimitive
            ?: throw ManedInputException(
                "output '$name' is not a scalar; use decimals(\"$name\") for every " +
                    "element, or tensor(\"$name\") when quantres == 0.",
                status = null, body = null, script = script,
            )
        return bigDecimalOf(primitive, name)
    }

    /** Every element of a named output, row-major, as exact [BigDecimal]s. */
    fun decimals(name: String): List<BigDecimal> {
        val out = ArrayList<BigDecimal>()
        fun walk(e: JsonElement) {
            when (e) {
                is JsonPrimitive -> out.add(bigDecimalOf(e, name))
                is JsonArray -> e.forEach(::walk)
                else -> throw ManedInputException(
                    "output '$name' contains a JSON object where a number was expected.",
                    status = null, body = null, script = script,
                )
            }
        }
        walk(element(name))
        return out
    }

    /**
     * A named scalar output as a `Double`. **Lossy and opt-in**: it rounds the
     * engine's fixed-point text into a binary double. Prefer [decimal].
     */
    fun double(name: String): Double = decimal(name).toDouble()

    // -- internals --------------------------------------------------------------

    private fun bigDecimalOf(primitive: JsonPrimitive, name: String): BigDecimal {
        if (primitive.isString) {
            throw ManedInputException(
                "output '$name' is a JSON string, not a number.",
                status = null, body = null, script = script,
            )
        }
        // JsonPrimitive.content is the ORIGINAL literal text: "3.00" stays "3.00",
        // so BigDecimal keeps the scale the engine printed. Going through Double
        // here would be the exact loss this accessor exists to avoid.
        return runCatching { BigDecimal(primitive.content) }.getOrElse {
            throw ManedInputException(
                "output '$name' is not a number: '${primitive.content}'.",
                status = null, body = null, script = script,
            )
        }
    }

    /** Looks in `outputs` first, then at the document root (the /frame shape). */
    private fun element(name: String): JsonElement =
        outputs[name] ?: json[name] ?: throw ManedInputException(
            "no output named '$name'; this response has " +
                (outputs.keys + json.keys).joinToString(", ") { "'$it'" }.ifEmpty { "none" },
            status = status, body = null, script = script,
        )

    private fun measure(e: JsonElement, depth: Int, shape: MutableList<Long>, name: String) {
        if (e !is JsonArray) return
        shape.add(e.size.toLong())
        if (e.isNotEmpty()) measure(e[0], depth + 1, shape, name)
    }

    private fun collectLongs(
        e: JsonElement,
        depth: Int,
        shape: List<Long>,
        into: MutableList<Long>,
        name: String,
    ) {
        if (depth == shape.size) {
            val primitive = e as? JsonPrimitive ?: ragged(name, depth, "a nested array", "a number")
            if (primitive.isString) ragged(name, depth, "a string", "a number")
            val v = primitive.content.toLongOrNull() ?: throw ManedInputException(
                "output '$name' holds '${primitive.content}', which is not an int64. " +
                    "At quantres > 0 the wire carries fixed-point text — use " +
                    "decimal(\"$name\") / decimals(\"$name\") instead of tensor().",
                status = null, body = null, script = script,
            )
            into.add(v)
            return
        }
        val array = e as? JsonArray ?: ragged(name, depth, "a scalar", "an array of ${shape[depth]}")
        if (array.size.toLong() != shape[depth]) {
            ragged(name, depth, "a row of ${array.size}", "${shape[depth]}")
        }
        array.forEach { collectLongs(it, depth + 1, shape, into, name) }
    }

    private fun ragged(name: String, depth: Int, found: String, expected: String): Nothing =
        throw ManedInputException(
            "output '$name': ragged array ($found at depth $depth, expected $expected)",
            status = null, body = null, script = script,
        )
}

/**
 * A rectangular integer tensor: row-major [data] with the [shape] it came in.
 * An empty [shape] is a rank-0 scalar (a 1x1 result collapses on the wire).
 */
class Tensor(val shape: LongArray, val data: LongArray) {
    val rank: Int get() = shape.size

    init {
        val expected = shape.fold(1L) { acc, d -> acc * d }
        require(data.size.toLong() == expected) {
            "tensor shape ${shape.joinToString(prefix = "[", postfix = "]")} implies " +
                "$expected elements but ${data.size} were supplied"
        }
    }

    /** `[i, j, ...]` indexing for the common 1-D/2-D cases. */
    operator fun get(vararg index: Int): Long {
        require(index.size == shape.size) {
            "tensor has rank ${shape.size}, got ${index.size} indices"
        }
        var offset = 0L
        for (k in index.indices) {
            require(index[k] in 0 until shape[k].toInt()) {
                "index ${index[k]} out of bounds for axis $k of size ${shape[k]}"
            }
            offset = offset * shape[k] + index[k]
        }
        return data[offset.toInt()]
    }

    override fun toString(): String =
        "Tensor(shape=${shape.joinToString(prefix = "[", postfix = "]")}, " +
            "data=${data.joinToString(prefix = "[", postfix = "]")})"
}
