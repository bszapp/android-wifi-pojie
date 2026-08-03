import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.Exec
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.text.SimpleDateFormat
import java.util.Properties
import java.util.Base64
import java.util.Date
import java.util.Locale

@DisableCachingByDefault(because = "Stages the generated terminal executable for APK packaging.")
abstract class StageTerminalLibTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun stage() {
        val source = inputDir.get().asFile
        val target = outputDir.get().asFile
        target.deleteRecursively()
        target.mkdirs()

        source.walkTopDown()
            .filter { it.isFile && it.name == "libterminal.so" }
            .forEach { file ->
                val destination = target.resolve(file.relativeTo(source))
                destination.parentFile.mkdirs()
                file.copyTo(destination, overwrite = true)
            }
    }
}

@DisableCachingByDefault(because = "Stages the generated rootfs archive for APK packaging.")
abstract class StageRootfsAssetsTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val inputArchive: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun stage() {
        val target = outputDir.get().asFile
        target.deleteRecursively()
        target.mkdirs()
        inputArchive.get().asFile.copyTo(target.resolve("rootfs.tar.xz"), overwrite = true)
    }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val generatedJniDir = layout.buildDirectory.dir("generated/native-jni")
val generatedRootfsArchive = layout.buildDirectory.file("generated/rootfs/rootfs.tar.xz")
val isWindowsHost = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
val isLinuxHost = System.getProperty("os.name").startsWith("Linux", ignoreCase = true)

require(isWindowsHost || isLinuxHost) {
    "rootfs and libterminal.so builds are supported on Windows with WSL or directly on Ubuntu."
}

fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

fun wslShellCommand(script: String): String {
    val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_8))
    return "printf '%s' '$encoded' | base64 -d | bash"
}

val linuxProjectPath = if (isWindowsHost) {
    val windowsProjectPath = rootProject.projectDir.absolutePath.replace('\\', '/')
    require(windowsProjectPath.length >= 3 && windowsProjectPath[1] == ':') {
        "Windows project path must start with a drive letter: $windowsProjectPath"
    }
    "/mnt/${windowsProjectPath[0].lowercaseChar()}${windowsProjectPath.substring(2)}"
} else {
    rootProject.projectDir.absolutePath
}

