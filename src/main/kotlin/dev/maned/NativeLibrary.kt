package dev.maned

import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/**
 * Extraction from the jar, discovery, the refusal messages (MANEDKT_PLAN.md §4.6).
 *
 * `manedpy` had it easy — a wheel unpacks into `site-packages` as real files.
 * **A JAR entry is not a file**, and [java.lang.foreign.SymbolLookup.libraryLookup]
 * takes a [Path], so the library must be extracted before it can be loaded, and
 * every step of that is a failure mode in somebody's container.
 *
 * Resolution order (§4.6):
 *  1. `-Dmaned.library.path=<file>` (or `$MANED_LIBRARY_PATH`) — the escape hatch;
 *  2. an already-extracted **cache hit**, keyed by a content hash in the filename;
 *  3. extraction from `resources/native/<target>/`.
 *
 * Cache location: `$XDG_CACHE_HOME/maned` → `~/.cache/maned` → `java.io.tmpdir/maned`.
 * The filename carries a content hash, so an upgraded jar never loads a stale
 * library and two versions coexist.
 *
 * Concurrency: two classloaders, or two JVMs, may extract at once. We write to a
 * temp name and [StandardCopyOption.ATOMIC_MOVE] into place; the final path is
 * never written directly.
 *
 * `noexec` mounts: hardened containers mount `/tmp` with `noexec`, and `dlopen`
 * of a file there **fails**. That is common enough in Kubernetes that it is
 * handled, not documented — each candidate directory is tried in turn (extract,
 * then a real `dlopen` probe), and if all of them fail we refuse with a message
 * naming **every path tried** and the `-Dmaned.library.path` escape hatch. That
 * refusal message is the API contract here.
 */
internal object NativeLibrary {

    const val LIBRARY_PATH_PROPERTY: String = "maned.library.path"
    const val LIBRARY_PATH_ENV: String = "MANED_LIBRARY_PATH"

    /** Bumped only when bytes are actually written; the cache-reuse gate reads it. */
    internal val writesPerformed: AtomicInteger = AtomicInteger(0)

    /** Every path the last [resolve] tried, in order — quoted verbatim in refusals. */
    @Volatile
    internal var lastAttempts: List<String> = emptyList()

    private val resolved: Path by lazy { resolve() }

    /** The library file to hand to `libraryLookup`, resolved once per classloader. */
    fun path(): Path = resolved

    // -- target -----------------------------------------------------------------

    /**
     * The `resources/native/<target>/` directory name for this JVM.
     *
     * Only the three targets the capi tarball publishes exist (§1): macOS ships
     * one universal arm64+x86_64 slice, Linux ships per-arch.
     */
    fun currentTarget(
        os: String = System.getProperty("os.name").orEmpty().lowercase(),
        arch: String = System.getProperty("os.arch").orEmpty().lowercase(),
    ): String = when {
        os.contains("mac") || os.contains("darwin") -> "macos-universal"
        os.contains("linux") && (arch == "aarch64" || arch == "arm64") -> "linux-aarch64"
        os.contains("linux") -> "linux-x86_64"
        else -> "unsupported-$os-$arch"
    }

    /** `dylib` on macOS, `so` on Linux. Windows is not a published target. */
    fun libraryExtension(os: String = System.getProperty("os.name").orEmpty().lowercase()): String =
        if (os.contains("mac") || os.contains("darwin")) "dylib" else "so"

    fun resourceName(target: String = currentTarget()): String =
        "native/$target/libmaned_c.${libraryExtension()}"

    // -- resolution -------------------------------------------------------------

    fun resolve(): Path {
        val attempts = mutableListOf<String>()

        // 1. the explicit override, checked first and never second-guessed.
        val override = System.getProperty(LIBRARY_PATH_PROPERTY)
            ?: System.getenv(LIBRARY_PATH_ENV)
        if (!override.isNullOrBlank()) {
            val p = Paths.get(override).toAbsolutePath().normalize()
            attempts += "-D$LIBRARY_PATH_PROPERTY=$p"
            lastAttempts = attempts
            if (!Files.isRegularFile(p)) {
                throw ManedLoadException(
                    "$LIBRARY_PATH_PROPERTY points at '$p', which is not a regular file."
                )
            }
            return p
        }

        // 2 + 3. cache hit, else extract — both happen inside extractFromResources,
        // which tries every candidate directory before giving up.
        return extractFromResources(attempts = attempts)
    }

