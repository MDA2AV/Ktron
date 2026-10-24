plugins {
    kotlin("jvm")
    id("com.google.devtools.ksp")
}

description = "Ktron: an HTTP/1.1 web framework on the Kotlin port of ioxide"

kotlin {
    jvmToolchain(25)
}

dependencies {
    api(project(":ioxide"))
    api(project(":glyph11"))
    api("com.ghostserializer:ghost-serialization:1.3.5")
    testImplementation(kotlin("test"))
    kspTest("com.ghostserializer:ghost-compiler:1.3.5")
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
