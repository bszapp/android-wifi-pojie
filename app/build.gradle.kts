import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("wifitoolbox.container-build")
}


android {
    namespace = "io.github.bszapp.wifitoolbox"
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }

    }
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "io.github.bszapp.wifitoolbox"
        minSdk = 24
        targetSdk = 37
        versionCode = 2
        versionName = "3.1.0-Alpha.8"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            //noinspection ChromeOsAbiSupport ChromeOS关我何事？
            abiFilters += "arm64-v8a"
        }

        val buildTime = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        val gitId = getGitCommitId()

        buildConfigField("String", "BUILD_DATE", "\"$buildTime\"")
        buildConfigField("String", "GIT_ID", "\"$gitId\"")
    }

    androidResources {
        noCompress += "xz"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}


configureContainerBuild()


fun getGitCommitId(): String {
    return try {
        val process = ProcessBuilder("git", "rev-parse", "--short", "HEAD").start()
        val text = process.inputStream.bufferedReader().readText().trim()
        process.waitFor()
        text.ifEmpty { "unknown" }
    } catch (_: Exception) {
        "unknown"
    }
}


dependencies {
    implementation(project(":ui-default"))
    implementation(project(":contract"))
    implementation(project(":service"))

    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.material.kolor)

    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.api)
    implementation(libs.provider)
    implementation(libs.hiddenapibypass)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
