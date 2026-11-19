# HermesLink 官方外观

App 使用 Hermes Webapp v0.23.2 自带的 12 套主题及明亮、暗色、跟随系统模式。React Native 和 Android 原生界面使用同一套配色。已同步到手机的自定义主题只读取能直接渲染的颜色字段，额外样式和远程字体由官方 Webapp 加载。

## 同步范围

App 从当前登录的 Hermes Webapp 读取并更新 `/api/ui-preferences`。服务端提供主机范围的共享文档，位于主机的根 Hermes 目录，供官方 Desktop 与 Webapp 共同使用。手机按当前服务器和登录代次隔离本地缓存及待同步操作。

主机默认值使用官方主题与模式键。选择命名配置档案时，App 读取最新的官方档案映射，改动其中对应的条目并提交受影响的键。服务器保留同一文档的其它键。手机断网时使用当前登录的缓存，恢复连接后继续同步。退出或切换登录会取消旧连接并切换缓存范围。档案映射使用官方接口的读取、合并和写入语义，服务端目前提供顶层键合并。

## 配色来源与核对

配色从 Microsoft Store v0.23.2 的实际 Webapp 主题注册表提取。经典主题使用发布包的皮肤参数和 Webapp 的颜色计算。缺少亮色表的主题沿用官方亮色推导公式。颜色或配色规则来自发布包，资产保留文件 SHA-256。生成器静态解析数据及有限的颜色函数调用。

```powershell
$hermesSource = 'C:/Program Files/WindowsApps/NousResearchInc.HermesAgent_2026.7675.1816.0_x64__sznbd6dv1j3v4/app/resources/agent-payload/hermes-agent'
node tools/build_official_themes.cjs --source $hermesSource --check
```

隔离运行的官方 Webapp 已验证登录保护、读取、更新命名档案、保留其它档案与桌面偏好、以及同一份主机文件的写入。JVM 测试验证移动端请求和有范围的颜色读取。
