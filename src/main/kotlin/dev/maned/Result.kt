package dev.maned

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * The /run.json document + Tensor accessors (MANEDKT_PLAN.md §4.2).
 *
 * LongArray is exact: interp::Value holds std::vector<int64_t> for dims and
 * data. At quantres > 0 prefer decimal(name) -> BigDecimal (the wire text,
 * preserved exactly) over double(name) (lossy, opt-in).
 */
class Result internal constructor(
    val status: Int,
    val bodyBytes: ByteArray,
    val notes: List<String>,
) {
    val json: JsonObject by lazy {
        Json.parseToJsonElement(bodyBytes.decodeToString()) as JsonObject
    }

    fun tensor(name: String): Tensor = throw NotImplementedError(Maned.BLOCKED)
}

class Tensor(val shape: LongArray, val data: LongArray)
