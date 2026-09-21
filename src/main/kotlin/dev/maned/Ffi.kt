package dev.maned

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.StructLayout
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandleProxies
import java.nio.file.Files
import java.nio.file.Path

/**
 * Linker, layouts, MethodHandles, the ABI version check (MANEDKT_PLAN.md §4).
 *
 * ### The layout gate (§3.1, hazard 9)
 *
 * `ctypes.Structure` computes padding from the field types; FFM makes you spell
 * `paddingLayout(4)` yourself, and a wrong offset reads a neighbouring field as
 * garbage rather than throwing. So the binding does not merely *believe* its
 * layout — it checks it against `maned_c_abi.json`, which the engine *states*,
 * at class-init. The manifest is per-target, which is the point: it is the check
 * that catches a 32-bit or different-ABI build dropped in beside the wrong jar.
 *
 * ### The two lifetime rules (§4.4)
 *
 *  - **A returned [MemorySegment] has byte size 0.** Any read throws
 *    [IndexOutOfBoundsException] until [MemorySegment.reinterpret]. This is *the*
 *    FFM beginner trap and the second place `--enable-native-access` bites.
 *  - **An [Arena] cannot own C-allocated memory.** `Arena.ofConfined().use {}`
 *    scopes the *arguments*; the response goes to `mnd_response_free`, always.
 *
 * `reinterpret(size, arena, cleanup)` could tie the free to arena close and is
 * more elegant; it is also one indirection further from obvious at a breakpoint.
 * §4.4 says prefer the explicit `finally` for v1, and that is what is here.
 */
internal object Ffi {

    // -- layout -----------------------------------------------------------------

