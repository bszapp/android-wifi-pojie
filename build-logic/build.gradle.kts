plugins {
    `kotlin-dsl`
}

repositories {
    google()
    mavenCentral()
}

dependencies {
    implementation("com.android.tools.build:gradle:${libs.versions.agp.get()}")
}

gradlePlugin {
    plugins {
        register("containerBuild") {
            id = "wifitoolbox.container-build"
            implementationClass = "ContainerBuildPlugin"
        }
    }
}
