pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Nudge"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":app")
include(":core:model")
include(":core:common")
include(":core:domain")
include(":core:database")
include(":core:datastore")
include(":core:data")
include(":core:reminders")
include(":core:designsystem")
include(":core:ui")
include(":core:testing")
include(":core:testing-jvm")
include(":feature:onboarding")
include(":feature:home")
include(":feature:list")
include(":feature:taskdetail")
include(":feature:quickadd")
include(":feature:smartview")
include(":feature:search")
include(":feature:settings")
