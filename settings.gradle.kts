pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "Ktron"

include(":glyph11", ":ktron")

// The Kotlin port of ioxide, Ktron's runtime: kotlin/ioxide of an ioxide checkout next to this one, or wherever
// -PioxideKt points.
include(":ioxide")
project(":ioxide").projectDir = file(providers.gradleProperty("ioxideKt").getOrElse("../ioxide/kotlin/ioxide"))
