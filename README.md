# HermesLink

HermesLink 是连接个人 Hermes Agent Webapp 的 Android 客户端。默认界面加载服务器提供的官方 HTML、JavaScript 和 CSS，并为窄屏调整排版。项目包含 Android 宿主、React Native / TypeScript 工作区和原生通信实现。

## 项目结构

| 路径 | 内容 |
| --- | --- |
| `android/` | Kotlin / Java 宿主、WebView、原生认证与通信、应用资源和 Gradle Wrapper |
| `src/` | React Native 页面、原生桥接类型、主题和国际化 |
| `qa/transport/` | JVM 协议测试及本地模拟服务 |
| `tools/` | Webapp 接口目录、语言、主题与离线终端资源的生成工具 |
| `docs/` | [API 合约](docs/api-contract.md)、[实时连接](docs/live-transport.md)、[外观](docs/appearance.md)和[国际化](docs/locales.md) |

## 构建

当前版本为 **0.6.1**。使用 Node.js 22.22.0、npm 10.9.4、JDK 17、Android SDK 37.1、NDK 27.1.12297006 和 CMake 3.22.1。Android 最低版本为 9（API 28）。Windows 在项目根目录运行以下命令。

```powershell
npm ci
npm run typecheck
npm run lint
npm run format:check
./android/gradlew.bat -p qa/transport test
./android/gradlew.bat -p android assembleDebug
```

macOS 或 Linux 使用 `./android/gradlew`。调试 APK 输出到 `android/app/build/outputs/apk/debug/app-debug.apk`。连接真实服务时使用自己的 Hermes Webapp 地址和账号。

## 来源与声明

HermesLink 为独立开发的客户端。此版本针对 Nous Research 的 Hermes Webapp v0.23.2。Hermes 品牌图形取自 Windows 商店版 Hermes Agent。离线语言数据、主题和第三方组件的声明随对应资源保留，终端构建时会将 xterm.js 的许可证一起打包。
