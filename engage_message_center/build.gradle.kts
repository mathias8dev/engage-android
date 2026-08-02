plugins {
    id("com.android.library") version "8.13.2"
    id("org.jetbrains.kotlin.android") version "2.0.21"
    id("maven-publish")
}

group = "io.engage"
version = providers.gradleProperty("engageVersion").getOrElse("0.1.0-SNAPSHOT")

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
    api("io.engage:engage-core:0.1.0-SNAPSHOT")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("org.robolectric:robolectric:4.16.1")
}

publishing {
    publications {
        register<MavenPublication>("release") {
            artifactId = "engage-message-center"
            afterEvaluate { from(components["release"]) }
        }
    }
}

