import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("maven-publish")
}

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
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { it.useJUnit() }
    }
    publishing { singleVariant("release") { withSourcesJar() } }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")
    api(project(":engage_core"))
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
