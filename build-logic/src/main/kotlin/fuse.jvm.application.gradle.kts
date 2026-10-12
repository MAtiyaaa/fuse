import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// A plain JVM program with no UI (Fuse One Host).
plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
