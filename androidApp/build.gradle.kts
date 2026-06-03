import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.android.application)
}

val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("keystore.properties")

if (keystorePropertiesFile.exists()) {
    FileInputStream(keystorePropertiesFile).use { input ->
        keystoreProperties.load(input)
    }
}

val hasReleaseKeystore =
    keystorePropertiesFile.exists() &&
        listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
            .all { key -> !keystoreProperties.getProperty(key).isNullOrBlank() }
val olcboxVersion = providers.gradleProperty("olcbox.version").orElse("1.0.0")
val olcboxVersionCode = providers.gradleProperty("olcbox.versionCode")
    .map { it.toInt() }
    .orElse(1)
val defaultAndroidAbiFilters = listOf("armeabi-v7a", "arm64-v8a", "x86_64")
val androidAbiFilters = providers.gradleProperty("olcbox.android.abiFilters")
    .map { value ->
        value.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }
    .getOrElse(defaultAndroidAbiFilters)

require(androidAbiFilters.isNotEmpty()) {
    "olcbox.android.abiFilters must contain at least one Android ABI"
}

android {
    namespace = "org.olcbox.app"
    compileSdk = 37
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 23
        targetSdk = 37

        applicationId = "org.olcbox.app"
        versionCode = olcboxVersionCode.get()
        versionName = olcboxVersion.get()

        ndk {
            abiFilters += androidAbiFilters
        }
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }

            isMinifyEnabled = false
            isShrinkResources = false
        }

        release {
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }

            isMinifyEnabled = true
            isShrinkResources = true

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs", "jniLibs")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

// In AGP 9.0+ Kotlin settings for Android are configured like this:
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":sharedUI"))
    implementation(libs.androidx.activityCompose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.datastore.preferences)
}
