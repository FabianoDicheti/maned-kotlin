// The quickstart example, consuming the PUBLISHED artifact from Maven
// Central. Run from this directory with JDK 22+:
//
//   gradle run
//
// Expected output:
//   y = [[2,4],[0,2]] | engine 0.4.0
plugins {
    kotlin("jvm") version "2.3.21"
    application
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
    runtimeOnly("dev.maned:maned:0.4.2:$classifier")
}
application {
    mainClass.set("ScoringKt")
    // Every FFM consumer needs this flag (JEP 472).
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}
