plugins {
    kotlin("jvm")
}

description = "Glyph11, the hardened HTTP/1.1 parser, on the JVM - genhttp-kt's port, tuned for Ktron"

kotlin {
    jvmToolchain(25)
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.10.1")
}

tasks.test {
    useJUnitPlatform()
}