android {
    namespace = "io.github.bszapp.wifitoolbox"
    compileSdk {
        version = release(37)
    }
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "io.github.bszapp.wifitoolbox"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "3.1.0-Alpha.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
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
            isMinifyEnabled = false
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

val rootfsWorkDir = if (isWindowsHost) {
    "/var/tmp/wifitoolbox-rootfs-build"
} else {
    layout.buildDirectory.dir("container-work/rootfs").get().asFile.absolutePath
}
val nativeWorkDir = if (isWindowsHost) {
    "/var/tmp/wifitoolbox-native-build"
} else {
    layout.buildDirectory.dir("container-work/native").get().asFile.absolutePath
}
val distfilesCacheDir = if (isWindowsHost) {
    "/var/cache/wlantool-rootfs/distfiles"
} else {
    gradle.gradleUserHomeDir.resolve("caches/wifitoolbox-rootfs/distfiles").absolutePath
}
val generatedRootfsLinuxPath = if (isWindowsHost) {
    "$linuxProjectPath/app/build/generated/rootfs/rootfs.tar.xz"
} else {
    generatedRootfsArchive.get().asFile.absolutePath
}
val generatedJniLinuxPath = if (isWindowsHost) {
    "$linuxProjectPath/app/build/generated/native-jni"
} else {
    generatedJniDir.get().asFile.absolutePath
}

val buildRootfsCommand = """
    set -eu
    PROJECT=${shellQuote(linuxProjectPath)}
    WORK_DIR=${shellQuote(rootfsWorkDir)}
    CACHE_DIR=${shellQuote(distfilesCacheDir)}
    OUTPUT=${shellQuote(generatedRootfsLinuxPath)}
    test "${'$'}WORK_DIR" = ${shellQuote(rootfsWorkDir)}
    rm -rf -- "${'$'}WORK_DIR"
    mkdir -p "${'$'}WORK_DIR/rftoolbuilder" "${'$'}WORK_DIR/app/src/main/assets" "${'$'}CACHE_DIR"
    cp "${'$'}PROJECT/rftoolbuilder/build.sh" "${'$'}WORK_DIR/rftoolbuilder/build.sh"
    cp "${'$'}PROJECT/rftoolbuilder/downloads.sh" "${'$'}WORK_DIR/rftoolbuilder/downloads.sh"
    sed -i 's/\r${'$'}//' "${'$'}WORK_DIR/rftoolbuilder/build.sh" "${'$'}WORK_DIR/rftoolbuilder/downloads.sh"
    tar --ignore-case --exclude='src/wlantool/readme*' -C "${'$'}PROJECT/rftoolbuilder" -cf - src | tar -C "${'$'}WORK_DIR/rftoolbuilder" -xf -
    ln -s "${'$'}CACHE_DIR" "${'$'}WORK_DIR/rftoolbuilder/distfiles"
    cd "${'$'}WORK_DIR"
    sh ./rftoolbuilder/downloads.sh
    sh ./rftoolbuilder/build.sh
    mkdir -p "${'$'}(dirname "${'$'}OUTPUT")"
    install -m 644 ./app/src/main/assets/rootfs.tar.xz "${'$'}OUTPUT"
""".trimIndent()

val buildRootfs = tasks.register<Exec>("buildRootfs") {
    group = "build"
    description = "Builds rootfs.tar.xz with WSL on Windows or directly on Ubuntu."
    inputs.files(rootProject.fileTree("rftoolbuilder") {
        include("build.sh", "downloads.sh", "src/**")
        exclude("**/README", "**/README.*", "**/Readme", "**/Readme.*", "**/readme", "**/readme.*")
    })
    outputs.file(generatedRootfsArchive)

    if (isWindowsHost) {
        commandLine(
            "wsl.exe", "-d", "Ubuntu-24.04", "-u", "root", "--",
            "bash", "-lc", wslShellCommand(buildRootfsCommand),
        )
    } else {
        commandLine("bash", "-lc", buildRootfsCommand)
    }
}

val linuxAndroidSdkDir = if (isLinuxHost) {
    providers.environmentVariable("ANDROID_SDK_ROOT")
        .orElse(providers.environmentVariable("ANDROID_HOME"))
        .orElse(providers.provider {
            val properties = Properties()
            val localProperties = rootProject.file("local.properties")
            if (localProperties.isFile) {
                localProperties.inputStream().use { properties.load(it) }
            }
            properties.getProperty("sdk.dir")
                ?: error("Set ANDROID_SDK_ROOT, ANDROID_HOME, or sdk.dir in local.properties.")
        })
        .get()
} else {
    null
}
val linuxNdkDir = if (isWindowsHost) {
    "/var/cache/wlantool-android/android-ndk-r27c"
} else {
    File(linuxAndroidSdkDir!!, "ndk/27.2.12479018").absolutePath
}
val linuxCmakeBinDir = if (isWindowsHost) {
    null
} else {
    File(linuxAndroidSdkDir!!, "cmake/3.22.1/bin").absolutePath
}
val cmakeSetup = if (linuxCmakeBinDir == null) {
    "CMAKE_BIN=cmake"
} else {
    """
        SDK_CMAKE_BIN=${shellQuote(linuxCmakeBinDir)}
        if [ -x "${'$'}SDK_CMAKE_BIN/cmake" ]; then
          CMAKE_BIN="${'$'}SDK_CMAKE_BIN/cmake"
          PATH="${'$'}SDK_CMAKE_BIN:${'$'}PATH"
          export PATH
        else
          CMAKE_BIN=${'$'}(command -v cmake)
        fi
    """.trimIndent()
}
val buildTerminalCommand = """
    set -eu
    PROJECT=${shellQuote(linuxProjectPath)}
    WORK_DIR=${shellQuote(nativeWorkDir)}
    CACHE_DIR=${shellQuote(distfilesCacheDir)}
    OUTPUT_DIR=${shellQuote(generatedJniLinuxPath)}
    NDK_DIR=${shellQuote(linuxNdkDir)}
    test "${'$'}WORK_DIR" = ${shellQuote(nativeWorkDir)}
    test -x "${'$'}NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/bin/clang" || {
      echo "Linux Android NDK r27c is missing at ${'$'}NDK_DIR" >&2
      exit 1
    }
    rm -rf -- "${'$'}WORK_DIR"
    mkdir -p "${'$'}WORK_DIR/app/src/main" "${'$'}WORK_DIR/rftoolbuilder" "${'$'}CACHE_DIR"
    tar -C "${'$'}PROJECT/app/src/main" -cf - cpp | tar -C "${'$'}WORK_DIR/app/src/main" -xf -
    cp "${'$'}PROJECT/rftoolbuilder/downloads.sh" "${'$'}WORK_DIR/rftoolbuilder/downloads.sh"
    sed -i 's/\r${'$'}//' "${'$'}WORK_DIR/rftoolbuilder/downloads.sh"
    find "${'$'}WORK_DIR/app/src/main/cpp" -type f -name '*.sh' -exec sed -i 's/\r${'$'}//' {} +
    ln -s "${'$'}CACHE_DIR" "${'$'}WORK_DIR/rftoolbuilder/distfiles"
    $cmakeSetup
    cd "${'$'}WORK_DIR"
    "${'$'}CMAKE_BIN" -S ./app/src/main/cpp -B ./build/native -G Ninja \
      -DCMAKE_TOOLCHAIN_FILE="${'$'}NDK_DIR/build/cmake/android.toolchain.cmake" \
      -DANDROID_ABI=arm64-v8a \
      -DANDROID_PLATFORM=android-24 \
      -DAPP_GENERATED_JNI_DIR="${'$'}WORK_DIR/out"
    "${'$'}CMAKE_BIN" --build ./build/native --target terminal
    mkdir -p "${'$'}OUTPUT_DIR/arm64-v8a"
    find "${'$'}OUTPUT_DIR" -type f -name '*.so' ! -name 'libterminal.so' -delete
    install -m 755 ./out/arm64-v8a/libterminal.so "${'$'}OUTPUT_DIR/arm64-v8a/libterminal.so"
""".trimIndent()

val buildTerminal = tasks.register<Exec>("buildTerminal") {
    group = "build"
    description = "Builds the unified libterminal.so with the Linux Android NDK."
    dependsOn(buildRootfs)
    inputs.files(rootProject.fileTree("app/src/main/cpp"))
    inputs.file(rootProject.file("rftoolbuilder/downloads.sh"))
    outputs.file(generatedJniDir.map { it.file("arm64-v8a/libterminal.so") })

    if (isWindowsHost) {
        commandLine(
            "wsl.exe", "-d", "Ubuntu-24.04", "-u", "root", "--",
            "bash", "-lc", wslShellCommand(buildTerminalCommand),
        )
    } else {
        commandLine("bash", "-lc", buildTerminalCommand)
    }
}

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

androidComponents {
    onVariants { variant ->
        val variantName = variant.name.replaceFirstChar { it.uppercase() }

        val stageTerminalLib = tasks.register<StageTerminalLibTask>("stage${variantName}TerminalLib") {
            dependsOn(buildTerminal)
            inputDir.set(generatedJniDir)
            outputDir.set(layout.buildDirectory.dir("generated/terminal-jni/${variant.name}"))
        }
        variant.sources.jniLibs?.addGeneratedSourceDirectory(
            stageTerminalLib,
            StageTerminalLibTask::outputDir,
        )

        val stageRootfsAssets = tasks.register<StageRootfsAssetsTask>("stage${variantName}RootfsAssets") {
            dependsOn(buildRootfs)
            inputArchive.set(generatedRootfsArchive)
            outputDir.set(layout.buildDirectory.dir("generated/rootfs-assets/${variant.name}"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(
            stageRootfsAssets,
            StageRootfsAssetsTask::outputDir,
        )
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
    implementation(libs.xz)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
