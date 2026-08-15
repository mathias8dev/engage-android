import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

plugins {
    id("com.android.library") version "8.11.1"
    id("org.jetbrains.kotlin.android") version "2.2.10"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10"
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
    namespace = "io.engage.sdk.inapp"
    compileSdk = 36

    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures { compose = true }

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
    api("androidx.compose.ui:ui:1.11.4")
    implementation("androidx.activity:activity:1.13.0")
    implementation("androidx.compose.foundation:foundation-layout:1.11.4")
    implementation("androidx.compose.runtime:runtime:1.11.4")
    implementation("androidx.compose.ui:ui-viewbinding:1.11.4")
    implementation("com.yandex.div:div:32.60.0")
    implementation("com.yandex.div:div-core:32.60.0")
    implementation("com.yandex.div:div-json:32.60.0")
    implementation("com.yandex.div:coil:32.60.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("org.robolectric:robolectric:4.16.1")
}

publishing {
    publications {
        register<MavenPublication>("release") {
            artifactId = "engage-android-in-app"
            afterEvaluate { from(components["release"]) }
        }
    }
}
