plugins {
    kotlin("jvm")
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
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

// The compression microbenchmark in src/bench: ./gradlew :ktron:compressionBench, the whole JVM pinned by taskset
// to -PbenchCpus (default 0); -PbenchArgs passes its own arguments, see CompressionBench.kt.
val bench: SourceSet by sourceSets.creating
kotlin.target.compilations.getByName("bench").associateWith(kotlin.target.compilations.getByName("main"))

tasks.register<Exec>("compressionBench") {
    description = "Runs the compression microbenchmark pinned to -PbenchCpus"
    dependsOn(bench.classesTaskName)
    val java = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
    val cpus = providers.gradleProperty("benchCpus").orElse("0")
    val args = providers.gradleProperty("benchArgs").orElse("")
    val classpath = bench.runtimeClasspath
    doFirst {
        commandLine(
            listOf(
                "taskset", "-c", cpus.get(), java.get().executablePath.asFile.path,
                "--enable-native-access=ALL-UNNAMED", "-cp", classpath.asPath, "ktron.compression.bench.CompressionBenchKt",
            ) + args.get().split(' ').filter { it.isNotEmpty() },
        )
    }
}
