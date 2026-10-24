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
    // -Pglyph11.fuzz.iterations=N -Pglyph11.fuzz.seed=S: a longer run of the fuzz tests, or another one
    for (name in listOf("glyph11.fuzz.iterations", "glyph11.fuzz.seed")) {
        providers.gradleProperty(name).orNull?.let { systemProperty(name, it) }
    }
}

// ---- Benchmarks: src/bench, a main per tool, run outside Gradle's JVM so it can be pinned to a CPU ----

// The bench sees main's internals and the test vectors (src/test), so the adapter it compares against and the C
// binding can fill a RequestHead, and its differential run reuses the tests' corpus.
val bench: SourceSet = sourceSets.create("bench") {
    compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
    runtimeClasspath += output + compileClasspath
}
configurations[bench.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[bench.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())

kotlin.target.compilations.getByName("bench").associateWith(kotlin.target.compilations.getByName("main"))

// The Glyph11 C core (github.com/dotnet-web-stack/Glyph11, core/), built into build/native for the FFM comparison:
// a Glyph11 checkout next to this repo, or -Pglyph11Core=<checkout>/core.
val glyph11Core = providers.gradleProperty("glyph11Core").orElse(rootDir.resolve("../Glyph11/core").path)
val nativeLib = layout.buildDirectory.file("native/libglyph11.so")

val nativeCore = tasks.register<Exec>("nativeCore") {
    description = "Builds the Glyph11 C core into build/native/libglyph11.so."
    val core = file(glyph11Core.get())
    inputs.file(core.resolve("src/glyph11.c")).optional()
    inputs.dir(core.resolve("include")).optional()
    outputs.file(nativeLib)
    // without a checkout the bench and the differential run skip the C core
    onlyIf { core.resolve("src/glyph11.c").exists() }
    doFirst { nativeLib.get().asFile.parentFile.mkdirs() }
    commandLine(
        "gcc", "-O3", "-march=x86-64-v3", "-shared", "-fPIC", "-Wall", "-Wextra",
        "-I", core.resolve("include").path, "-o", nativeLib.get().asFile.path, core.resolve("src/glyph11.c").path,
    )
}

// Writes the bench classpath to build/bench-classpath.txt, for running a tool by hand:
//   taskset -c 14 java --enable-native-access=ALL-UNNAMED -cp "$(cat glyph11/build/bench-classpath.txt)" \
//       ktron.glyph11.bench.BenchKt
tasks.register("benchClasspath") {
    description = "Builds the benchmarks and writes their classpath to build/bench-classpath.txt."
    dependsOn(bench.classesTaskName, nativeCore)
    val out = layout.buildDirectory.file("bench-classpath.txt")
    val classpath = bench.runtimeClasspath
    outputs.file(out)
    doLast { out.get().asFile.writeText(classpath.asPath) }
}
