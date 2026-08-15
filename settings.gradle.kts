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

rootProject.name = "engage-android"

include(
    ":engage_core",
    ":engage_push_fcm",
    ":engage_in_app",
    ":engage_message_center",
    ":engage_message_center_divkit",
)
