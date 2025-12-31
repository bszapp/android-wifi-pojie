plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("kotlin-parcelize")
}

android {
    namespace = "com.wifi.toolbox"
    compileSdk {
        version = release(36)
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    defaultConfig {
        applicationId = "com.wifi.toolbox"
        minSdk = 24
        //noinspection ExpiredTargetSdkVersion 注:WifiManager需要
        targetSdk = 28
        versionCode = 1
        versionName = "3.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlin {
        compilerOptions {
            jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
        }
    }
    buildFeatures {
        compose = true
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

//    applicationVariants.all {
//        if (name == "release") {
//            outputs.configureEach {
//                (this as? com.android.build.gradle.internal.api.ApkVariantOutputImpl)?.outputFileName =
//                    "${defaultConfig.applicationId}-v${defaultConfig.versionName}.apk"
//            }
//        }
//    }
}

dependencies {
    implementation(libs.androidx.compose.foundation.layout)
    implementation(libs.androidx.ui)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.api)
    implementation(libs.provider)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom.v20251200))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.preference)
    implementation(libs.androidx.compose.runtime.annotation)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)

    implementation(libs.materialKolor)

    implementation(libs.miuix)
    implementation(libs.coil.compose)

    implementation(libs.hiddenapibypass)

    implementation(libs.play.services.location) //注:依赖play服务，打开系统定位（也许有点臃肿，算了不管了）
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}