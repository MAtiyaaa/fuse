plugins {
    id("fuse.android.application")
}

dependencies {
    implementation(projects.ui.designsystem)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
}
