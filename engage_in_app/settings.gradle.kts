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

rootProject.name = "engage-in-app"

val localCoreDirectory = file("../engage_core").canonicalFile
val parentBuildDirectory = gradle.parent?.startParameter?.currentDir?.canonicalFile
val parentUsesSiblingSources = parentBuildDirectory?.parentFile == localCoreDirectory.parentFile

if ((gradle.parent == null || parentUsesSiblingSources) &&
    file("$localCoreDirectory/settings.gradle.kts").isFile
) {
    includeBuild(localCoreDirectory) {
        dependencySubstitution {
            substitute(module("com.github.mathias8dev:engage-android-core")).using(project(":"))
        }
    }
}
