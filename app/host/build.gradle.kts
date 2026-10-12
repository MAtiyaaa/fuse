// Fuse One Host: the household host on its own, with no window, no library and no game launching.
// Only Fuse One's hosting (core:sync) and what it stands on; no Compose, Fuseline or Fuse's UI. It
// is what the Docker image runs (tools/host), and it can run straight from `installDist` too.
plugins {
    id("fuse.jvm.application")
}

dependencies {
    implementation(projects.core.sync)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.client.cio)
    testImplementation(kotlin("test"))
}

val fuseVersion: String = providers.gradleProperty("fuse.version").getOrElse("0.0.0")

application {
    mainClass.set("io.github.matiyaaa.fuse.host.HostMainKt")
    applicationName = "fuse-one-host"
    // Small and steady: the host keeps little in memory and serves a handful of devices.
    applicationDefaultJvmArgs = listOf("-Xms32m", "-Xmx256m", "-XX:+UseSerialGC", "-Djava.awt.headless=true", "-Dslf4j.internal.verbosity=ERROR", "-Dfuse.version=$fuseVersion")
}

tasks.test {
    useJUnitPlatform()
}
