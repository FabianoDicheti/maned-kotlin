package dev.maned

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.lang.foreign.MemorySegment
import java.lang.ref.Cleaner
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A loaded .mnd script: AutoCloseable, per-Script lock, Cleaner registration
 * (MANEDKT_PLAN.md §4.4-4.5).
 *
 * Default: calls serialize on a per-Script lock, because routed scripts are not
 * thread-safe — they drive single-connection device channels
 * (`DeviceSession`/`VmChannel`), which is why
 * `tools/maned-serve/maned_serve.cpp:124` refuses `--workers > 1`.
 * [Maned.load] with `concurrent = true` drops the lock and is **refused at load**
 * unless `mnd_script_is_routed() == 0`.
 *
 * This matters more on the JVM than in Python: a Python caller reaches for
 * threads deliberately, a Kotlin caller gets them by default from any web
 * framework or thread pool. The lock will actually be contended, and
 * `concurrent = true` on a non-routed script is the difference between a
 * serialized endpoint and a parallel one.
 *
 * Calls are **uninterruptible** (§5.5): `Thread.interrupt()`,
 * `Future.cancel(true)` and coroutine cancellation do not touch a native
 * downcall. A `withTimeout { }` around [runAsync] will *appear* to work — the
 * coroutine resumes — while the carrier thread stays blocked in C until the call
 * returns. Real cancellation needs an `mnd_cancel` that does not exist.
 */
