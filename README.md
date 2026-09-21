# maned-kotlin

Call `.mnd` scripts from Kotlin/JVM, in-process, via the Foreign Function & Memory API.

## You need this JVM flag

FFM's `libraryLookup` and `reinterpret` are restricted methods. Every consumer must run
with:

```
--enable-native-access=ALL-UNNAMED     # or =dev.maned when consumed as a module
```

JDK 22/23 warn without it, JDK 24 warns loudly (JEP 472), and a future JDK makes it an
error. Put it in `application { applicationDefaultJvmArgs }`. maned-kotlin detects its
absence and logs one actionable line rather than letting the JVM's own warning be your
first clue.

```kotlin
import dev.maned.Maned

val result = Maned.io["scoring"].run(payload)   // the JSON /run.json returns
println(result.tensor("a").data.toList())       // exact int64
println(result.decimal("y"))                    // exact BigDecimal at quantres > 0
```

## Also set LC_ALL=C — today, this is not optional

The engine parses request-body numbers through the **process C locale**. In a
comma-decimal locale (`pt_BR`, `de_DE`, `fr_FR`, …) `[[3,1],[2,4]]` is parsed as
`[[3],[2]]`: a **200 response with silently wrong values**, no error, no note.

This lands on the JVM specifically, because the JVM calls `setlocale(LC_ALL, "")` during
startup and adopts `$LANG`/`$LC_NUMERIC` from the environment — a C++ host that never
calls `setlocale` stays in the `"C"` locale and never sees it.

```
LC_ALL=C java --enable-native-access=ALL-UNNAMED -jar your-app.jar
```

maned-kotlin **detects** this (via `localeconv()`) and logs one line. It deliberately
does **not** call `setlocale()` on your process to hide it: that would be a
process-global, thread-unsafe write into a host runtime, papering over a data-corruption
bug. See `LocaleHazardTest` for the executable diagnosis.

## What is here

| File | Role |
|---|---|
| `Maned.kt` | the facade: `io[...]`, `load`, `setKernelThreads`, `searchPath`, the cache |
| `Script.kt` | `AutoCloseable`, the per-script lock, `Cleaner` registration, the drain |
| `Ffi.kt` | `Linker`, layouts, the ABI version check, the struct-layout gate |
| `NativeLibrary.kt` | extraction from the jar, discovery, the refusal messages |
| `Result.kt` | the `/run.json` document, `Tensor`, `BigDecimal` accessors |
| `Errors.kt` | the `ManedException` hierarchy and the status map |
| `Coroutines.kt` | `runAsync` on `Dispatchers.IO` |

## Threading

- **Per-`Script` lock by default.** Routed scripts drive single-connection device
  channels and are not thread-safe. `Maned.load(path, concurrent = true)` drops the lock
  and is **refused at load** unless `mnd_script_is_routed() == 0`.
- **maned calls run on platform threads, never virtual threads.** A native downcall pins
  its carrier for the whole call; the carrier pool is `availableProcessors()`, so a
  handful of concurrent calls starve every unrelated task in the JVM, silently.
  `Dispatchers.IO` is the supported dispatcher. Set `-Dmaned.warnOnVirtualThread=false`
  to silence the warning if you know what you are doing.
- **Size your dispatcher.** `Dispatchers.IO` has a 64-thread ceiling and each in-flight
  call may spawn `setKernelThreads` interpreter workers on top. Use
  `Dispatchers.IO.limitedParallelism(n)`.
- **Calls are uninterruptible**: `Thread.interrupt()`, `Future.cancel(true)` and
  coroutine cancellation do not touch a native downcall. There is deliberately no
  timeout overload — it would *appear* to work while the thread stayed blocked in C.

## Data paths

`in::w = csv("weights.csv")` resolves against the **process** working directory.
`System.getProperty("user.dir")` is a startup snapshot, **setting it does not chdir**,
and app servers and Gradle daemons start with a CWD nobody intended. Use absolute paths
inside `.mnd` files. maned-kotlin does not offer a `chdir` option because the JVM cannot
honestly implement one.

## Native library resolution

1. `-Dmaned.library.path=<file>` (or `$MANED_LIBRARY_PATH`);
2. an already-extracted cache hit — the filename carries a content hash, so an upgraded
   jar never loads a stale library and two versions coexist;
3. extraction from `resources/native/<target>/` into `$XDG_CACHE_HOME/maned` →
   `~/.cache/maned` → `java.io.tmpdir/maned`, written to a temp name and `ATOMIC_MOVE`d
   into place.

`noexec` mounts (common for `/tmp` in hardened containers) make `dlopen` fail even when
the write succeeded, so each candidate directory is probed with a real `dlopen`; if all
fail, the refusal names every path tried and the escape hatch.

## Building and testing

The capi artifact (`libmaned_c`, `maned_c_abi.json`, the conformance corpus) is **not
vendored here**. `gradle test` finds it in the sibling `../capi-freeze` checkout;
override with a Gradle property or the environment:

```
gradle test
gradle test -Pmaned.library.path=/path/to/libmaned_c.dylib \
            -Pmaned.conformance.dir=/path/to/conformance
MANED_LIBRARY_PATH=... MANED_CONFORMANCE_DIR=... gradle test
```

Toolchain: the installed JDK compiles, `jvmTarget`/`--release` pin the published floor at
**22** (FFM final). Kotlin 2.3.21, Gradle 9.7.

## Baseline and non-goals

- **JDK 22** (FFM final). No JDK 21 preview spelling, no multi-release jar.
- **Android is out of scope**: `java.lang.foreign` does not exist there, and modern
  Android blocks `exec` of non-app binaries regardless.

## Licensing

Kotlin sources are **Apache-2.0**. A built jar bundling `libmaned_c` is
**mixed-licence**: the binary is covered by section 3 of the maned `LICENSE`
(`LICENSE-libmaned_c` in `META-INF/`), and the POM's `<licenses>` block must list both.
