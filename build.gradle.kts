import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

plugins {
    id("com.android.library") version "8.11.1" apply false
    id("org.jetbrains.kotlin.android") version "2.2.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20" apply false
}

val releaseVersion = providers.gradleProperty("engageReleaseVersion").get()
val localBuildVersion = providers.provider {
    val timestamp = DateTimeFormatter.ofPattern("yyMMddHHmm")
        .withZone(ZoneOffset.UTC)
        .format(Instant.now())
    "$releaseVersion-$timestamp"
}
val engageVersion = providers.gradleProperty("engageVersion")
    .orElse(providers.environmentVariable("VERSION"))
    .orElse(localBuildVersion)
    .get()

allprojects {
    group = "com.github.mathias8dev.engage-android"
    version = engageVersion
}
