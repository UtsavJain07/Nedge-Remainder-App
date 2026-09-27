import java.util.Properties

plugins {
    alias(libs.plugins.nudge.android.application)
    alias(libs.plugins.nudge.android.compose)
    alias(libs.plugins.nudge.hilt)
    alias(libs.plugins.kotlin.serialization)
}

// 12 §2: versionCode = MAJOR*10000 + MINOR*100 + PATCH (+ optional CI offset).
val appVersionName = providers.gradleProperty("nudge.versionName").get()
val appVersionCode = appVersionName.split('.').map { it.toInt() }.let { (ma, mi, pa) -> ma * 10000 + mi * 100 + pa } +
    (providers.environmentVariable("VERSION_CODE_OFFSET").orNull?.toIntOrNull() ?: 0)

// 12 §4: signing from env vars (CI) or local.properties; never committed.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(name: String): String? = providers.environmentVariable(name).orNull ?: localProps.getProperty(name)

android {
    namespace = "app.nudge"

    defaultConfig {
        applicationId = "app.nudge.reminders"
        versionCode = appVersionCode
        versionName = appVersionName
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        val storePath = secret("KEYSTORE_PATH")
        if (storePath != null && file(storePath).exists()) {
            create("release") {
                storeFile = file(storePath)
                storePassword = secret("KEYSTORE_PASSWORD")
                keyAlias = secret("KEY_ALIAS")
                keyPassword = secret("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/LICENSE*", "/META-INF/*.kotlin_module")
    }
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.core.domain)
    implementation(projects.core.database)
    implementation(projects.core.datastore)
    implementation(projects.core.data)
    implementation(projects.core.reminders)
    implementation(projects.core.designsystem)
    implementation(projects.core.ui)
    implementation(projects.feature.onboarding)
    implementation(projects.feature.home)
    implementation(projects.feature.list)
    implementation(projects.feature.taskdetail)
    implementation(projects.feature.quickadd)
    implementation(projects.feature.smartview)
    implementation(projects.feature.search)
    implementation(projects.feature.settings)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.timber)
    debugImplementation(libs.leakcanary.android)

    testImplementation(projects.core.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.compiler)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
