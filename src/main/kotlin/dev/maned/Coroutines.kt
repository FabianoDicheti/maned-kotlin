package dev.maned

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * suspend overloads on Dispatchers.IO (MANEDKT_PLAN.md §4.5).
 *
 * `Dispatchers.IO` because it is **platform-backed**: a native downcall pins a
 * virtual thread's carrier for the whole call (§5.1 — the sharpest hazard in the
 * plan). Never `Dispatchers.Default`, never a virtual-thread dispatcher.
 *
 * ### Sizing
 *
 * `Dispatchers.IO` defaults to a **64-thread ceiling**, and every in-flight call
 * may itself spawn [Maned.setKernelThreads] interpreter workers (`parallel_rows`).
 * 64 concurrent runs times 8 kernel threads is 512 runnable threads on a machine
 * that has 8 cores — multiplicative oversubscription arriving through the
 * dispatcher instead of through rayon. A service doing many concurrent runs
 * should give maned calls a slice:
 *
 * ```kotlin
 * val manedDispatcher = Dispatchers.IO.limitedParallelism(4)   // x setKernelThreads(2)
 * withContext(manedDispatcher) { script.run(payload) }
 * ```
 *
 * ### Cancellation
 *
 * There is deliberately no timeout overload. A `withTimeout { runAsync(...) }`
 * *appears* to work — the coroutine resumes on time — while the dispatcher thread
 * stays blocked inside C until the call finishes (§5.5). That is a worse failure
 * than an honest hang, because it looks handled. Real cancellation needs an
 * `mnd_cancel` and an interpreter poll point; out of scope for v1.
 */
suspend fun Script.runAsync(
    payload: JsonObject,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
): Result = withContext(dispatcher) { run(payload) }

/** [runAsync] for JSON that already exists as text. */
suspend fun Script.runAsync(
    payload: String,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
): Result = withContext(dispatcher) { run(payload) }

/**
 * `POST /run` as multipart, off the calling thread — the suspend half of
 * [Script.runFiles]. Same dispatcher rules and cancellation caveat as [runAsync].
 */
suspend fun Script.runFilesAsync(
    files: Map<String, ByteArray>,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
): Map<String, ByteArray> = withContext(dispatcher) { runFiles(files) }

/** `POST /frame`, off the calling thread; see [runAsync] for the caveats. */
suspend fun Script.frameAsync(
    payload: JsonObject,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
): Result = withContext(dispatcher) { frame(payload) }

/** `POST /frame.csv`, off the calling thread; see [runAsync] for the caveats. */
suspend fun Script.frameCsvAsync(
    payload: JsonObject,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
): ByteArray = withContext(dispatcher) { frameCsv(payload) }

/**
 * The raw [Script.request] escape hatch, off the calling thread. Same dispatcher
 * rules and the same cancellation caveat as [runAsync]; no status map.
 */
suspend fun Script.requestAsync(
    method: String,
    target: String,
    contentType: String? = null,
    body: ByteArray = ByteArray(0),
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
): Result = withContext(dispatcher) { request(method, target, contentType, body) }
