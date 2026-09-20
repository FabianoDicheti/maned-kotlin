package dev.maned

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * suspend overloads on Dispatchers.IO (MANEDKT_PLAN.md §4.5).
 *
 * Dispatchers.IO because it is platform-backed: a native downcall pins a
 * virtual thread's carrier for the whole call (§5.1 — the sharpest hazard in
 * the plan). Never Dispatchers.Default, never a virtual-thread dispatcher.
 * Services doing many concurrent runs should use a limitedParallelism slice
 * sized against setKernelThreads.
 */
suspend fun Script.runAsync(payload: JsonObject): Result =
    withContext(Dispatchers.IO) { run(payload) }
