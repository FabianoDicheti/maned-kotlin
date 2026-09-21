package dev.maned

import kotlinx.serialization.json.JsonObject
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
