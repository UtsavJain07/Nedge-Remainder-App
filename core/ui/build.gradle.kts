plugins {
    alias(libs.plugins.nudge.android.library)
    alias(libs.plugins.nudge.android.compose)
}

dependencies {
    api(projects.core.designsystem)
    api(projects.core.domain)
    api(libs.kotlinx.collections.immutable)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    // Test-only host activity for Compose UI tests; never part of an installed APK.
    testImplementation(libs.androidx.compose.ui.test.manifest)
}
