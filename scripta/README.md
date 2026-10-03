# scripta 编辑器模块

本模块内置 [scripta](https://github.com/YuKongA/scripta) 编辑器源码，遵循 Apache License 2.0，许可证见 [LICENSE](LICENSE)。

源码来自本机 scripta 项目的 `editor/src`，原样保留公共、Android、桌面端实现及已有测试。未引入示例应用、Git 信息或构建产物。

构建配置接入 WifiToolbox 的版本目录，保留原编辑器的 Kotlin Multiplatform 目标及依赖版本。Android 运行时类目录的完整重建规则由 WifiToolbox 根构建脚本统一提供。

界面模块直接依赖 `project(":scripta")`，不需要项目目录外的 scripta checkout 或 Maven 发布产物。
