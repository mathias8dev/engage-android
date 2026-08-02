pluginManagement {
    repositories {
        google()
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

rootProject.name = "engage-push-fcm"

if (file("../engage_core/settings.gradle.kts").isFile) {
    includeBuild("../engage_core") {
        dependencySubstitution {
            substitute(module("io.engage:engage-core")).using(project(":"))
        }
    }
}

