plugins {
    alias(libs.plugins.nudge.jvm.library)
}

dependencies {
    api(projects.core.domain)
    api(libs.junit4)
    api(libs.truth)
    api(libs.kotlinx.coroutines.test)
    api(libs.turbine)
}
