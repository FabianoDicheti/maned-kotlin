package dev.maned

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * The facade object: load, io, setKernelThreads, searchPath (MANEDKT_PLAN.md §4.1).
 *
 * ```kotlin
 * val result = Maned.io["scoring"].run(payload)   // the JSON /run.json returns
 * ```
 *
 * Kotlin has no `__getattr__`, so manedpy's `mnd.io.<name>` does not translate;
 * [Io.get] is the v1 spelling, an `operator fun get` over the same search path
 * with the same refusal-by-name naming every directory tried. (A KSP processor
 * generating `Maned.io.scoring(x = ..., w = ...)` is post-v1: `/health` reports
 * names and wire formats only — no dtypes, no shapes — so a generated API can be
 * typed on arity and names, not on shape.)
 *
 * ### Caching
 *
 * [Io.get] and [load] cache, keyed on **(realpath, mtime, size, flags)**, with
 * [clearCache] and `load(path, reload = true)` as the escape hatches. On the JVM
 * the indexer sits inside request handlers, so an uncached `get` is a re-parse
 * per request; an *unkeyed* cache would break the edit-and-rerun loop. The policy
 * is stated because the three bindings deliberately differ — manedpy caches by
 * mtime, maned-rs does not cache at all — and a binding that silently picks a
 * third behaviour is a support ticket.
 */
object Maned {

    // -- mnd_open flags ---------------------------------------------------------

    /** `MND_SYNTHESIZE_OUTPUTS (1u << 0)` — return-only scripts become servable. */
    const val SYNTHESIZE_OUTPUTS: Int = 1 shl 0

    /** `MND_NO_VM_FALLBACK (1u << 1)` — an unreachable device is a hard error. */
    const val NO_VM_FALLBACK: Int = 1 shl 1

