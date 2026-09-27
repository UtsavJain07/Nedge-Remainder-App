plugins {
    alias(libs.plugins.nudge.android.library)
    alias(libs.plugins.nudge.hilt)
}

dependencies {
    api(projects.core.domain)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.timber)
    testImplementation(projects.core.testingJvm)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.work.testing)
}
