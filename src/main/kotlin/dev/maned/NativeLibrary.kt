package dev.maned

/**
 * Extraction from the jar, discovery, the refusal messages (MANEDKT_PLAN.md §4.6).
 *
 * Resolution order: -Dmaned.library.path -> extracted-cache hit -> extract from
 * resources/native/<target>/. Cache: $XDG_CACHE_HOME/maned -> java.io.tmpdir,
 * filename content-hashed; write to a temp name and ATOMIC_MOVE. noexec mounts
 * are handled, not documented: try each candidate dir, and refuse with a message
 * naming every path tried plus the -Dmaned.library.path escape hatch.
 */
internal object NativeLibrary
