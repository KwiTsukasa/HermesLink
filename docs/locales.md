# HermesLink 官方语言资源

HermesLink 从 Windows 商店版 Hermes Agent v0.23.2 提取安装包自带的语言文字。目录包含 17 种语言和 10,566 个消息键。Webapp 前端实际包含九种语言的桌面字典，其余语言使用对应的 Core 和 TUI 字典。每个键优先使用同语言的 Webapp 桌面文字，随后使用 TUI 和 Core 已提供的文字。当前语言缺词时回退到官方英文。手机新增的功能文字由应用单独维护。

- React Native 使用 `src/shared/i18n/official.json` 和 `locales.json`。后者提供语言 ID、自称、书写方向和已打包的字典来源。
- Android 原生界面从同一套数据生成每种语言的资产。设置存于应用私有偏好，默认匹配设备的首选受支持语言。用户可固定语言，或恢复跟随系统。服务器的 `display.language` 继续由服务器自己的配置控制。
- 工作区接口目录使用可核对的官方分组和条目名称。缺少官方名称的条目显示 RPC 方法名或 HTTP 方法与路径。接口标识和权限约束保持原来的值。

## 来源与复核

来源是 Microsoft Store 包 `NousResearchInc.HermesAgent_2026.7675.1816.0_x64__sznbd6dv1j3v4`，源码提交 `ae2a81ccc00e9b7c06301523e5bb09608397149c`。生成器按前端键清单读取实际发布的 Webapp 字典，静态提取直接字符串及只有直接参数插值的模板，保留上游的命名或位置参数。动态计算的模板由对应前端自行处理。生成过程不执行上游 JavaScript，也不读取用户语言覆盖层。逐文件 SHA-256 和提取数保存在 `src/shared/i18n/provenance.json`。

项目根目录安装锁定的 npm 依赖后，可用以下命令重现。去掉 `--check` 重新生成。

```powershell
$hermesSource = 'C:/Program Files/WindowsApps/NousResearchInc.HermesAgent_2026.7675.1816.0_x64__sznbd6dv1j3v4/app/resources/agent-payload/hermes-agent'
node tools/build_official_locales.cjs --source $hermesSource --check
```

官方文本按 Nous Research 的 MIT 许可证分发，原许可随 Android 资源中的 `hermes-i18n/LICENSE.txt` 一起打包。
