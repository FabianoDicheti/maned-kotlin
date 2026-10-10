package dev.maned

import java.nio.file.Files
import java.nio.file.Path

/**
 * Where the frozen capi artifact lives for this run.
 *
 * The build wires `-Dmaned.library.path` and `-Dmaned.conformance.dir` from a
 * Gradle property, `$MANED_LIBRARY_PATH`/`$MANED_CONFORMANCE_DIR`, or the
 * sibling `../capi-freeze` checkout, in that order — so a plain `gradle test`
 * works here and CI can point elsewhere without editing anything.
 */
object TestEnv {

    fun libraryPath(): Path {
        val p = System.getProperty(NativeLibrary.LIBRARY_PATH_PROPERTY)
            ?: System.getenv(NativeLibrary.LIBRARY_PATH_ENV)
            ?: error("-Dmaned.library.path is not set; the Gradle test task sets it")
        return Path.of(p).toAbsolutePath().normalize()
    }

    /** The capi artifact root — `lib/`, `include/`, `conformance/` live here. */
    fun capiRoot(): Path = libraryPath().parent.parent

    fun abiManifest(): Path = capiRoot().resolve("maned_c_abi.json")

    fun conformanceDir(): Path {
        val p = System.getProperty("maned.conformance.dir")
            ?: System.getenv("MANED_CONFORMANCE_DIR")
            ?: capiRoot().resolve("conformance").toString()
        val dir = Path.of(p).toAbsolutePath().normalize()
        check(Files.isDirectory(dir)) { "conformance corpus not found at $dir" }
        return dir
    }

    fun target(): String = System.getProperty("maned.target") ?: NativeLibrary.currentTarget()

    /** Writes [text] as `<name>.mnd` under [dir] and returns its path. */
    fun writeScript(dir: Path, name: String, text: String): Path {
        val p = dir.resolve("$name.mnd")
        Files.writeString(p, text)
        return p
    }
}
