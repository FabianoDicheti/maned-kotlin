package dev.maned

/**
 * Linker, layouts, MethodHandles, the ABI version check (MANEDKT_PLAN.md §4).
 *
 * The mnd_response MemoryLayout must be asserted against maned_c_abi.json from
 * the capi tarball at build time (§3.1) — FFM layouts are hand-written and a
 * wrong offset reads garbage silently.
 *
 * Two standing rules (§4.4): a MemorySegment returned from a downcall has byte
 * size 0 until reinterpret(40); an Arena cannot own C-allocated memory — the
 * response goes to mnd_response_free in a finally.
 *
 * Linker.Option.critical() only ever on mnd_abi_version / mnd_script_is_routed
 * (O(1), return immediately). Never on mnd_request or mnd_open: a critical call
 * never reaches a safepoint, so a long request stalls every GC in the process
 * (§5.2).
 */
internal object Ffi
