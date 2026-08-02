pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}

rootProject.name = "engage-message-center-divkit"

if (file("../engage_message_center/settings.gradle.kts").isFile) {
    includeBuild("../engage_message_center") {
        dependencySubstitution {
            substitute(module("io.engage:engage-message-center")).using(project(":"))
        }
    }
}
