plugins {
    id("com.android.library") version "8.13.2"
    id("org.jetbrains.kotlin.android") version "2.2.10"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10"
    id("maven-publish")
}

group = "io.engage"
version = providers.gradleProperty("engageVersion").getOrElse("0.1.0-SNAPSHOT")

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

    api("io.engage:engage-core:0.1.0-SNAPSHOT")
    api("androidx.compose.ui:ui:1.11.4")
    implementation("androidx.activity:activity:1.13.0")
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
            artifactId = "engage-in-app"
            afterEvaluate { from(components["release"]) }
        }
    }
}
