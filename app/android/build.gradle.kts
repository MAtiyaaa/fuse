plugins {
    id("fuse.android.application")
}

// Release signing comes from the environment (CI secrets). Without it, release builds are signed
// with the debug key so CI still produces an installable APK; such a build is not a real release.
val releaseKeystorePath: String? = providers.environmentVariable("FUSE_KEYSTORE_PATH").orNull
val releaseKeystorePassword: String? = providers.environmentVariable("FUSE_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias: String? = providers.environmentVariable("FUSE_KEY_ALIAS").orNull
val releaseKeyPassword: String? = providers.environmentVariable("FUSE_KEY_PASSWORD").orNull
val hasReleaseKey = listOf(releaseKeystorePath, releaseKeystorePassword, releaseKeyAlias, releaseKeyPassword)
    .all { !it.isNullOrBlank() }

android {
    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        getByName("release") {
            // R8 stays off until keep rules for Ktor, kotlinx.serialization and SQLDelight are written.
            isMinifyEnabled = false
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                logger.warn(
                    "Fuse: FUSE_KEYSTORE_PATH, FUSE_KEYSTORE_PASSWORD, FUSE_KEY_ALIAS or FUSE_KEY_PASSWORD is not set. " +
                        "The release APK is signed with the debug key and cannot update a properly signed install.",
                )
                signingConfigs.getByName("debug")
            }
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/INDEX.LIST",
                "/META-INF/io.netty.versions.properties",
                "/META-INF/versions/9/previous-compilation-data.bin",
            )
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(projects.ui.shell)
    implementation(projects.ui.link)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)

    testImplementation(libs.junit)
}