    /**
     * ```c
     * typedef struct mnd_response {
     *   int32_t     status;        /* offset  0 */
     *                              /* offset  4: 4 bytes padding */
     *   const char *content_type;  /* offset  8 */
     *   const char *body;          /* offset 16 */
     *   size_t      body_len;      /* offset 24 */
     *   const char *notes;         /* offset 32 */
     * } mnd_response;              /* sizeof   40 */
     * ```
     * Hand-written, then gated against `maned_c_abi.json` in [assertLayout].
     */
    val RESPONSE_LAYOUT: StructLayout = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT.withName("status"),
        MemoryLayout.paddingLayout(4),
        ValueLayout.ADDRESS.withName("content_type"),
        ValueLayout.ADDRESS.withName("body"),
        ValueLayout.JAVA_LONG.withName("body_len"), // size_t, LP64
        ValueLayout.ADDRESS.withName("notes"),
    ).withName("mnd_response")

    /**
     * Field offsets, derived from [RESPONSE_LAYOUT] rather than written out
     * again, so the gate in [assertLayout] covers the reads too.
     *
     * `RESPONSE_LAYOUT.varHandle(...)` would be the textbook spelling, but
     * [VarHandle.get] is signature-polymorphic and **Kotlin does not implement
     * polymorphic signatures** — the call compiles to `(Object[])Object` and
     * throws `WrongMethodTypeException` at runtime. `MemorySegment.get(layout,
     * offset)` is an ordinary method and reads the same bytes.
     */
    private fun offsetOf(name: String): Long =
        RESPONSE_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement(name))

    private val OFF_STATUS: Long = offsetOf("status")
    private val OFF_CONTENT_TYPE: Long = offsetOf("content_type")
    private val OFF_BODY: Long = offsetOf("body")
    private val OFF_BODY_LEN: Long = offsetOf("body_len")
    private val OFF_NOTES: Long = offsetOf("notes")

    /** ABI generations this binding accepts. A RANGE, never `==` — the header's
     *  stability policy says bindings must accept N-1 for one minor cycle. */
    val SUPPORTED_ABI: IntRange = 1..1

    // -- linkage ----------------------------------------------------------------

    private val linker: Linker = Linker.nativeLinker()

    /** Global: the library is loaded for the life of the process and `dlclose`
     *  while a `MethodHandle` is still live would be a use-after-free. */
    private val arena: Arena = Arena.global()

    val libraryPath: Path = run {
        warnIfNativeAccessNotEnabled()
        NativeLibrary.path()
    }

    private val lookup: SymbolLookup = try {
        SymbolLookup.libraryLookup(libraryPath, arena)
    } catch (e: IllegalCallerException) {
        throw ManedLoadException(
            "FFM restricted-method access is denied for this module, so libmaned_c " +
                "cannot be loaded. Add --enable-native-access=ALL-UNNAMED (or " +
                "=dev.maned when maned-kotlin is consumed as a module) to the JVM " +
                "command line. Original: ${e.message}"
        )
    }

    /**
     * Binds one exported symbol to [T], a single-abstract-method interface whose
     * signature matches [descriptor].
     *
     * The textbook FFM spelling is `(MemorySegment) handle.invokeExact(args)`.
     * **Kotlin cannot write that**: `MethodHandle.invokeExact` is
     * signature-polymorphic, and the Kotlin compiler has no support for
     * `@PolymorphicSignature`, so the call site is emitted as `(Object[])Object`
     * and every invocation throws `WrongMethodTypeException`.
     * [MethodHandleProxies.asInterfaceInstance] is the way out that keeps the
     * performance argument intact: since JDK 22 it spins a hidden class whose
     * SAM body *is* an `invokeExact` of the bound handle, so the JIT still
     * inlines the downcall. `invokeWithArguments` would work too, and would box
     * every argument on every call — which is the JNA overhead this binding
     * exists to avoid.
     */
    private inline fun <reified T> downcall(
        name: String,
        descriptor: FunctionDescriptor,
        vararg options: Linker.Option,
    ): T {
        val symbol = lookup.find(name).orElseThrow {
            ManedLoadException(
                "'$libraryPath' does not export '$name' — that is not a libmaned_c " +
                    "of ABI ${SUPPORTED_ABI.first}..${SUPPORTED_ABI.last}."
            )
        }
        val handle: MethodHandle = linker.downcallHandle(symbol, descriptor, *options)
        return MethodHandleProxies.asInterfaceInstance(T::class.java, handle)
    }

    // Linker.Option.critical() is a trap wearing a performance label (§5.2): it
    // elides the Java->native thread-state transition, so the calling thread
    // never reaches a safepoint for the duration of the call. On an O(1) call
    // that is free speed. On mnd_request it would stall EVERY GC in the process
    // for as long as a matmul takes -- and mnd_open and mnd_request can both
    // touch the network. So: critical here, and nowhere else. The next person to
    // read this file will want to add it to mnd_request. This comment is why not.
    private val CRITICAL: Array<Linker.Option> = arrayOf(Linker.Option.critical(false))

    private val MND_ABI_VERSION: AbiVersionFn =
        downcall("mnd_abi_version", FunctionDescriptor.of(ValueLayout.JAVA_INT), *CRITICAL)

    private val MND_SCRIPT_IS_ROUTED: IsRoutedFn =
        downcall(
            "mnd_script_is_routed",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
            *CRITICAL,
        )

    private val MND_VERSION: VersionFn =
        downcall("mnd_version", FunctionDescriptor.of(ValueLayout.ADDRESS))

    private val MND_OPEN: OpenFn =
        downcall(
            "mnd_open",
            FunctionDescriptor.of(
                ValueLayout.ADDRESS,   // mnd_script*
                ValueLayout.ADDRESS,   // const char *path
                ValueLayout.JAVA_INT,  // uint32_t flags
                ValueLayout.ADDRESS,   // char **err
                ValueLayout.ADDRESS,   // char **notes
            ),
        )

    private val MND_CLOSE: PointerVoidFn =
        downcall("mnd_close", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS))

    private val MND_REQUEST: RequestFn =
        downcall(
            "mnd_request",
            FunctionDescriptor.of(
                ValueLayout.ADDRESS,    // mnd_response*
                ValueLayout.ADDRESS,    // mnd_script *s
                ValueLayout.ADDRESS,    // const char *method
                ValueLayout.ADDRESS,    // const char *target
                ValueLayout.ADDRESS,    // const char *content_type
                ValueLayout.ADDRESS,    // const char *body
                ValueLayout.JAVA_LONG,  // size_t body_len
            ),
        )

    private val MND_RESPONSE_FREE: PointerVoidFn =
        downcall("mnd_response_free", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS))

    private val MND_FREE_STRING: PointerVoidFn =
        downcall("mnd_free_string", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS))

    private val MND_SET_KERNEL_THREADS: SetIntFn =
        downcall("mnd_set_kernel_threads", FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT))

    // -- the gates, run once, at class-init -------------------------------------

    /** Where the layout manifest was actually read from; quoted by the gate test. */
    var abiManifestPath: Path? = null
        private set

    init {
        val manifest = findAbiManifest()
        abiManifestPath = manifest
        assertLayout(readManifest(manifest), manifest.toString())

        val abi = abiVersion()
        if (abi !in SUPPORTED_ABI) {
            throw ManedLoadException(
                "libmaned_c at '$libraryPath' reports ABI $abi; maned-kotlin accepts " +
                    "${SUPPORTED_ABI.first}..${SUPPORTED_ABI.last}. Bindings accept a " +
                    "RANGE of versions by contract, so this is a real incompatibility, " +
                    "not a version-string mismatch."
            )
        }
    }

    /**
     * Checks the hand-written [RESPONSE_LAYOUT] against what the engine states.
     * This is a gate, not a comment: a wrong offset is a silent garbage read.
     */
    fun assertLayout(manifest: JsonObject, origin: String) {
        val structs = manifest["structs"]?.jsonObject
            ?: fail(origin, "no 'structs' object")
        val response = structs["mnd_response"]?.jsonObject
            ?: fail(origin, "no 'structs.mnd_response' object")

        val declaredSize = response["sizeof"]?.jsonPrimitive?.content?.toLongOrNull()
            ?: fail(origin, "no 'structs.mnd_response.sizeof'")
        if (declaredSize != RESPONSE_LAYOUT.byteSize()) {
            fail(
                origin,
                "sizeof(mnd_response) is $declaredSize there but " +
                    "${RESPONSE_LAYOUT.byteSize()} in Ffi.RESPONSE_LAYOUT"
            )
        }

        val fields = response["fields"]?.jsonObject
            ?: fail(origin, "no 'structs.mnd_response.fields' object")
        for ((name, element) in fields) {
            val declared = element.jsonPrimitive.content.toLongOrNull()
                ?: fail(origin, "field '$name' offset is not an integer")
            val actual = RESPONSE_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement(name))
            if (declared != actual) {
                fail(
                    origin,
                    "mnd_response.$name is at offset $declared there but $actual in " +
                        "Ffi.RESPONSE_LAYOUT — every read past this field would be garbage"
                )
            }
        }

        val declaredAbi = manifest["abi_version"]?.jsonPrimitive?.content?.toIntOrNull()
        if (declaredAbi != null && declaredAbi !in SUPPORTED_ABI) {
            fail(
                origin,
                "declares abi_version $declaredAbi; maned-kotlin accepts " +
                    "${SUPPORTED_ABI.first}..${SUPPORTED_ABI.last}"
            )
        }
    }

    private fun fail(origin: String, what: String): Nothing = throw ManedLoadException(
        "ABI layout gate failed against '$origin': $what. " +
            "maned_c_abi.json is generated per target (MANEDKT_PLAN.md §3.1); a " +
            "mismatch means this libmaned_c was built for a different ABI than this " +
            "binding was written against. Refusing to read the struct."
    )

    /**
     * The manifest ships beside `maned_c.h` in the capi tarball and beside the
     * library in the jar. Look in the obvious places, and name all of them if
     * none has it.
     */
    private fun findAbiManifest(): Path {
        val tried = mutableListOf<String>()

        System.getProperty("maned.abi.manifest")?.takeIf { it.isNotBlank() }?.let {
            val p = Path.of(it).toAbsolutePath().normalize()
            tried += p.toString()
            if (Files.isRegularFile(p)) return p
        }

        val libDir = libraryPath.toAbsolutePath().normalize().parent
        listOfNotNull(
            libDir?.resolve("maned_c_abi.json"),
            libDir?.parent?.resolve("maned_c_abi.json"),
            libDir?.parent?.resolve("include")?.resolve("maned_c_abi.json"),
        ).forEach { p ->
            tried += p.toString()
            if (Files.isRegularFile(p)) return p
        }

        val resource = "native/${NativeLibrary.currentTarget()}/maned_c_abi.json"
        tried += "classpath:$resource"
        Ffi::class.java.classLoader.getResource(resource)?.let {
            return Path.of(it.toURI())
        }

        throw ManedLoadException(
            buildString {
                append("maned_c_abi.json not found; the struct-layout gate cannot run.\n")
                append("Tried:\n")
                tried.forEach { append("  - ").append(it).append('\n') }
                append(
                    "This file is generated by the engine build and ships in the " +
                        "maned-capi tarball beside maned_c.h. Point at it with " +
                        "-Dmaned.abi.manifest=/path/to/maned_c_abi.json.\n"
                )
                append(
                    "The gate is not optional: FFM struct layouts are hand-written, " +
                        "and a wrong offset reads a neighbouring field as garbage " +
                        "rather than throwing (MANEDKT_PLAN.md hazard 9)."
                )
            }
        )
    }

    private fun readManifest(p: Path): JsonObject =
        Json.parseToJsonElement(Files.readString(p)).jsonObject

    /**
     * JDK 22/23 warn on restricted-method use, JDK 24 warns loudly by default
     * (JEP 472), and a later release makes it fatal. Detect the absence and log
     * ONE actionable line rather than letting the JVM's own warning — or, on a
     * modern JDK, an [IllegalCallerException] — be the user's first clue (§5.11).
     */
    private fun warnIfNativeAccessNotEnabled() {
        val args = runCatching {
            java.lang.management.ManagementFactory.getRuntimeMXBean().inputArguments
        }.getOrNull() ?: return
        if (args.any { it.startsWith("--enable-native-access") }) return
        Log.warnOnce(
            "maned-kotlin is about to use FFM restricted methods (libraryLookup, " +
                "reinterpret) and --enable-native-access is not on this JVM's command " +
                "line. Add --enable-native-access=ALL-UNNAMED (or =dev.maned when " +
                "consumed as a module). On JDK 24+ this is a loud warning; on a later " +
                "JDK it becomes fatal."
        )
    }

    /**
     * The process C locale's decimal separator, read through `localeconv()`.
     * `null` when it could not be determined.
     *
     * Not `java.util.Locale`: that is the *JVM's* notion of a locale and can be
     * set independently (`-Duser.language`). This reads the one that `strtod` and
     * friends actually consult.
     *
     * Kept for `LocaleIndependenceTest`, which flips the process into `pt_BR` /
     * `de_DE` / `fr_FR` and needs to prove the flip took effect before asserting
     * that the engine's answers did not move. There is no longer a warning built
     * on it: the engine pins its own thread locale with `uselocale` for the
     * duration of a request (maned_lang fe3ebc8), so a comma-decimal host is
     * simply not this binding's problem any more, and a line advising `LC_ALL=C`
     * would now be stale advice about a fixed bug.
     */
    fun cDecimalSeparator(): String? = runCatching {
        val handle = linker.downcallHandle(
            linker.defaultLookup().find("localeconv").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.ADDRESS),
        )
        val localeconv: LocaleconvFn =
            MethodHandleProxies.asInterfaceInstance(LocaleconvFn::class.java, handle)
        // struct lconv's first member is `char *decimal_point` (C99 7.11.2.1).
        localeconv.call()
            .reinterpret(ValueLayout.ADDRESS.byteSize())
            .get(ValueLayout.ADDRESS, 0L)
            .reinterpret(Long.MAX_VALUE)
            .getString(0)
    }.getOrNull()

    // -- entry points -----------------------------------------------------------

    fun abiVersion(): Int = MND_ABI_VERSION.call()

    fun version(): String = MND_VERSION.call().readCString()

    fun setKernelThreads(n: Int) = MND_SET_KERNEL_THREADS.call(n)

    fun isRouted(handle: MemorySegment): Boolean = MND_SCRIPT_IS_ROUTED.call(handle) != 0

    /** Result of [open]: a live handle plus the load notes the engine emitted. */
    class Opened(val handle: MemorySegment, val notes: List<String>)

    /**
     * Parse -> lint -> inline -> plan -> validate, ONCE. Throws
     * [ManedLoadException] with the engine's own message on failure.
     */
    fun open(path: String, flags: Int): Opened {
        Arena.ofConfined().use { arena ->
            val pathSeg = arena.allocateFrom(path)
            val errOut = arena.allocate(ValueLayout.ADDRESS)
            val notesOut = arena.allocate(ValueLayout.ADDRESS)

            val handle = MND_OPEN.call(pathSeg, flags, errOut, notesOut)

            // Both out-params are heap char* on EITHER outcome and go to
            // mnd_free_string — never to free() (the header's ownership rule 1).
            val notes = takeOwnedString(errOrNotes = notesOut)
            val err = takeOwnedString(errOrNotes = errOut)

            if (handle.address() == 0L) {
                throw ManedLoadException(
                    (err ?: "mnd_open failed without a message") +
                        // §5.4: data paths inside a .mnd resolve against the PROCESS
                        // working directory, which a JVM caller can neither set nor
                        // reliably observe (user.dir is a startup snapshot and
                        // setting it does not chdir).
                        " [loading '$path'; note that relative data paths inside a " +
                        ".mnd file resolve against the process working directory, " +
                        "which on the JVM is '${System.getProperty("user.dir")}' and " +
                        "cannot be changed — prefer absolute paths]",
                    script = path,
                )
            }
            return Opened(handle, parseNotes(notes))
        }
    }

    fun close(handle: MemorySegment) = MND_CLOSE.call(handle)

    /** status / content_type / body / notes, copied onto the JVM heap. */
    class RawResponse(
        val status: Int,
        val contentType: String,
        val body: ByteArray,
        val notes: String,
    )

    /**
     * One request -> one response. Arguments live in a confined [Arena]; the
     * response is copied into JVM arrays inside the `try` and freed in the
     * `finally`, because an Arena cannot own C-allocated memory (§4.4).
     */
    fun request(
        handle: MemorySegment,
        method: String,
        target: String,
        contentType: String?,
        body: ByteArray,
    ): RawResponse {
        Arena.ofConfined().use { arena ->
            val methodSeg = arena.allocateFrom(method)
            val targetSeg = arena.allocateFrom(target)
            val ctSeg =
                if (contentType == null) MemorySegment.NULL else arena.allocateFrom(contentType)
            val bodySeg =
                if (body.isEmpty()) MemorySegment.NULL
                else arena.allocateFrom(ValueLayout.JAVA_BYTE, *body)

            val raw = MND_REQUEST.call(
                handle, methodSeg, targetSeg, ctSeg, bodySeg, body.size.toLong(),
            )

            // The header promises mnd_request NEVER returns NULL -- even OOM
            // returns a static status-0 response. Checked anyway: a NULL here
            // would otherwise present as an IndexOutOfBoundsException from
            // reinterpret, which says nothing about what went wrong.
            if (raw.address() == 0L) {
                throw ManedInternalException(
                    "mnd_request returned NULL, which the ABI says it never does " +
                        "(maned_c.h: \"NEVER returns NULL\"). The library is not a " +
                        "libmaned_c of ABI ${SUPPORTED_ABI.first}..${SUPPORTED_ABI.last}.",
                    body = null,
                )
            }

            try {
                // A segment returned from a downcall has byte size 0: every read
                // below throws IndexOutOfBoundsException without this line. (§4.4,
                // hazard 8 -- the single most common FFM mistake.)
                val r = raw.reinterpret(RESPONSE_LAYOUT.byteSize())

                val status = r.get(ValueLayout.JAVA_INT, OFF_STATUS)
                val contentTypePtr = r.get(ValueLayout.ADDRESS, OFF_CONTENT_TYPE)
                val bodyPtr = r.get(ValueLayout.ADDRESS, OFF_BODY)
                val bodyLen = r.get(ValueLayout.JAVA_LONG, OFF_BODY_LEN)
                val notesPtr = r.get(ValueLayout.ADDRESS, OFF_NOTES)

                // body MAY CONTAIN NULs (images) and body_len is the truth --
                // never strlen() it.
                val bodyBytes =
                    if (bodyLen <= 0L || bodyPtr.address() == 0L) ByteArray(0)
                    else bodyPtr.reinterpret(bodyLen).toArray(ValueLayout.JAVA_BYTE)

                return RawResponse(
                    status = status,
                    contentType = contentTypePtr.readCString(),
                    body = bodyBytes,
                    notes = notesPtr.readCString(),
                )
            } finally {
                // Ownership rule 2: the struct AND every string it points at.
                // Kotlin never calls free().
                MND_RESPONSE_FREE.call(raw)
            }
        }
    }

    // -- helpers ----------------------------------------------------------------

    /**
     * Reads a `const char *` field. Nothing in a returned `mnd_response` is NULL
     * (the header's nullability rule) — `content_type` and `notes` are `""` when
     * absent — but a zero address is still handled rather than reinterpreted.
     */
    private fun MemorySegment.readCString(): String =
        if (address() == 0L) "" else reinterpret(Long.MAX_VALUE).getString(0)

    /** Reads a `char **` out-param, copies it, and hands the original back to
     *  `mnd_free_string`. Null (the param was not produced) yields null. */
    private fun takeOwnedString(errOrNotes: MemorySegment): String? {
        val ptr = errOrNotes.get(ValueLayout.ADDRESS, 0L)
        if (ptr.address() == 0L) return null
        val s = ptr.reinterpret(Long.MAX_VALUE).getString(0)
        MND_FREE_STRING.call(ptr)
        return s
    }

    /** `notes` is a JSON array of strings, `"[]"` when there are none. */
    fun parseNotes(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            Json.parseToJsonElement(json).let { element ->
                (element as kotlinx.serialization.json.JsonArray).map { it.jsonPrimitive.content }
            }
        }.getOrElse {
            // A note is diagnostic output; an unparseable one must never take down
            // a successful call.
            listOf(json)
        }
    }
}

/** One-shot logging, so an advisory never becomes a per-call flood. */
internal object Log {
    private val logger: System.Logger = System.getLogger("dev.maned")
    private val said = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun warnOnce(message: String) {
        if (said.add(message)) logger.log(System.Logger.Level.WARNING, message)
    }
}

// -- the SAM shapes the downcall handles are bound to ---------------------------
//
// One interface per distinct C signature in maned_c.h. They exist only because
// Kotlin cannot call a signature-polymorphic method (see Ffi.downcall); they are
// `internal`, which is public in bytecode, because MethodHandleProxies requires a
// public interface.

internal fun interface AbiVersionFn {
    /** `int32_t mnd_abi_version(void)` */
    fun call(): Int
}

internal fun interface VersionFn {
    /** `const char *mnd_version(void)` */
    fun call(): MemorySegment
}

internal fun interface IsRoutedFn {
    /** `int32_t mnd_script_is_routed(const mnd_script *)` */
    fun call(script: MemorySegment): Int
}

internal fun interface OpenFn {
    /** `mnd_script *mnd_open(const char *, uint32_t, char **, char **)` */
    fun call(
        path: MemorySegment,
        flags: Int,
        err: MemorySegment,
        notes: MemorySegment,
    ): MemorySegment
}

internal fun interface RequestFn {
    /**
     * `mnd_response *mnd_request(mnd_script *, const char *, const char *,
     * const char *, const char *, size_t)`
     */
    fun call(
        script: MemorySegment,
        method: MemorySegment,
        target: MemorySegment,
        contentType: MemorySegment,
        body: MemorySegment,
        bodyLen: Long,
    ): MemorySegment
}

internal fun interface PointerVoidFn {
    /** `void mnd_close(mnd_script *)` / `mnd_response_free` / `mnd_free_string` */
    fun call(pointer: MemorySegment)
}

internal fun interface SetIntFn {
    /** `void mnd_set_kernel_threads(int32_t)` */
    fun call(value: Int)
}

internal fun interface LocaleconvFn {
    /** libc `struct lconv *localeconv(void)` — read-only, for the locale gate. */
    fun call(): MemorySegment
}
