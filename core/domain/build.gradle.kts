plugins {
    alias(libs.plugins.nudge.jvm.library)
}

dependencies {
    api(projects.core.model)
    api(projects.core.common)
    testImplementation(projects.core.testingJvm)
}
