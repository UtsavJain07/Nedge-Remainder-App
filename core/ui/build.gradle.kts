plugins {
    alias(libs.plugins.nudge.android.library)
    alias(libs.plugins.nudge.android.compose)
}

dependencies {
    api(projects.core.designsystem)
    api(projects.core.domain)
    api(libs.kotlinx.collections.immutable)
    implementation(libs.androidx.lifecycle.runtime.compose)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
