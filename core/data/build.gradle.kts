plugins {
    alias(libs.plugins.nudge.android.library)
    alias(libs.plugins.nudge.hilt)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(projects.core.domain)
    implementation(projects.core.database)
    implementation(projects.core.datastore)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.room.ktx)
    testImplementation(projects.core.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
