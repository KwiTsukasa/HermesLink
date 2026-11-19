# Hermes Webapp v0.23.2 API 目录

HermesLink 的目录来自 Windows 商店版 Hermes Agent 安装包。版本为 `0.23.2`，商店包为 `NousResearchInc.HermesAgent_2026.7675.1816.0_x64__sznbd6dv1j3v4`，包内源码提交为 `ae2a81ccc00e9b7c06301523e5bb09608397149c`。

可由 App 读取的目录是 `android/app/src/main/assets/webapp-contract.json`。它收录 **701** 项。

| 接口 | 数量 | 核对依据 |
| --- | ---: | --- |
| REST | 402 | 已挂载的 APIRouter、Webapp surface policy、插件回退注册与两项随包附带的插件 |
| 登录与鉴权 | 11 | `hermes_cli/dashboard_auth/routes.py` |
| WebSocket | 11 | 已挂载的 WebSocket 端点及其握手条件 |
| 网关 RPC | 277 | Authority handler 表、群聊路由、通过 Webapp fallback 筛选的 legacy handler、心跳与动态插件命名空间 |

HTTP 与 WebSocket 共 424 项。其中 370 项属于共享 Webapp 路由及动态插件回退注册。剩余 54 项是随包附带的成就、看板插件的条件路由，插件须通过上游加载条件才会挂载。未知插件的路由以实际注册的回退路径和 RPC 命名空间呈现，其具体接口取决于服务端启用的插件。

## 复核

在项目根目录用 Python 3.10 或更高版本运行以下命令。将变量设为安装包内的 `app/resources/agent-payload/hermes-agent` 目录。

```powershell
$hermesWebappSource = 'C:/Program Files/WindowsApps/NousResearchInc.HermesAgent_2026.7675.1816.0_x64__sznbd6dv1j3v4/app/resources/agent-payload/hermes-agent'
python tools/build_webapp_contract.py --source $hermesWebappSource --check
```

去掉 `--check` 可按同一个已核对的安装包重建目录。脚本检查安装包的提交标记和接口 ID 唯一性，输出源码哈希与每项的文件及行号。`--check` 逐字节核对生成结果。提取过程只读随包附带的 Python 源码和 manifest，不导入 Hermes，不读取用户目录、账号凭据或运行时数据，也不向网关发送请求。

## 目录字段

- `id` 为稳定标识。HTTP 接口使用 `kind:METHOD:/path`，RPC 使用 `rpc:method`。同路径上的不同 HTTP 方法各有独立条目。
- `params` 标明路径、查询、正文和请求头参数，以及内容类型。每个字段包含名称、类型、默认值、是否必填和可核对的格式约束。参数可选性来自上游签名或处理器明示检查。手写解析器的部分正文用 `$body` 表示，字段语义见源处理器。
- `response.format` 标明响应为 JSON、HTML、重定向、二进制、事件流、WebSocket 或 JSON-RPC。网关 RPC 的响应附有源码中的模型名称。部分响应由下游插件或代理决定。
- `constraints` 标明调用方式和鉴权依据。带身份的 REST 请求使用 Webapp 会话。支持独立执行的 JSON-RPC 仍需一次性网关票据和 WebSocket。带会话附接、流式响应、屏幕、终端或浏览器授权状态的操作保留专用流程。本机 Web Access 使用上游的本机 native grant。
- `source` 定位入口或注册。RPC 的 `contractSource` 定位独立的参数模型。通用接口面板按专用传输、会话权限和敏感响应约束选择何时允许交互。

生成器读取安装版中的静态注册，包括由循环和装饰器工厂声明的 RPC。隔离运行核对了实际注册的 262 项 legacy 方法，其中 226 项通过 Webapp 的 legacy fallback 筛选。会话绑定由目录中的 `sessionBinding` 标记。文件返回的 RPC 使用 `wireFormat=jsonrpc` 保留实际线上格式，由 App 解码后交给系统保存。 [RPC 接入明细](rpc-disposition.md)

目录以 Webapp listener 为范围。独立 OpenAI 兼容监听器、FastAPI 自带文档页、SPA 静态文件和客户端路由不作为 Webapp API 计数。声明的参数模型不能代替运行时权限、当前配置档案、插件状态、数据约束或外部服务可用性。目录收录是源码覆盖证据，真机交互按独立验收记录统计。
