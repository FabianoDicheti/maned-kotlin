package dev.maned

/**
 * The facade object: load, io, setKernelThreads, searchPath (MANEDKT_PLAN.md §4.1).
 *
 * Scaffold: blocked on the maned_lang C ABI and libmaned_c (MANEDPY_PLAN.md
 * Phases 0-2b). Every member throws.
 *
 * `Maned.io[...]` caches like manedpy — keyed on (realpath, mtime, size, flags) —
 * with clearCache() and load(path, reload = true) as escape hatches (§4.1).
 */
object Maned {
    internal const val BLOCKED =
        "maned-kotlin is a scaffold: libmaned_c does not exist yet (MANEDPY_PLAN.md Phases 0-2b)"

    val searchPath: MutableList<String> = mutableListOf()

    val io: Io = Io

    object Io {
        operator fun get(name: String): Script = throw NotImplementedError(BLOCKED)
    }

    fun load(path: String, concurrent: Boolean = false, reload: Boolean = false): Script =
        throw NotImplementedError(BLOCKED)

    /** Process-global (src/interp/parallel.cpp:16) — per-process, not per-classloader. */
    fun setKernelThreads(n: Int): Unit = throw NotImplementedError(BLOCKED)

    fun clearCache(): Unit = throw NotImplementedError(BLOCKED)
}
