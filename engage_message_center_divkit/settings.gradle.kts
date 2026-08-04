pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io") {
            content { includeGroup("com.github.mathias8dev") }
        }
    }
}

rootProject.name = "engage-message-center-divkit"

val localMessageCenterDirectory = file("../engage_message_center").canonicalFile
val parentBuildDirectory = gradle.parent?.startParameter?.currentDir?.canonicalFile
val parentUsesSiblingSources =
    parentBuildDirectory?.parentFile == localMessageCenterDirectory.parentFile

if ((gradle.parent == null || parentUsesSiblingSources) &&
    file("$localMessageCenterDirectory/settings.gradle.kts").isFile
) {
    includeBuild(localMessageCenterDirectory) {
        dependencySubstitution {
            substitute(module("com.github.mathias8dev:engage-android-message-center")).using(project(":"))
        }
    }
}
