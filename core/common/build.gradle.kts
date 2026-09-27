plugins {
    alias(libs.plugins.nudge.jvm.library)
}

dependencies {
    api(libs.javax.inject)
    api(libs.kotlinx.coroutines.core)
}