class Script internal constructor(
    handle: MemorySegment,
    /** The realpath this script was loaded from. */
    val path: String,
    /** True when the per-[Script] lock was dropped at load time. */
    val concurrent: Boolean,
    /** `mnd_script_is_routed` — 1 when any flow routes to a device. */
    val isRouted: Boolean,
    /** Warnings and notes the engine emitted while loading. */
    val loadNotes: List<String>,
) : AutoCloseable {

    private val native = NativeHandle(handle)
    private val cleanable: Cleaner.Cleanable = CLEANER.register(this, native)

    /** Null when [concurrent]; otherwise every call serializes on it. */
    private val lock: ReentrantLock? = if (concurrent) null else ReentrantLock()

    /**
     * Guards [inFlight] and [closing]. A plain `java.lang.Object` because close()
     * needs `wait`/`notifyAll`, which Kotlin does not surface on `Any`.
     */
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val gate = Object()
    private var inFlight: Int = 0
    private var closing: Boolean = false

    val isClosed: Boolean get() = native.freed.get()

    // -- calls ------------------------------------------------------------------

    /**
     * `POST /run.json` with [payload] as the body, and the §4.3 status map
     * applied: a 400 throws [ManedInputException], a 500 throws
     * [ManedRuntimeException], and so on. Use [request] to see an error response
     * as data instead.
     */
    fun run(payload: JsonObject): Result = run(payload.toString())

    /** [run], for a caller whose JSON already exists as text (a Jackson shop). */
    fun run(payload: String): Result =
        request("POST", "/run.json", "application/json", payload.toByteArray(Charsets.UTF_8))
            .also { it.throwIfError() }

    /**
     * The raw escape hatch: any method, any target, any body, **no status map**.
     * Errors come back as a [Result] with a non-2xx [Result.status] and a
     * plain-text body. This is the shape the conformance corpus is walked with,
     * and the way to reach routes this binding has no sugar for.
     *
     * @param contentType may be null or "" when the route does not need one —
     *   only multipart `/run` and `/frame` do.
     */
    @JvmOverloads
    fun request(
        method: String,
        target: String,
        contentType: String? = null,
        body: ByteArray = ByteArray(0),
    ): Result {
        warnIfVirtualThread()
        begin()
        try {
            val raw =
                if (lock == null) Ffi.request(native.segment, method, target, contentType, body)
                else lock.withLock { Ffi.request(native.segment, method, target, contentType, body) }
            return Result(
                status = raw.status,
                contentType = raw.contentType,
                bodyBytes = raw.body,
                notes = Ffi.parseNotes(raw.notes),
                script = path,
            )
        } finally {
            end()
        }
    }

    // -- the file routes --------------------------------------------------------

    /**
     * `POST /run` as multipart — **the file route**: raw bytes in, raw bytes
     * out, one part per declared `out::`.
     *
     * This is the only way to get the engine's actual file output. [run] goes to
     * `/run.json`, which renders an `out::` image as a nested integer array and
     * a csv as a JSON tensor; here a PNG comes back as a PNG.
     *
     * Each entry of [files] is an `in::` name and the raw bytes for it — CSV
     * text, a PNG, a parquet file — in whatever format that input declares. The
     * map's iteration order is the part order on the wire.
     *
     * ```kotlin
     * val parts = script.runFiles("h0" to Files.readAllBytes(rows))
     * Files.write(out, parts.getValue("heatmap"))
     * ```
     *
     * A single-output script answers with the bare file and no part name at all,
     * so the one declared `out::` name is used as the key — the return type must
     * not depend on how many outputs the script happens to have.
     */
    fun runFiles(files: Map<String, ByteArray>): Map<String, ByteArray> {
        val boundary = Multipart.boundaryFor(files)
        val body = Multipart.encode(boundary, files)
        val response = request("POST", "/run", "multipart/form-data; boundary=$boundary", body)
        response.throwIfError()
        if (response.contentType.lowercase().startsWith("multipart/")) {
            return Multipart.decode(response.contentType, response.bodyBytes)
        }
        val names = outputs.map { it.name }
        return mapOf((if (names.size == 1) names[0] else "body") to response.bodyBytes)
    }

    /** [runFiles] for the common one-or-two-input call. */
    fun runFiles(vararg files: Pair<String, ByteArray>): Map<String, ByteArray> =
        runFiles(files.toMap())

    /**
     * `POST /frame` — the `frame::` tables as a JSON document, status-mapped
     * like [run].
     *
     * The frame document **is** the body on this route; unlike `/run.json` the
     * tables are not nested under a `frames` key, so read [Result.json] directly.
     */
    fun frame(payload: JsonObject): Result = frame(payload.toString())

    /** [frame], for a caller whose JSON already exists as text. */
    fun frame(payload: String): Result =
        request("POST", "/frame", "application/json", payload.toByteArray(Charsets.UTF_8))
            .also { it.throwIfError() }

    /**
     * `POST /frame.csv` — the same tables as CSV text, bytes as written.
     *
     * Returned undecoded: a `text` column carries raw bytes `0..255` and is
     * never promised to be UTF-8.
     */
    fun frameCsv(payload: JsonObject): ByteArray = frameCsv(payload.toString())

    /** [frameCsv], for a caller whose JSON already exists as text. */
    fun frameCsv(payload: String): ByteArray =
        request("POST", "/frame.csv", "application/json", payload.toByteArray(Charsets.UTF_8))
            .also { it.throwIfError() }
            .bodyBytes

    // -- the contract -----------------------------------------------------------

    /**
     * `GET /health` — this script's contract, fetched at most once.
     *
     * Cached because `mnd_open` builds the plan and freezes it: the contract
     * cannot change while the handle is open, so re-asking would buy nothing but
     * a round trip per [runFiles] call.
     *
     * `requests_served` is removed. It counts calls on **this handle**, so it
     * looks like a server metric and is not one.
     */
    val health: JsonObject by lazy {
        val response = request("GET", "/health", null, ByteArray(0))
        response.throwIfError()
        JsonObject(
            Json.parseToJsonElement(response.text).jsonObject
                .filterKeys { it != "requests_served" },
        )
    }

    /** The `in::` contract — what a payload may, or must, carry. */
    val inputs: List<IoSpec> get() = ioSpecs("inputs")

    /**
     * The response schema — the `out::` names [runFiles] returns parts for.
     *
     * Under `synthesizeOutputs` this lists the *synthesized* names, because
     * synthesis happens in the engine's own `script.outputs`; this and what
     * [run] returns cannot disagree.
     */
    val outputs: List<IoSpec> get() = ioSpecs("outputs")

    /**
     * The routes this script actually answers — `"POST /frame.csv"` appears only
     * when it declares a `frame::`.
     */
    val routes: List<String>
        get() = (health["routes"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?: emptyList()

    private fun ioSpecs(key: String): List<IoSpec> {
        val array = health[key] as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val entry = element as? JsonObject ?: return@mapNotNull null
            val name = (entry["name"] as? JsonPrimitive)?.contentOrNull
                ?: return@mapNotNull null
            IoSpec(
                name = name,
                format = (entry["format"] as? JsonPrimitive)?.contentOrNull ?: "",
                default = (entry["default"] as? JsonPrimitive)?.booleanOrNull,
            )
        }
    }

    // -- lifetime ---------------------------------------------------------------

    /**
     * Closes the handle. **Idempotent and draining**: when [concurrent] is true
     * another thread may be inside `mnd_request` right now, and freeing the
     * handle under it is a use-after-free in C — so close waits for every
     * in-flight call to return before calling `mnd_close`. (Both sibling bindings
     * do the same thing, after a review found exactly this bug.)
     *
     * Calls that have not started yet are refused from the moment close begins.
     */
    override fun close() {
        var interrupted = false
        synchronized(gate) {
            closing = true
            while (inFlight > 0) {
                try {
                    gate.wait()
                } catch (e: InterruptedException) {
                    // The in-flight native call cannot be interrupted (§5.5), so
                    // returning here would free the handle under it. Keep waiting,
                    // and remember the interrupt for after the loop — re-setting
                    // the flag now would make the next wait() throw immediately
                    // and spin this thread at 100% until the call returned.
                    interrupted = true
                }
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
        // Runs NativeHandle exactly once and deregisters the Cleaner, so a later
        // GC cannot double-free.
        cleanable.clean()
        Maned.forget(this)
    }

    private fun begin() {
        synchronized(gate) {
            if (closing || native.freed.get()) {
                throw IllegalStateException("script '$path' is closed")
            }
            inFlight++
        }
    }

    private fun end() {
        synchronized(gate) {
            inFlight--
            if (inFlight == 0) gate.notifyAll()
        }
    }

    // Package-visible hooks so the drain can be tested at the counter level
    // without a script that blocks for a measurable time.
    internal val inFlightCount: Int get() = synchronized(gate) { inFlight }
    internal fun beginForTest() = begin()
    internal fun endForTest() = end()

    override fun toString(): String =
        "Script($path, routed=$isRouted, concurrent=$concurrent, closed=$isClosed)"

    /**
     * The Cleaner action. Holds the handle and nothing else — capturing the
     * [Script] would keep it reachable and the Cleaner would never fire.
     */
    private class NativeHandle(val segment: MemorySegment) : Runnable {
        val freed = AtomicBoolean(false)
        override fun run() {
            if (freed.compareAndSet(false, true)) Ffi.close(segment)
        }
    }

    internal companion object {
        /** The JVM analogue of manedpy's `__del__` guard: a forgotten handle is
         *  eventually freed rather than leaked for the process lifetime (§4.4). */
        private val CLEANER: Cleaner = Cleaner.create { r ->
            Thread(r, "maned-cleaner").apply { isDaemon = true }
        }

        private const val WARN_PROPERTY = "maned.warnOnVirtualThread"

        /**
         * Hazard 1, the sharpest in the plan: a thread executing a native downcall
         * **cannot be unmounted from its carrier**. A virtual thread calling into
         * maned pins one carrier for the entire duration of a matmul, and the
         * default carrier pool is `availableProcessors()`, so a handful of
         * concurrent calls starve the scheduler for every unrelated task in the
         * application. Nothing logs. Nothing throws. This does.
         *
         * On by default — the failure is silent and presents as an unrelated HTTP
         * handler going unresponsive. `-D$WARN_PROPERTY=false` silences it.
         */
        private fun warnIfVirtualThread() {
            if (System.getProperty(WARN_PROPERTY, "true").equals("false", ignoreCase = true)) return
            if (!Thread.currentThread().isVirtual) return
            Log.warnOnce(
                "a maned call is running on a virtual thread. A native downcall " +
                    "pins its carrier for the whole call, so concurrent maned calls " +
                    "starve the ForkJoinPool that every other virtual thread in this " +
                    "JVM shares — the symptom is unrelated tasks going unresponsive, " +
                    "with nothing logged. Run maned calls on platform threads: " +
                    "Script.runAsync dispatches to Dispatchers.IO, which is " +
                    "platform-backed. Silence with -$WARN_PROPERTY=false."
            )
        }
    }
}
