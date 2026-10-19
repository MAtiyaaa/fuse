import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Shared Kotlin Multiplatform module: Android (AGP 9 KMP library plugin) + Linux/desktop JVM.
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    android {
        namespace = project.fuseNamespace
        compileSdk = libs.int("android-compileSdk")
        minSdk = libs.int("android-minSdk")
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    jvm("desktop") {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    compilerOptions {
        freeCompilerArgs.addAll("-Xexpect-actual-classes")
        optIn.addAll("kotlinx.coroutines.ExperimentalCoroutinesApi")
    }
    sourceSets {
        commonMain.dependencies {
            implementation(libs.lib("kotlinx-coroutines-core"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.lib("kotlinx-coroutines-test"))
        }
    }
}

// A failing test says its message and the whole trace in the build's log (CI's too), not only the
// exception's name, so a failure that won't happen again on purpose still says what it was.
tasks.withType<Test>().configureEach {
    testLogging {
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
