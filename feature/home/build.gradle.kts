plugins {
    alias(libs.plugins.nudge.android.feature)
}

dependencies {
    implementation(projects.feature.taskdetail)
    implementation(projects.feature.quickadd)
}

dependencies {
    implementation(libs.reorderable)
}
