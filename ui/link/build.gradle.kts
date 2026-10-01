import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPOutputStream

plugins {
    id("fuse.kmp.library")
    id("fuse.serialization")
}

// The phone web app (plain HTML, CSS and JavaScript in src/web) is bundled into the app as gzipped,
// base64 chunks in a generated Kotlin file, so it works offline and needs no resource plumbing.
val generateWebAssets by tasks.registering {
    description = "Bundles the Phone Link web app into WebAssets.kt."
    // Plain locals so the task action holds no reference to the build script (configuration cache).
    val webDir = layout.projectDirectory.dir("src/web").asFile
    val webAssetsDir = layout.buildDirectory.dir("generated/webAssets/kotlin")
    inputs.dir(webDir)
    outputs.dir(webAssetsDir)
    doLast {
        val out = webAssetsDir.get().file("io/github/matiyaaa/fuse/link/WebAssets.kt").asFile
        out.parentFile.mkdirs()
        val files = webDir.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }
        val code = StringBuilder()
        code.append("// Generated from ui/link/src/web by :ui:link:generateWebAssets. Do not edit.\n")
        code.append("package io.github.matiyaaa.fuse.link\n\n")
        code.append("internal object WebAssets {\n")
        code.append("    /** Published path to gzipped bytes as base64 chunks (class constants are limited in size). */\n")
        code.append("    val files: Map<String, List<String>> = mapOf(\n")
        for (f in files) {
            val gz = ByteArrayOutputStream().also { b -> GZIPOutputStream(b).use { it.write(f.readBytes()) } }.toByteArray()
            val b64 = Base64.getEncoder().encodeToString(gz)
            val chunks = b64.chunked(48_000).joinToString(", ") { "\"$it\"" }
            code.append("        \"/${f.name}\" to listOf($chunks),\n")
        }
        code.append("    )\n}\n")
        out.writeText(code.toString())
    }
}

kotlin {
    sourceSets {
        commonMain {
            kotlin.srcDir(generateWebAssets)
            dependencies {
                api(projects.ui.shell)
                implementation(libs.ktor.server.core)
                implementation(libs.ktor.server.cio)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.datetime)
            }
        }
        // Android and the desktop are both the JVM: crypto, networking and the QR encoder live here once.
        val jvmShared by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation(libs.qrcodegen)
            }
        }
        androidMain.get().dependsOn(jvmShared)
        getByName("desktopMain").dependsOn(jvmShared)
        getByName("desktopTest").dependencies {
            implementation(libs.ktor.client.mock)
        }
    }
}
