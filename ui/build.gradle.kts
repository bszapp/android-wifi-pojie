plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    id("kotlin-parcelize")
}

android {
    namespace = "io.github.bszapp.wifitoolbox.ui"
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        minSdk = 24

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
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
}

dependencies {
    implementation(project(":contract"))
    implementation(project(":scripta"))
    implementation("com.google.zxing:core:3.5.4")

    implementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.compose.runtime)
    // materialkolor 的 rememberDynamicColorScheme 返回 androidx.compose.material3.ColorScheme，
    // 仅供配色预览取色；ui-default 源码已不再使用任何 material3 组件或 MaterialTheme token。
    implementation(libs.androidx.compose.material3)
    implementation(libs.material.kolor)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.ui)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.blur)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.miuix.navigation3.ui)
    implementation(libs.androidx.navigationevent.compose)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.activity.compose)

    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.compose.ui.tooling.preview)
}

// ───────────────────────── UI QA 生图（qbsg 方式，不新增模块）─────────────────────────
// ui/qa/*.kt 作为渲染器源文件（qa 包），用 :ui 内置 Kotlin 输出 + JetBrains CMP 桌面工件组装
// JVM 运行时，headless Skia 软件光栅化出 PNG；产物位于 ui/build/ui-qa/<场景>/<变体>.png。
val uiQaRuntime = configurations.create("uiQaRuntime") {
    isCanBeConsumed = false
    isCanBeResolved = true
}
val uiQaCompiler = configurations.create("uiQaCompiler") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

// material-icons 桌面工件在 CMP ≥1.8 已停更；改用 ui 模块实际编译时的 androidx 版本（KMP desktop 变体 jar）。
// 不传递依赖，避免拖入旧版桌面 compose 全家桶。
val uiQaIcons = configurations
    .detachedConfiguration(
        dependencies.create("androidx.compose.material:material-icons-core:1.7.8"),
        dependencies.create("androidx.compose.material:material-icons-extended:1.7.8"),
    )
    .apply { isTransitive = false }

dependencies {
    val cmp = "1.12.0" // 渲染运行时跟随 CMP 1.12.0（与 ui 模块编译期对齐）
    val coroutines = libs.versions.coroutines.get()
    "uiQaCompiler"("org.jetbrains.kotlin:kotlin-compiler-embeddable:${libs.versions.kotlin.get()}")
    "uiQaCompiler"("org.jetbrains.kotlin:kotlin-compose-compiler-plugin-embeddable:${libs.versions.kotlin.get()}")
    "uiQaCompiler"("org.jetbrains.kotlin:kotlin-parcelize-compiler:${libs.versions.kotlin.get()}")
    "uiQaRuntime"("org.jetbrains.kotlin:kotlin-stdlib:${libs.versions.kotlin.get()}")
    "uiQaRuntime"("org.jetbrains.kotlin:kotlin-parcelize-runtime:${libs.versions.kotlin.get()}")
    "uiQaRuntime"("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:$coroutines")
    "uiQaRuntime"("org.jetbrains.kotlinx:kotlinx-coroutines-android:$coroutines")
    "uiQaRuntime"("com.google.zxing:core:3.5.4")
    "uiQaRuntime"("org.jetbrains.kotlinx:kotlinx-coroutines-swing:$coroutines")
    "uiQaRuntime"("org.jetbrains.compose.ui:ui-desktop:$cmp")
    "uiQaRuntime"("org.jetbrains.compose.foundation:foundation-desktop:$cmp")
    "uiQaRuntime"("org.jetbrains.compose.runtime:runtime-desktop:$cmp")
    "uiQaRuntime"("org.jetbrains.compose.runtime:runtime-saveable-desktop:$cmp")
    "uiQaRuntime"("org.jetbrains.compose.desktop:desktop-jvm:$cmp")
    // skiko 平台原生库：无属性解析只拿到 skiko-awt 本体，需显式带上本机 runtime 构件。
    // skiko 平台原生库：无属性解析只拿到 skiko-awt 本体，需显式带上本机 runtime 构件。
    "uiQaRuntime"("org.jetbrains.skiko:skiko-awt-runtime-windows-x64:0.150.1")
    // ui/navigation 只消费 NavKey（渲染路径）。
    "uiQaRuntime"("androidx.navigation3:navigation3-runtime-desktop:${libs.versions.navigation3.get()}")
    val miuixQa = "0.9.4" // 渲染侧对抗 skiko 0.150.x 的 Matrix33 API 变更（0.9.3 squircle 用旧签名）；生产件保持 0.9.3。
    "uiQaRuntime"("top.yukonga.miuix.kmp:miuix-ui-desktop:$miuixQa")
    "uiQaRuntime"("top.yukonga.miuix.kmp:miuix-blur-desktop:$miuixQa")
    "uiQaRuntime"("top.yukonga.miuix.kmp:miuix-icons-desktop:$miuixQa")
    "uiQaRuntime"("top.yukonga.miuix.kmp:miuix-preference-desktop:$miuixQa")
    "uiQaRuntime"("com.materialkolor:material-kolor-jvm:5.0.0")
}

