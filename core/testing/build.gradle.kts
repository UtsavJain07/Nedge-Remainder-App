plugins {
    alias(libs.plugins.nudge.android.library)
}

dependencies {
    api(projects.core.testingJvm)
    api(libs.robolectric)
    api(libs.androidx.test.core)
}
