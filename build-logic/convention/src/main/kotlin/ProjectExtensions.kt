import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmCompilerOptions

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.version(alias: String): String = findVersion(alias).get().requiredVersion

internal fun VersionCatalog.lib(alias: String) = findLibrary(alias).get()

/** Shared Android config for application + library modules (05 §2, 10 M0). */
internal fun Project.configureKotlinAndroid(ext: CommonExtension) {
    ext.compileSdk = libs.version("compileSdk").toInt()
    ext.defaultConfig.minSdk = libs.version("minSdk").toInt()
    ext.defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    ext.compileOptions.sourceCompatibility = JavaVersion.VERSION_17
    ext.compileOptions.targetCompatibility = JavaVersion.VERSION_17
    ext.testOptions.unitTests.isIncludeAndroidResources = true
    ext.testOptions.unitTests.isReturnDefaultValues = true
    ext.lint.abortOnError = true
    ext.lint.checkDependencies = false
    ext.lint.disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "ObsoleteLintCustomCheck")
    configureKotlin()
}

internal fun Project.configureKotlin() {
    tasks.withType<KotlinCompilationTask<*>>().configureEach {
        val options = compilerOptions
        if (options is KotlinJvmCompilerOptions) options.jvmTarget.set(JvmTarget.JVM_17)
        options.freeCompilerArgs.addAll(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-Xannotation-default-target=param-property",
        )
    }
}
