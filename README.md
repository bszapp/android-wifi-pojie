# WiFi 工具箱

## USB monitor / Wireshark

全新 Windows 电脑的软件安装、插件安装、连接手机与捕获步骤见
[tools/wireshark/README.md](tools/wireshark/README.md)。
电脑使用内置 WinUSB 驱动，通过 extcap 接收原始无线帧，并可调频或提交原始帧发送请求。
应用内电脑控制持续运行到手动停止；独立调试脚本使用 20 秒自动还原。

## 原生 hashcat

构建裁剪为 WPA/WPA2 文件字典用途：
保留 22000（密码推导 PMK，PMKID/EAPOL）和 22001（已有 PMK，PMKID/EAPOL）两种模式。
普通 UTF-8 TXT 字典无需编码转换库，构建不再捆绑 libiconv、liblzma 和 Markov 数据。

`libhashcat.so` 是同一份 Android ARM64 原生共享库，具有两种入口：

- App 使用 `System.loadLibrary("hashcat")` 后通过 JNI 调用；普通 C 调用方可使用
  `hashcat/hashcat_android.h` 中的 `wlantool_hashcat_run`、`wlantool_hashcat_quit` 和版本接口。
- 直接执行这份 `.so` 时，通过 ARM64 ELF 入口与系统 `/system/bin/linker64` 启动原始命令行流程。
  使用标准 Android/bionic 环境，不经过 rootfs、proot 或 chroot。

WPA/WPA2 模块、文件字典 feed、对应 OpenCL 源码及其传递依赖、通用调优配置
在构建时打包进 `.so`。插件剥离非运行必需符号，资源以 gzip 最高级别压缩，原生链接回收无用代码段。
其他哈希模块、bridge/feed、内置规则库、掩码库、字符集和无关内核不进入构建产物。
未指定算法时双入口默认使用 22000，启动资源完整性检查也使用 WPA 模块和内核。
首次运行解包到调用方的专属运行目录，随后复用内容哈希对应的资源目录与内核缓存。
直接执行时，即便只复制这一份 `.so`，也不需要另外复制 hashcat 的模块或 OpenCL 资源。
该目录必须允许当前 UID 读写和加载原生模块；文件执行和驱动访问仍受设备的权限、SELinux
及 Android linker namespace 限制。

### App 原生调用

```kotlin
// 在工作线程执行。listener 回调也在原生工作线程，需要自行转发给 UI。
val exitCode = NativeHashcat.run(
    context = context,
    arguments = listOf("--backend-info"),
    listener = NativeHashcat.EventListener { eventId, bytes ->
        if (eventId in NativeHashcat.EVENT_LOG_ERROR..NativeHashcat.EVENT_LOG_ADVICE) {
            val message = bytes.toString(Charsets.UTF_8)
            // 接入调用方已有的日志存储。
        }
    },
)
```

默认使用 `context.noBackupFilesDir/hashcat`；独立服务可以调用接受 `File` 的重载，
传入自己拥有的持久目录。输入、字典、输出文件使用绝对路径。返回 hashcat 原始退出码，
`quit()` 请求停止已进入执行阶段的当前会话。此入口不新增 UI 或 hashcat 任务类型。
JNI 运行不启动终端按键读取线程，也不改向宿主进程的标准输入/输出文件描述符。
多个原生调用串行执行，以保护上游 getopt 等进程级状态。

### 直接执行

```sh
# 在 shell 可访问、可执行的持久目录中放置 libhashcat.so。
chmod 755 /你的目录/libhashcat.so
HASHCAT_HOME=/你的目录/hashcat-data /你的目录/libhashcat.so --version
HASHCAT_HOME=/你的目录/hashcat-data /你的目录/libhashcat.so --backend-info
```

未设置 `HASHCAT_HOME` 时，使用 `.so` 所在目录下的 `hashcat-data`。
复制进 `com.android.shell` 的目录时需使用 shell 有权限的路径；程序不会修改该包权限，
也不假定 App UID 能直接访问 shell UID 的私有目录。

### GPU 与性能

