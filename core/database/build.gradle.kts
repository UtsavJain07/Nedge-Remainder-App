plugins {
    alias(libs.plugins.nudge.android.library)
    alias(libs.plugins.nudge.android.room)
    alias(libs.plugins.nudge.hilt)
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.common)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
