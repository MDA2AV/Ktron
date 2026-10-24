plugins {
    kotlin("jvm")
    id("com.google.devtools.ksp")
    application
}

description = "HttpArena's HTTP/1.1 profiles on Ktron"

kotlin {
    jvmToolchain(25)
}

dependencies {
    implementation(project(":ktron"))
    ksp("com.ghostserializer:ghost-compiler:1.3.5")
}

// The BoringSSL ioxide bundles, from the ioxide checkout beside this one; a host elsewhere sets -Dioxide.boringssl.
val boringSsl = rootProject.file("../ioxide/src/ioxide/runtimes/linux-x64/native/libioxide_boringssl.so").absolutePath

application {
    mainClass = "arena.MainKt"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED", "-Dioxide.boringssl=$boringSsl")
}