val uiQaDir = layout.buildDirectory.dir("ui-qa")

// 已编译 Kotlin 产出的运行时目录（AGP9 内置 kotlinc 输出位置 + KMP desktop 目标）。

val uiQaPrepareClasspath = tasks.register("uiQaPrepareClasspath") {
    dependsOn("compileDebugKotlin", ":contract:compileDebugKotlin", ":scripta:compileKotlinDesktop")
    val outDir = uiQaDir.get().asFile
    outputs.file(outDir.resolve("runtime-classpath.txt"))
    outputs.file(outDir.resolve("compiler-classpath.txt"))
    doLast {
        outDir.mkdirs()
        val uiClasses = tasks.named("compileDebugKotlin").get().outputs.files
        val contractClasses =
            project(":contract").tasks.named("compileDebugKotlin").get().outputs.files.filter { it.isDirectory }
        val scriptaClasses =
            project(":scripta").tasks.named("compileKotlinDesktop").get().outputs.files
        // 不带 scripta 的 runtimeDependencyFiles：由 uiQaRuntime 统一提供 CMP 桌面运行时，避免两套版本混载。
        // 深度扫描：built_in_kotlinc 直出类根；KMP 目标（scripta）在 classes/kotlin/<target>/main 下。
        fun visit(dir: File, depth: Int, into: LinkedHashSet<File>) {
            if (depth > 4) return
            if (dir.name == "android") return // scripta 的 android 目标输出不参与 JVM 运行时
            if (dir.resolve("io").isDirectory || dir.resolve("top").isDirectory) {
                into += dir
                return
            }
            dir.listFiles()?.filter { it.isDirectory }?.forEach { visit(it, depth + 1, into) }
        }
        val uiRoots = LinkedHashSet<File>()
        val contractRoots = LinkedHashSet<File>()
        val scriptaRoots = LinkedHashSet<File>()
        uiClasses.filter { it.isDirectory }.forEach { visit(it, 0, uiRoots) }
        contractClasses.filter { it.isDirectory }.forEach { visit(it, 0, contractRoots) }
        scriptaClasses.filter { it.isDirectory }.forEach { visit(it, 0, scriptaRoots) }
        val classRoots = LinkedHashSet<File>().apply {
            addAll(uiRoots)
            addAll(contractRoots)
            addAll(scriptaRoots)
        }

        val entries = LinkedHashSet<File>().apply {
            addAll(classRoots)
            addAll(uiQaRuntime.resolve())
            addAll(uiQaIcons.resolve()) // material-icons KMP 版（desktop 变体 jar，纯 Kotlin 可直接上类路径）
        }
        outDir.resolve("runtime-classpath.txt").writeText(entries.joinToString(File.pathSeparator) { it.absolutePath })
        // 编译器类路径：仅 contract/scripta 类根 + qa 运行时 jars。
        // ui 生产源码不走预编译类：qa 单体把“桩 + ui 生产源码 + qa”一起重编，
        // 保证字节码对同一套桌面依赖元数据（miuix/QA 版本）自洽，避免 0.9.3 类 × 0.9.4 jar 混载。
        // mock android 框架类型仅编译期：真 android.jar 是全桩体（方法体报 Stub!），运行时由 qa 桩遮蔽。
        // sdk.dir 是 properties 转义形式（D\:\\Android\\Sdk）：手工反转义。
        val sdkRaw = rootProject.file("local.properties").readLines()
            .firstOrNull { it.trim().startsWith("sdk.dir") }?.substringAfter('=')?.trim() ?: ""
        val sdkRoot = sdkRaw.replace("\\:", ":").replace("\\\\", "\\").ifBlank {
            System.getenv("ANDROID_HOME") ?: "D:/Android/Sdk"
        }
        val androidJar = File(sdkRoot, "platforms/android-36/android.jar")
        require(androidJar.isFile) { "uiQaCompile 需要 android.jar：$androidJar" }
        outDir.resolve("compile-runtime-classpath.txt").writeText(
            LinkedHashSet<File>().apply {
                addAll(contractRoots)
                addAll(scriptaRoots)
                addAll(uiQaRuntime.resolve())
                addAll(uiQaIcons.resolve())
                add(androidJar)
            }.joinToString(File.pathSeparator) { it.absolutePath },
        )
        outDir.resolve("compiler-classpath.txt").writeText(
            uiQaCompiler.resolve().joinToString(File.pathSeparator) { it.absolutePath },
        )
    }
}

