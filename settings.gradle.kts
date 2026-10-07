rootProject.name = "fuse"

pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(
    ":core:model",
    ":core:library",
    ":core:launch",
    ":core:integrations",
    ":core:data",
    ":core:playback",
    ":core:jellyfin",
    ":core:sync",
    ":core:transfer",
    ":core:romm",
    ":core:reach",
    ":ui:fuseline",
    ":ui:designsystem",
    ":ui:player",
    ":ui:shell",
    ":ui:link",
    ":app:android",
    ":app:desktop",
)
