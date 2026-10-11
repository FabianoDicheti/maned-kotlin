![the maned mark, in Maven's colors](https://raw.githubusercontent.com/FabianoDicheti/maned-kotlin/master/docs/img/logo.png)

# maned-kotlin

Call `.mnd` scripts from Kotlin/JVM, in-process, via the Foreign Function & Memory API.

## Quickstart

Published on Maven Central. Step by step, with JDK 22+ installed:

1. In your Gradle project, add the library and the classifier jar for
   your platform (`macos-universal`, `linux-x86_64` or `linux-aarch64` —
   it carries the native engine library, nothing else to install):

```kotlin
dependencies {
    implementation("dev.maned:maned:0.4.2")
    runtimeOnly("dev.maned:maned:0.4.2:macos-universal")
}
application {
    mainClass.set("ScoringKt")
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}
```

2. Save the script as `scoring.mnd` and this as `src/main/kotlin/Scoring.kt`:

```kotlin
import dev.maned.Maned

fun main() {
    Maned.load("scoring.mnd").use { s ->
        val result = s.run("""{"x": [[2,0],[0,2]]}""")
        println("y = " + result.outputs["y"])
    }
}
```

3. Run it:

```
$ gradle run
y = [[2,4],[0,2]]
```

A complete, runnable copy of exactly this lives in
[`examples/scoring/`](examples/scoring/) — `cd examples/scoring && gradle run`,
resolving the published artifact from Maven Central. Deeper, equally
runnable: [`examples/deepdive/`](examples/deepdive/) — `gradle runFiles`
(file routes), `gradle runDecimals` (exact BigDecimals + coroutines),
`gradle runRouted` (the local Bark VM).

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

## Your host locale does not matter

The engine is locale-hardened as of this library version: `handle()` and
`loadServerScript()` pin their thread to the C locale with POSIX `uselocale` for the
duration of a call, so requests and responses are byte-identical whatever `$LANG` or
`java.util.Locale` say. You do not need `LC_ALL=C`, and the binding does not touch your
process locale.

This is worth stating because it briefly was not true: a JVM calls
`setlocale(LC_ALL, "")` at startup and adopts `$LANG`/`$LC_NUMERIC`, and against an
older engine a comma-decimal host (`pt_BR`, `de_DE`, `fr_FR`, …) parsed `[[3,1],[2,4]]`
as `[[3],[2]]` — a 200 with silently wrong values. `LocaleIndependenceTest` is the
JVM-side regression gate for the fix: it flips the whole process into each installed
comma-decimal locale and asserts the exact response bytes, including that a
`quantres > 0` script still prints `3.00` and never `3,00`.

## What is here

| File | Role |
|---|---|
| `Maned.kt` | the facade: `io[...]`, `load`, `setKernelThreads`, `searchPath`, the cache |
| `Script.kt` | `AutoCloseable`, the per-script lock, `Cleaner` registration, the drain |
| `Ffi.kt` | `Linker`, layouts, the ABI version check, the struct-layout gate |
| `NativeLibrary.kt` | extraction from the jar, discovery, the refusal messages |
| `Result.kt` | the `/run.json` document, `Tensor`, `BigDecimal` accessors |
| `Multipart.kt` | `multipart/form-data` for the file routes, bytes in and out |
| `IoSpec.kt` | one `in::`/`out::` entry of the contract, from `GET /health` |
| `Errors.kt` | the `ManedException` hierarchy and the status map |
| `Coroutines.kt` | `runAsync`, `runFilesAsync`, `frameAsync` on `Dispatchers.IO` |

### The file routes

`run()` goes to `/run.json`, which renders an `out::` image as a nested integer
array and a csv as a JSON tensor. For the engine's actual file bytes there is
the multipart route, and it takes file bytes on the way in too — a `csv()` /
`image()` / `parquet()` `in::` never has to be decoded into JSON first.

| Call | Route | Returns |
|---|---|---|
| `script.runFiles("h0" to bytes)` | `POST /run` multipart | `Map<String, ByteArray>`, one entry per `out::` |
| `script.frame(payload)` | `POST /frame` | `Result` — the frame document *is* the body |
| `script.frameCsv(payload)` | `POST /frame.csv` | `ByteArray`, undecoded |
| `script.inputs` / `.outputs` / `.routes` / `.health` | `GET /health` | the contract, fetched once and cached |

`Multipart.encode` / `.decode` / `.boundaryFor` are public for anyone driving
`request()` by hand. The encoding is byte-identical to manedpy's and maned-rs's
— `IoTest` asserts it against a frozen body, so a drift is red rather than
merely different.

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

![a maned wolf with a cup of coffee](https://raw.githubusercontent.com/FabianoDicheti/maned-kotlin/master/docs/img/wolf.png)

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