    /**
     * Steps 2 and 3 of §4.6, together: for each candidate directory in turn, use
     * an existing content-hash-named copy if there is one, otherwise extract, then
     * prove the result is actually `dlopen`-able from there (the `noexec` check).
     *
     * @param attempts accumulates every path tried, for the refusal message.
     */
    fun extractFromResources(
        target: String = currentTarget(),
        loader: ClassLoader = NativeLibrary::class.java.classLoader,
        candidates: List<Path> = cacheCandidates(),
        attempts: MutableList<String> = mutableListOf(),
    ): Path {
        val resource = resourceName(target)
        val bytes = loader.getResourceAsStream(resource)?.use(InputStream::readBytes)
            ?: throw ManedLoadException(
                buildString {
                    append("no bundled native library for target '$target'. ")
                    append("Looked for classpath resource '$resource'. ")
                    append(escapeHatch())
                }
            )

        val fileName = hashedName(bytes, target)
        val failures = mutableListOf<String>()

        for (dir in candidates) {
            val destination = dir.resolve(fileName)
            attempts += destination.toString()
            try {
                if (!Files.isRegularFile(destination)) {
                    Files.createDirectories(dir)
                    writeAtomically(bytes, destination)
                    writesPerformed.incrementAndGet()
                }
                makeReadableAndExecutable(destination)
                // The noexec gate: only a real dlopen proves this mount works.
                probeLoadable(destination)
                lastAttempts = attempts
                return destination
            } catch (t: Throwable) {
                failures += "$destination: ${t.javaClass.simpleName}: ${t.message}"
            }
        }

        lastAttempts = attempts
        throw ManedLoadException(
            buildString {
                append("could not stage libmaned_c for target '$target' anywhere it can be loaded from.\n")
                append("Tried, in order:\n")
                failures.forEach { append("  - ").append(it).append('\n') }
                append(
                    "A 'noexec' mount (common for /tmp in hardened containers) makes dlopen " +
                        "fail even though the write succeeded.\n"
                )
                append(escapeHatch())
            }
        )
    }

    /** `$XDG_CACHE_HOME/maned` → `~/.cache/maned` → `java.io.tmpdir/maned`. */
    fun cacheCandidates(): List<Path> {
        val dirs = LinkedHashSet<Path>()
        System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }?.let {
            dirs.add(Paths.get(it, "maned"))
        }
        System.getProperty("user.home")?.takeIf { it.isNotBlank() }?.let {
            dirs.add(Paths.get(it, ".cache", "maned"))
        }
        System.getProperty("java.io.tmpdir")?.takeIf { it.isNotBlank() }?.let {
            dirs.add(Paths.get(it, "maned"))
        }
        return dirs.map { it.toAbsolutePath().normalize() }
    }

    /**
     * `libmaned_c-<target>-<sha256[0..16]>.<ext>` — the content hash is what makes
     * an upgraded jar never load a stale library, and lets two versions coexist.
     */
    fun hashedName(bytes: ByteArray, target: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val hex = StringBuilder(32)
        for (i in 0 until 8) hex.append("%02x".format(digest[i]))
        return "libmaned_c-$target-$hex.${libraryExtension()}"
    }

    /** Temp name + ATOMIC_MOVE; the final path is never written directly (§4.6). */
    private fun writeAtomically(bytes: ByteArray, destination: Path) {
        val dir = destination.parent
        val temp = Files.createTempFile(dir, destination.fileName.toString(), ".tmp")
        try {
            Files.write(temp, bytes)
            makeReadableAndExecutable(temp)
            try {
                Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                // Some overlay and network filesystems cannot rename atomically.
                // REPLACE_EXISTING is still safe here: the name is content-hashed,
                // so whatever we would clobber is byte-identical to what we write.
                Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.FileAlreadyExistsException) {
                // Another JVM won the race with identical bytes. Nothing to do.
            }
        } finally {
            runCatching { Files.deleteIfExists(temp) }
        }
    }

    private fun makeReadableAndExecutable(p: Path) {
        runCatching { p.toFile().setReadable(true, false) }
        runCatching { p.toFile().setExecutable(true, false) }
    }

    /**
     * `dlopen` the candidate and immediately drop it. `dlopen` refcounts, so the
     * later real lookup in [Ffi] is unaffected; this call exists purely so a
     * `noexec` mount fails *here*, where we can try the next directory, instead of
     * at first use.
     */
    private fun probeLoadable(p: Path) {
        java.lang.foreign.Arena.ofConfined().use { arena ->
            java.lang.foreign.SymbolLookup.libraryLookup(p, arena)
                .find("mnd_abi_version")
                .orElseThrow { ManedLoadException("'$p' loaded but has no mnd_abi_version symbol") }
        }
    }

    private fun escapeHatch(): String =
        "Set -D$LIBRARY_PATH_PROPERTY=/path/to/libmaned_c.${libraryExtension()} " +
            "(or \$$LIBRARY_PATH_ENV) to point at a copy you control."
}
