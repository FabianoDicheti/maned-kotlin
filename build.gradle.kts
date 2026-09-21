import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// MANEDKT_PLAN.md §4. Baseline is JDK 22 (FFM final — §2 cost 1): that is the
// published bytecode floor, NOT the toolchain. The toolchain is whatever JDK is
// installed (here: Temurin 26); jvmTarget/--release pin the floor at 22 so the
// artifact still runs on a 22 JVM.
plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
    `java-library`
}

group = "dev.maned"
version = "0.1.0-SNAPSHOT"

// --- Toolchain ---------------------------------------------------------------
// jvmToolchain(N) would also pin the *target* to N. We want the local JDK for
// compilation and 22 for the target, so set them separately and never trigger a
// toolchain download (no foojay resolver is configured on purpose).
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(JavaVersion.current().majorVersion))
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_22)
        freeCompilerArgs.add("-Xjdk-release=22")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(22)
}

repositories {
    mavenCentral()
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

// --- Where the native library and the shared conformance corpus live ----------
// The capi artifact is not vendored into this repo (it is a separate, frozen
// deliverable). `gradle test` in this checkout finds it next door; CI overrides
// with -Pmaned.library.path=... or $MANED_LIBRARY_PATH.
val capiFreezeDir: Directory = layout.projectDirectory.dir("../capi-freeze")

val manedLibraryPath: Provider<String> =
    providers.gradleProperty("maned.library.path")
        .orElse(providers.environmentVariable("MANED_LIBRARY_PATH"))
        .orElse(capiFreezeDir.file("lib/libmaned_c.dylib").asFile.absolutePath)

val manedConformanceDir: Provider<String> =
    providers.gradleProperty("maned.conformance.dir")
        .orElse(providers.environmentVariable("MANED_CONFORMANCE_DIR"))
        .orElse(capiFreezeDir.dir("conformance").asFile.absolutePath)

// --- Stage the dylib into test resources for the extraction test (§4.6) -------
// NOT committed (see .gitignore): the extraction test needs a resources/native/
// <target>/ entry to extract *from*, and the only copy of the binary on this
// machine is the frozen capi artifact.
val stageNativeForTests = tasks.register<Copy>("stageNativeForTests") {
    description = "Copies libmaned_c into src/test/resources/native/<target>/ so the extraction test has something to extract."
    val lib = file(manedLibraryPath.get())
    onlyIf { lib.isFile }
    from(lib)
    into(layout.projectDirectory.dir("src/test/resources/native/${manedTargetTriple()}"))
}

tasks.named<ProcessResources>("processTestResources") {
    dependsOn(stageNativeForTests)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Restricted-method access for FFM — the same flag every consumer needs
    // (MANEDKT_PLAN.md §5.11). JDK 26 is past JEP 472's warn stage.
    jvmArgs("--enable-native-access=ALL-UNNAMED")

    systemProperty("maned.library.path", manedLibraryPath.get())
    systemProperty("maned.conformance.dir", manedConformanceDir.get())
    systemProperty("maned.target", manedTargetTriple())
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = true
    }
}

/** Mirrors NativeLibrary.currentTarget() — kept in sync by NativeLibraryTest. */
fun manedTargetTriple(): String {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    return when {
        os.contains("mac") || os.contains("darwin") -> "macos-universal"
        os.contains("linux") && (arch == "aarch64" || arch == "arm64") -> "linux-aarch64"
        os.contains("linux") -> "linux-x86_64"
        else -> "unsupported-$os-$arch"
    }
}
