rootProject.name = "build-logic"

// Kotlin's own build plugin comes from Maven Central first: the plugin portal only mirrors it, and
// when the mirror answers badly the whole build stops before anything compiles.
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    versionCatalogs {
        create("libs") { from(files("../gradle/libs.versions.toml")) }
    }
}
