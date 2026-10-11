// Three deep-dive examples against the PUBLISHED dev.maned:maned, each its
// own task (JDK 22+, run from this directory):
//
//   gradle runFiles      - file routes: csv bytes in, output file bytes back
//   gradle runDecimals   - exact BigDecimals + coroutine concurrency
//   gradle runRouted     - @device(host="local") spawns the local Bark VM
//                          (needs the maned toolchain installed: bark-vm on PATH)
plugins {
    kotlin("jvm") version "2.3.21"
}
repositories { mavenCentral() }

val os = System.getProperty("os.name").lowercase()
val arch = System.getProperty("os.arch").lowercase()
val classifier = when {
    os.contains("mac") -> "macos-universal"
    arch == "aarch64" || arch == "arm64" -> "linux-aarch64"
    else -> "linux-x86_64"
}

dependencies {
    implementation("dev.maned:maned:0.4.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    runtimeOnly("dev.maned:maned:0.4.2:$classifier")
}

listOf("Files" to "FilesKt", "Decimals" to "DecimalsKt", "Routed" to "RoutedKt").forEach { (name, main) ->
    tasks.register<JavaExec>("run$name") {
        group = "examples"
        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set(main)
        workingDir = projectDir      // the scripts' csv() sources are relative
        jvmArgs("--enable-native-access=ALL-UNNAMED")
    }
}
