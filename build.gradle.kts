import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// MANEDKT_PLAN.md §4. Baseline is JDK 22 (FFM final — §2 cost 1): that is the
// published bytecode floor, NOT the toolchain. The toolchain is whatever JDK is
// installed (here: Temurin 26); jvmTarget/--release pin the floor at 22 so the
// artifact still runs on a 22 JVM.
plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
    `java-library`
    // Drives sources/javadoc jars, POM, signing and the Central Portal
    // upload in one plugin (MANEDKT_PLAN.md §8). Credentials live in
    // ~/.gradle/gradle.properties: mavenCentralUsername/-Password +
    // signingInMemoryKey/-Password — never in this file.
    id("com.vanniktech.maven.publish") version "0.34.0"
}

group = "dev.maned"
version = "0.4.1"

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

// --- Publishing (MANEDKT_PLAN.md §8) ------------------------------------------
// Main jar is pure JVM bytecode; the native library ships in per-platform
// CLASSIFIER jars (maned-<v>-macos-universal.jar etc.), each carrying
// native/<target>/libmaned_c.* — the exact layout NativeLibrary's extraction
// path consumes — plus the mixed-licence files. Staged from the released
// maned-capi tarballs named by -Pmaned.capi.dist (default: ../capi-dist-<v>).
val capiDistDir: Provider<String> =
    providers.gradleProperty("maned.capi.dist")
        .orElse(layout.projectDirectory.dir("../capi-dist-0.4.0").asFile.absolutePath)

data class NativeTarget(
    val classifier: String, val capiTarget: String,
    val libName: String, val realPattern: String,
)
val nativeTargets = listOf(
    // realPattern must match the REAL file, never a SONAME symlink: tarTree
    // materializes a symlink entry as an empty file. The Linux tarballs ship
    // libmaned_c.so -> .so.1 -> .so.1.X.Y; only the double-dotted name is
    // the actual library, and it is renamed back to the canonical name the
    // extraction path loads.
    NativeTarget("macos-universal", "universal-apple-darwin",    "libmaned_c.dylib", "**/lib/libmaned_c.dylib"),
    NativeTarget("linux-x86_64",    "x86_64-unknown-linux-gnu",  "libmaned_c.so",    "**/lib/libmaned_c.so.*.*"),
    NativeTarget("linux-aarch64",   "aarch64-unknown-linux-gnu", "libmaned_c.so",    "**/lib/libmaned_c.so.*.*"),
)

val nativeJarTasks = nativeTargets.map { t ->
    val unpack = tasks.register<Copy>("unpackCapi-${t.classifier}") {
        val tarball = file("${capiDistDir.get()}/maned-capi-0.4.0-${t.capiTarget}.tar.gz")
        onlyIf { tarball.isFile }
        from(tarTree(resources.gzip(tarball))) {
            include(t.realPattern)
            eachFile { path = t.libName }
            includeEmptyDirs = false
        }
        from(tarTree(resources.gzip(tarball))) {
            include("**/maned_c_abi.json")
            eachFile { path = name }
            includeEmptyDirs = false
        }
        into(layout.buildDirectory.dir("capi/${t.classifier}"))
    }
    tasks.register<Jar>("nativeJar-${t.classifier}") {
        dependsOn(unpack)
        archiveClassifier.set(t.classifier)
        onlyIf { layout.buildDirectory.dir("capi/${t.classifier}").get().asFile.resolve(t.libName).isFile }
        from(layout.buildDirectory.dir("capi/${t.classifier}")) { into("native/${t.classifier}") }
        from("LICENSE-libmaned_c", "NOTICE")
    }
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    coordinates("dev.maned", "maned", version.toString())
    configure(com.vanniktech.maven.publish.KotlinJvm(
        javadocJar = com.vanniktech.maven.publish.JavadocJar.Empty(),
        sourcesJar = true,
    ))
    pom {
        name.set("maned")
        description.set("Kotlin/JVM binding for the Maned integer dataflow language, via the FFM API")
        url.set("https://maned-lang.com")
        licenses {
            license {
                name.set("Apache-2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0")
            }
            license {
                // The native classifier jars additionally carry libmaned_c
                // under the Maned EULA §3 (see LICENSE-libmaned_c inside them).
                name.set("LicenseRef-Maned-EULA (bundled libmaned_c in classifier jars)")
                url.set("https://github.com/FabianoDicheti/maned-kotlin/blob/master/LICENSE-libmaned_c")
            }
        }
        scm {
            url.set("https://github.com/FabianoDicheti/maned-kotlin")
            connection.set("scm:git:https://github.com/FabianoDicheti/maned-kotlin.git")
        }
        developers {
            developer {
                id.set("FabianoDicheti")
                name.set("Fabiano Dicheti")
            }
        }
    }
}

publishing.publications.withType<MavenPublication>().configureEach {
    nativeJarTasks.forEach { artifact(it) }
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