    /**
     * Extra directories searched by [Io.get], after `$MANEDPATH` and before the
     * working directory. Mutable on purpose: an application sets it at startup.
     */
    val searchPath: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())

    val io: Io = Io

    object Io {
        /**
         * `Maned.io["scoring"]` — the first `scoring.mnd` on the search path
         * (`$MANEDPATH` -> [searchPath] -> the working directory -> `./mnd/`),
         * loaded with default flags and cached.
         *
         * A miss names every directory tried.
         */
        operator fun get(name: String): Script {
            val fileName = if (name.endsWith(".mnd")) name else "$name.mnd"
            val tried = mutableListOf<String>()
            for (dir in searchDirectories()) {
                val candidate = Path.of(dir, fileName).toAbsolutePath().normalize()
                tried += candidate.toString()
                if (Files.isRegularFile(candidate)) return load(candidate.toString())
            }
            throw ManedLoadException(
                buildString {
                    append("no script named '$name' (looked for '$fileName').\n")
                    append("Searched, in order:\n")
                    tried.forEach { append("  - ").append(it).append('\n') }
                    append(
                        "The search path is \$MANEDPATH, then Maned.searchPath, then the " +
                            "working directory, then ./mnd/. Add a directory with " +
                            "Maned.searchPath.add(...), or call Maned.load(\"/abs/path.mnd\")."
                    )
                },
                script = name,
            )
        }
    }

    /** `$MANEDPATH` -> [searchPath] -> CWD -> `./mnd/`, de-duplicated in order. */
    internal fun searchDirectories(): List<String> {
        val dirs = LinkedHashSet<String>()
        System.getenv("MANEDPATH")?.split(File.pathSeparatorChar)
            ?.filter { it.isNotBlank() }
            ?.forEach { dirs.add(it) }
        synchronized(searchPath) { dirs.addAll(searchPath) }
        dirs.add(".")
        dirs.add("./mnd")
        return dirs.toList()
    }

    // -- loading ----------------------------------------------------------------

    private data class CacheKey(
        val realPath: String,
        val lastModified: Long,
        val size: Long,
        val flags: Int,
        val concurrent: Boolean,
    )

    private val cache = ConcurrentHashMap<CacheKey, Script>()

    /**
     * Parse -> lint -> inline -> plan -> validate, once, and hand back a handle.
     *
     * @param concurrent drop the per-[Script] lock. **Refused at load** unless
     *   `mnd_script_is_routed() == 0`: a routed script drives single-connection
     *   device channels and is not thread-safe. The engine's own
     *   `testConcurrentHandleIsIdenticalGap025` proves the non-routed case safe
     *   for 8x25 concurrent calls.
     * @param reload bypass and replace the cache entry — the edit-and-rerun hatch.
     * @param synthesizeOutputs `MND_SYNTHESIZE_OUTPUTS`: serve a script that
     *   declares no `out::`/`profile::`/`frame::` by synthesizing outputs from
     *   what its flows return.
     * @param noVmFallback `MND_NO_VM_FALLBACK`: an unreachable device becomes a
     *   hard error instead of falling back to the local Bark VM.
     */
    @JvmOverloads
    fun load(
        path: String,
        concurrent: Boolean = false,
        reload: Boolean = false,
        synthesizeOutputs: Boolean = false,
        noVmFallback: Boolean = false,
    ): Script {
        val file = Path.of(path).toAbsolutePath().normalize()
        if (!Files.isRegularFile(file)) {
            throw ManedLoadException("no such .mnd file: '$file'", script = path)
        }
        val real = file.toRealPath()
        val attributes = Files.readAttributes(real, BasicFileAttributes::class.java)

        var flags = 0
        if (synthesizeOutputs) flags = flags or SYNTHESIZE_OUTPUTS
        if (noVmFallback) flags = flags or NO_VM_FALLBACK

        val key = CacheKey(
            realPath = real.toString(),
            lastModified = attributes.lastModifiedTime().toMillis(),
            size = attributes.size(),
            flags = flags,
            concurrent = concurrent,
        )

        if (reload) {
            cache.remove(key)
        } else {
            cache[key]?.let { if (!it.isClosed) return it else cache.remove(key, it) }
        }

        val opened = Ffi.open(real.toString(), flags)
        val routed = Ffi.isRouted(opened.handle)

        if (concurrent && routed) {
            Ffi.close(opened.handle)
            throw ManedLoadException(
                "'$real' routes at least one flow to a device, so concurrent = true " +
                    "is refused: a routed script drives single-connection device " +
                    "channels (DeviceSession/VmChannel) and concurrent mnd_request " +
                    "calls on one handle are safe if and only if " +
                    "mnd_script_is_routed(s) == 0. Load it without concurrent = true " +
                    "(calls then serialize on a per-Script lock), or load one handle " +
                    "per thread — though two handles routing to the SAME device race " +
                    "that device's single-connection listener.",
                script = real.toString(),
            )
        }

        val script = Script(
            handle = opened.handle,
            path = real.toString(),
            concurrent = concurrent,
            isRouted = routed,
            loadNotes = opened.notes,
        )
        remember(script)

        if (reload) {
            cache[key] = script
            return script
        }
        val raced = cache.putIfAbsent(key, script)
        if (raced != null && !raced.isClosed) {
            // Another thread loaded the same (realpath, mtime, size, flags) first.
            script.close()
            return raced
        }
        cache[key] = script
        return script
    }

    /**
     * Drops every cached handle. Cached scripts are **not** closed here — a caller
     * may still be holding one — so they are freed by [Script.close], the Cleaner,
     * or the shutdown hook, whichever comes first.
     */
    fun clearCache() {
        cache.clear()
    }

    // -- process-global knobs ---------------------------------------------------

    /**
     * The interpreter worker-thread count (`interp/parallel`).
     *
     * **Per-process, not per-classloader.** `mnd_set_kernel_threads` writes one
     * process-global `std::atomic<int>` (`src/interp/parallel.cpp:16`), and
     * `dlopen` refcounts one library across every classloader that looks it up —
     * so an app server, the Gradle daemon and Spring devtools "isolated"
     * applications silently share this setting. Exposed only here, never per
     * [Script], for exactly that reason. It changes timing, never values.
     *
     * Sizing note (§4.5): every in-flight call may spawn this many interpreter
     * workers, so `Dispatchers.IO`'s 64-thread ceiling times this is the real
     * oversubscription. Pair a raised value with
     * `Dispatchers.IO.limitedParallelism(n)`.
     */
    fun setKernelThreads(n: Int) {
        require(n >= 0) { "kernel thread count must be >= 0, got $n" }
        Ffi.setKernelThreads(n)
    }

    /** The running library's engine version string, e.g. `"1.2.3"`. */
    fun version(): String = Ffi.version()

    /** The running library's ABI generation. Accepted as a RANGE, never `==`. */
    fun abiVersion(): Int = Ffi.abiVersion()

    // -- live handles and the shutdown hook -------------------------------------

    /**
     * Weakly held so a handle the caller dropped can still be reclaimed by the
     * Cleaner. (Handles in [cache] are strongly reachable from there, which is
     * the documented cost of caching.)
     */
    private val live: MutableSet<Script> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<Script, Boolean>()))

    /**
     * Hazard 7: the Bark VM child is SIGKILLed from the `VmChannel` destructor,
     * reached via [Script.close]. A `Runtime.halt()`, a SIGKILL or an uncaught
     * OOM skips both `close()` and the Cleaner, and the child outlives the JVM.
     * A shutdown hook covers everything except `halt()`, which nothing can.
     *
     * Null only if the JVM was already shutting down when this class initialised.
     */
    internal val shutdownHook: Thread? =
        Thread({ closeAll() }, "maned-shutdown").let { hook ->
            runCatching { Runtime.getRuntime().addShutdownHook(hook) }
                .map { hook }
                .getOrNull()
        }

    private fun remember(script: Script) {
        live.add(script)
    }

    internal fun forget(script: Script) {
        live.remove(script)
    }

    /** Closes every handle this classloader still knows about. */
    internal fun closeAll() {
        val snapshot = synchronized(live) { live.toList() }
        snapshot.forEach { runCatching { it.close() } }
        cache.clear()
    }
}
