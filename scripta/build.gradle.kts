plugins {
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvmToolchain(21)

    android {
        namespace = "top.yukonga.scripta.editor"
        compileSdk {
            version = release(37) {
                minorApiLevel = 2
            }
        }
        minSdk = 24
        withHostTest {}
    }

    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            api(libs.compose.foundation)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