保留上游 OpenCL 算法内核、设备识别、工作量自动调优及 ARM64/NEON 优化。
所有厂商判断分支、OpenCL 设备识别和调优表保留，构建不固定 GPU 型号、驱动路径或工作量。
计算循环不经过 JNI 回调，每个候选密码不会跨 Java/native 边界；JNI 只转发原有事件与日志。
资源解包只影响首次准备，不参与计算循环。

自动尝试加载可访问的 `libOpenCL.so` 和 Android 厂商常见驱动位置，并检查 OpenCL 入口。
可通过 App 的 `openClLibrary` 参数或命令行 `HASHCAT_OPENCL_LIBRARY` 明确指定驱动。
指定失败时报告失败，不悄悄换成其他驱动。使用 `--backend-info` 查看真实可用的设备；
设备选项和工作量选项继续采用原始 hashcat 参数，不默认强制 GPU 或满负载运行。
Manifest 将 `libOpenCL.so` 与 `libGLES_mali.so` 声明为可选原生依赖，以便 Android 12
以上允许访问厂商公开的对应库；缺少这些库不会阻止安装。该声明不会开放厂商私有库。
参见 [Android 原生库声明](https://developer.android.com/guide/topics/manifest/uses-native-library-element)。
普通加载失败时，适配层可选地查找系统已公开的 `sphal/vendor` namespace，使用
`android_dlopen_ext` 加载同一驱动，避免独立执行位置导致的驱动依赖解析差异。
其中 `android_get_exported_namespace` 是内部接口，动态查找并允许缺失；不创建 namespace，
不更改系统配置。参照 [AOSP 厂商库加载实现](https://android.googlesource.com/platform/system/core/+/refs/heads/main/libvndksupport/linker.cpp)。

手机支持高性能游戏不保证开放通用 OpenCL 计算。厂商驱动可能不开放、被 namespace/SELinux
限制或缺少所需能力；没有可用计算后端时报告 hashcat 原始错误。此适配不提供伪造后端，
不捆绑厂商闭源 GPU 驱动，不另写 Vulkan/GLES 替代计算内核，不承诺未经实测的性能。
此精简版本保留文件字典计算，移除 Brain 和其他候选生成插件，内核自检和自动调优继续运行。
gzip 使用 Android 系统 zlib。额外编码转换、XZ/zstd 字典等可选库不捆绑，
需要这些库的输入方式遵循上游缺失依赖提示；普通 UTF-8 TXT 字典不受影响。
官方 Android 文档记录了部分模式（包括 22000）在其测试设备上的内存不足问题；
该记录不代表本机结果。保留跨设备的上游实现，仍需设备具备可访问且满足要求的计算驱动。

### 构建

APK 的原生打包任务从项目内源码编译 `libhashcat.so`、两个 WPA/WPA2 模块和文件字典插件，
使用 Android NDK 的 bionic 工具链和静态 C++ 运行库。源码目录不内嵌预编译 ELF、静态库
或可执行程序；构建生成的资源包也只收集本次 Android ARM64 编译产物和官方数据文件。
现有 Gradle debug/release 原生打包均包含 `libterminal.so` 和 `libhashcat.so`。

## 一键打包

```powershell
[Console]::OutputEncoding=[System.Text.UTF8Encoding]::new(); $OutputEncoding=[System.Text.UTF8Encoding]::new(); Add-Type -AssemblyName System.IO.Compression.FileSystem; $zipName="$(Get-Date -Format 'yyyyMMddHHmmss').zip"; $zip=(Join-Path $PWD $zipName); Remove-Item $zip -ErrorAction SilentlyContinue; $archive=[System.IO.Compression.ZipFile]::Open($zip,'Create'); try { git -c core.quotepath=false ls-files --cached --others --exclude-standard | ? { $_ -and ($_ -notmatch '^[^\\/]+\.zip$') -and (Test-Path -LiteralPath $_ -PathType Leaf) } | % { [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive,(Resolve-Path -LiteralPath $_).Path,($_ -replace '\\','/')) > $null } } finally { $archive.Dispose() }
```
