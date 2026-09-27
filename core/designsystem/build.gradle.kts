plugins {
    alias(libs.plugins.nudge.android.library)
    alias(libs.plugins.nudge.android.compose)
}

dependencies {
    api(projects.core.model)
    api(libs.androidx.compose.foundation)
    api(libs.androidx.compose.animation)
    api(libs.androidx.compose.material3)
    api(libs.androidx.compose.material.icons.extended)
    api(libs.androidx.graphics.shapes)
    implementation(libs.androidx.core.ktx)
    implementation(libs.materialkolor)
    implementation(libs.kotlinx.collections.immutable)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
