// Scaffold per MANEDKT_PLAN.md §4. Versions are placeholders; pin them when
// implementation starts. Baseline is JDK 22 (FFM final — MANEDKT_PLAN.md §2 cost 1).
plugins {
    kotlin("jvm") version "2.0.20"
    kotlin("plugin.serialization") version "2.0.20"
    `java-library`
}

group = "dev.maned"
version = "0.0.0-SNAPSHOT"

kotlin {
    jvmToolchain(22)
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}

tasks.withType<Test> {
    // Restricted-method access for FFM — the same flag consumers need (§5.11).
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