// 生产源码整编进 qa 单体（与桩同编译单元）；Android-only 的整页屏幕/壳被排除出编译面。
private val uiQaExcludeMarkers = listOf(
    "/ui/DefaultUIMain.kt",
    // dictionary 仅保留纯状态/存储（PskSecurityAuditUiState 依赖）；UI 相关的排除。
    "/ui/dictionary/DictionaryDialog.kt",
    "/ui/dictionary/DictionaryDetailSheet.kt",
    "/ui/dictionary/DictionaryEditor.kt",
    "/ui/dictionary/DictionaryPage.kt",
    "/ui/dictionary/DictionaryScreen.kt",
    "/ui/dictionary/DictionaryResourceItem.kt",
    "/ui/dictionary/DictionaryTag.kt",
    "/ui/dictionary/DictionaryViewModel.kt",
    "/ui/dictionary/DictionaryState.kt",
    "/ui/theme/MiuixAppTheme.kt",
    "/ui/hashcat/",
    "/ui/screen/settings/",
    "/ui/screen/CaptureScreen.kt",
    "/ui/screen/HomeScreen.kt",
    "/ui/screen/LogScreen.kt",
    "/ui/screen/ToolsScreen.kt",
    "/ui/screen/SettingsScreen.kt",
    "/ui/screen/WifiQrScannerScreen.kt",
    "/ui/screen/CaptureFavicon.kt",
    "/ui/screen/ListScreen.kt",
    "/ui/model/CaptureViewModel.kt",
    "/ui/component/FloatingBottomBar.kt",
    "/ui/component/bottombar/BottomBarMiuix.kt",
    "/ui/component/miuix/animation/InteractiveHighlight.kt",
    "/ui/component/miuix/animation/DampedDragAnimation.kt",
)

val uiProductionSources: List<File> =
    projectDir.resolve("src/main/java").walkTopDown()
        .onEnter { it.name != "build" }
        .filter { it.isFile && it.extension == "kt" }
        .filter { f -> uiQaExcludeMarkers.none { f.invariantSeparatorsPath.contains(it) } }
        .toList()

val uiQaSources: List<File> =
    projectDir.resolve("qa").listFiles { f -> f.extension == "kt" }?.toList().orEmpty()

val uiQaCompile = tasks.register("uiQaCompile", org.gradle.api.tasks.JavaExec::class) {
    dependsOn(uiQaPrepareClasspath)
    val qaDir = uiQaDir.get().asFile
    mainClass = "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler"
    classpath = files(uiQaCompiler)
    jvmArgs("-Xmx2g")
    doFirst {
        val classesOut = qaDir.resolve("classes")
        classesOut.deleteRecursively()
        classesOut.mkdirs()
        val runtime = qaDir.resolve("compile-runtime-classpath.txt").readText()
        val composePlugin = uiQaCompiler.files.first { it.name.startsWith("kotlin-compose-compiler-plugin-embeddable") }
        val parcelizePlugin = uiQaCompiler.files.first { it.name.startsWith("kotlin-parcelize-compiler-") }
        args = buildList {
            add("-no-stdlib")
            add("-jvm-target")
            add("21")
            add("-classpath")
            add(runtime)
            add("-Xplugin=$composePlugin")
            add("-Xplugin=$parcelizePlugin")
            add("-d")
            add(classesOut.absolutePath)
            uiProductionSources.forEach { add(it.absolutePath) }
            uiQaSources.forEach { add(it.absolutePath) }
        }
    }
}

val uiQaRender = tasks.register("uiQaRender", org.gradle.api.tasks.JavaExec::class) {
    dependsOn(uiQaCompile)
    val qaDir = uiQaDir.get().asFile
    mainClass = "qa.RenderListUiKt"
    workingDir(qaDir)
    jvmArgs(
        "-Djava.awt.headless=true",
        "-Dskiko.renderApi=SOFTWARE",
        "-Dqa.outDir=${qaDir.resolve("qa-out").absolutePath}",
        "-Xmx2g",
    )
    args = providers.gradleProperty("qaArgs").orElse("skeleton")
        .map { spec -> spec.split(' ').filter { it.isNotBlank() } }.get()
    // 默认骨架渲染：qa.PrintMatrix -- / :ui:uiQaRender -PqaArgs="matrix"
    doFirst {
        classpath = files(
            qaDir.resolve("classes"),
            qaDir.resolve("runtime-classpath.txt").readText().split(File.pathSeparator),
        )
    }
}

