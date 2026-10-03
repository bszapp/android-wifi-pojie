// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.compose.multiplatform) apply false
}

subprojects {
    // Recreate the runtime class directory in full: a partial incremental copy can
    // leave compiled library classes out of the directory consumed by D8.
    tasks.matching { it.name.startsWith("bundleLibRuntimeToDir") }.configureEach {
        outputs.upToDateWhen { false }
    }
}
