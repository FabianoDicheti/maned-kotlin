package dev.maned

import kotlinx.serialization.json.JsonObject

/**
 * A loaded .mnd script: AutoCloseable, per-Script lock, Cleaner registration
 * (MANEDKT_PLAN.md §4.4-4.5).
 *
 * Default: calls serialize on a per-Script lock (routed scripts are not
 * thread-safe). `Maned.load(path, concurrent = true)` is refused at load unless
 * mnd_script_is_routed() == 0.
 *
 * Calls are uninterruptible (§5.5): interrupt/cancel do not touch a native
 * downcall, and a withTimeout around runAsync resolves while the carrier thread
 * stays blocked in C.
 */
class Script internal constructor() : AutoCloseable {
    fun run(payload: JsonObject): Result = throw NotImplementedError(Maned.BLOCKED)

    val isRouted: Boolean get() = throw NotImplementedError(Maned.BLOCKED)

    override fun close(): Unit = throw NotImplementedError(Maned.BLOCKED)
}
