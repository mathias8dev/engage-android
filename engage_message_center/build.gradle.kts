import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

plugins {
    id("com.android.library") version "8.11.1"
    id("org.jetbrains.kotlin.android") version "2.0.21"
    id("maven-publish")
}

group = "com.github.mathias8dev"
val engageReleaseVersion = providers.gradleProperty("engageReleaseVersion").get()
val localBuildVersion = providers.provider {
    val timestamp = DateTimeFormatter.ofPattern("yyMMddHHmm")
        .withZone(ZoneOffset.UTC)
        .format(Instant.now())
    "$engageReleaseVersion-$timestamp"
}
val engageVersion = providers.gradleProperty("engageVersion")
    .orElse(providers.environmentVariable("VERSION"))
    .orElse(localBuildVersion)
    .get()
val engageDependencyVersion = providers.gradleProperty("engageDependencyVersion")
    .orElse(providers.environmentVariable("VERSION"))
    .getOrElse("main-SNAPSHOT")
version = engageVersion

android {
    namespace = "io.engage.sdk.messagecenter"
    compileSdk = 36

    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += "-Xjvm-default=all"
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { it.useJUnit() }
    }
    publishing { singleVariant("release") { withSourcesJar() } }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")
    api("com.github.mathias8dev:engage-android-core:$engageDependencyVersion")
    api("androidx.annotation:annotation:1.9.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("org.robolectric:robolectric:4.16.1")
}

publishing {
    publications {
        register<MavenPublication>("release") {
            artifactId = "engage-android-message-center"
            afterEvaluate { from(components["release"]) }
        }
    }
}
