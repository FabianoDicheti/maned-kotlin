# maned-kotlin

Call `.mnd` scripts from Kotlin/JVM, in-process, via the Foreign Function & Memory API.

## You need this JVM flag

FFM's `libraryLookup` and `reinterpret` are restricted methods. Every consumer must run
with:

```
--enable-native-access=ALL-UNNAMED     # or =dev.maned when consumed as a module
```

JDK 22/23 warn without it, JDK 24 warns loudly (JEP 472), and a future JDK makes it an
error. Put it in `application { applicationDefaultJvmArgs }`.

```kotlin
import dev.maned.Maned

val result = Maned.io["scoring"].run(payload)   // the JSON /run.json returns
```

**Status: scaffold only. Nothing here is functional.** This repo tracks
`MANEDKT_PLAN.md` in the maned workspace; the binding is blocked on the maned_lang side
(the `mnd_*` C ABI, `libmaned_c`, `maned_c_abi.json`, and `posix_spawnp` — MANEDPY
Phases 0–2b). Every entry point throws `NotImplementedError`.

## Baseline and non-goals

- **JDK 22** (FFM final). No JDK 21 preview spelling, no multi-release jar.
- **Android is out of scope**: `java.lang.foreign` does not exist there, and modern
  Android blocks `exec` of non-app binaries regardless.
- **maned calls run on platform threads, never virtual threads** — a native downcall
  pins its carrier for the whole call. `Dispatchers.IO` is the supported dispatcher.
- Calls are uninterruptible: `Thread.interrupt()`, `Future.cancel(true)` and coroutine
  cancellation do not touch a native downcall.

## Licensing

Kotlin sources are **Apache-2.0**. A built jar bundling `libmaned_c` is
**mixed-licence**: the binary is covered by section 3 of the maned `LICENSE`
(`LICENSE-libmaned_c` in `META-INF/`), and the POM's `<licenses>` block must list both.
