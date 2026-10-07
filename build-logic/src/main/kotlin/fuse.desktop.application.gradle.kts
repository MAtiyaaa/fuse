import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The Linux (desktop JVM) app.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

composeCompiler {
    // No per-composable source records or trace checks in the code that ships: less work on every
    // recomposition, and nothing that changes what is drawn.
    includeSourceInformation.set(false)
    includeTraceMarkers.set(false)
}
