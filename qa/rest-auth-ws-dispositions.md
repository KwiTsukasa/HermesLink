# HermesLink REST、WebSocket 和鉴权接口余项核对

核对基准为已安装 Hermes Webapp 0.23.2，Windows 商店包 `NousResearchInc.HermesAgent_2026.7675.1816.0_x64__sznbd6dv1j3v4`。目录记录其源码文件、行号、参数及鉴权方式。按应用 `WorkspaceClient.unsupportedReason` 筛出通用传输以外的 REST、WebSocket 和鉴权操作，再按已接入的专用入口、官方工作流程、服务端权限或资源形态核对去向。

## 文件和档案的专用界面

- `HEAD /api/files/stream` 检查资源大小和媒体类型。`GET /api/files/stream` 使用原生同源 Authorization、HTTP Range 及可用的 If-Range 校验值。受管文件页和会话产物页共用播放器，会话产物携带所属档案和会话 ID。核对 `hermes_cli/web_routers/files.py` 中 `stream_managed_file` 和 `_fs_owned_session`。
- `POST /api/profiles/{name}/export` 让服务器生成脱敏包，接着从 `GET /api/fs/download` 读取可确认的生成路径，经系统保存器保存。`POST /api/profiles/import` 读取系统选择器中的 tar.gz，通过 `GET /api/files`、`POST /api/files/upload-stream` 放到受管目录的一次性文件，再交给上游导入。在确定收到导入应答后使用 `DELETE /api/files` 清理本次文件。手机检查文件成员和常见凭据文件名。核对 `hermes_cli/web_routers/profiles.py` 中的导入导出处理器与 `hermes_cli/profiles.py` 中的路径、脱敏及解包规则。
- 以上专用界面使用的 REST 方法已在目录内。每个方法只计一个接口。原生手机实现、浏览器跳转、通用传输和真机验收可由各自证据辨认。

## SSE

已安装目录内的实时聊天、音频、屏幕、终端和频道事件使用 WebSocket。服务器 `hermes_cli/local_runtime/load_progress.py` 消费模型后端 SSE，`hermes_cli/proxy/sse_done.py` 处理模型代理 SSE 结束标记。它们属于其他监听器或后端适配器。Webapp 的 `/api/events` 是 WebSocket。

## 通用传输以外的操作

### 手机专用传输

现有手机入口使用原生 PKCE、同源目的票据、音频帧、会话权威网关、主机终端、Bot 屏幕、看板事件或服务商会话。票据和服务端授权会话 ID 由原生层保管。

- `auth:GET:/api/auth/me`
- `auth:GET:/api/auth/providers`
- `auth:GET:/auth/native/authorize`
- `auth:POST:/api/auth/ws-ticket`
- `auth:POST:/auth/native/refresh`
- `auth:POST:/auth/native/token`
- `rest:DELETE:/api/providers/oauth/sessions/{session_id}`
- `rest:GET:/api/providers/oauth/{provider_id}/poll/{session_id}`
- `rest:POST:/api/profiles/import`
- `rest:POST:/api/profiles/{name}/export`
- `rest:POST:/api/providers/oauth/{provider_id}/start`
- `rest:POST:/api/webapp/socket-ticket`
- `websocket:CONNECT:/api/audio/speak-stream`
- `websocket:CONNECT:/api/audio/transcribe-stream`
- `websocket:CONNECT:/api/display/ws`
- `websocket:CONNECT:/api/host-terminal`
- `websocket:CONNECT:/api/plugins/kanban/events`
- `websocket:CONNECT:/api/ws`

### 浏览器身份会话

官方登录页、OAuth 回调、密码表单及退出使用浏览器 PKCE 状态和 Cookie。手机的本地退出清除原生登录。官方退出路由按 Cookie 撤销会话。

- `auth:GET:/auth/callback`
- `auth:GET:/auth/login`
- `auth:GET:/login`
- `auth:POST:/auth/logout`
- `auth:POST:/auth/password-login`

### 浏览器文件票据

文件票据可在其有效期内读取固定路径和所属会话的资源。官方 Webapp 自行持有票据，原生媒体播放器在同源 HTTPS 或已有私网连接上发送原生 Authorization。

- `rest:POST:/api/files/ticket`

### 浏览器 MCP 授权

官方连接器页给已配置的远程 MCP 服务启动 OAuth。第三方授权 URL 由已配置的 MCP 授权服务器动态发现，回调由 Webapp 验证 state 和 issuer，再用 flow_id 查询或取消。手机入口打开同源连接器页。

- `rest:DELETE:/api/mcp/oauth/flows/{flow_id}`
- `rest:GET:/api/mcp/oauth/callback/{server_name:path}`
- `rest:GET:/api/mcp/oauth/flows/{flow_id}`

### 主机 Memory OAuth

插件的 OAuth 启动函数在 Hermes 主机启动浏览器与回环监听器，状态查询对应这个主机流程。已支持的 Memory 配置和状态使用原有受管接口。该登录需在主机完成。

- `rest:GET:/api/memory/providers/{provider}/oauth/status`
- `rest:POST:/api/memory/providers/{provider}/oauth/start`

### 浏览器网关管理及中继

官方 Webapp 网关页维护保存的网关、OAuth、SSH 和回调关联。中继方法下的目标路径、连接 ID、请求语义由官方浏览器中继保持，通用手机表单只使用已验证的固定入口。

- `rest:DELETE:/api/gateway-connections/{connection_id}`
- `rest:DELETE:/api/gateway-connections/{connection_id}/oauth`
- `rest:DELETE:/api/gateway-connections/{connection_id}/ssh`
- `rest:DELETE:/api/relay/{connection_id}/{path:path}`
- `rest:GET:/api/gateway-connections/{connection_id}/oauth`
- `rest:GET:/api/gateway-connections/{connection_id}/ssh`
- `rest:GET:/api/relay/{connection_id}/{path:path}`
- `rest:GET:/api/ssh/ownership`
- `rest:GET:/webapp/gateway-connections/oauth/callback`
- `rest:HEAD:/api/relay/{connection_id}/{path:path}`
- `rest:OPTIONS:/api/relay/{connection_id}/{path:path}`
- `rest:PATCH:/api/relay/{connection_id}/{path:path}`
- `rest:POST:/api/gateway-connections`
- `rest:POST:/api/gateway-connections/last-used`
- `rest:POST:/api/gateway-connections/launch-mode`
- `rest:POST:/api/gateway-connections/{connection_id}/oauth/complete`
- `rest:POST:/api/gateway-connections/{connection_id}/oauth/start`
- `rest:POST:/api/gateway-connections/{connection_id}/primary`
- `rest:POST:/api/gateway-connections/{connection_id}/ssh`
- `rest:POST:/api/gateway-connections/{connection_id}/test`
- `rest:POST:/api/relay/{connection_id}/{path:path}`
- `rest:PUT:/api/relay/{connection_id}/{path:path}`
- `websocket:CONNECT:/api/relay/{connection_id}/{path:path}`

### 本机 Desktop 授权和私有启动

Web Access 管理使用本机 native HTTP grant。私有启动页和窗口令牌使用主机或服务自身的启动授权。

- `rest:GET:/api/webapp-access/status`
- `rest:GET:/webapp/launch`
- `rest:GET:/webapp/window`
- `rest:POST:/api/webapp-access/restart`
- `rest:POST:/api/webapp-access/start`
- `rest:POST:/api/webapp-access/stop`
- `rest:POST:/api/webapp/window-ticket`
- `rest:POST:/webapp/window-session`

### 旧终端与事件协议

这是旧版 dashboard 的终端与频道 WebSocket，包含 PTY、Console 命令、事件发布和订阅。现有聊天使用权威网关事件，终端使用 Webapp 主机终端。

- `websocket:CONNECT:/api/console`
- `websocket:CONNECT:/api/events`
- `websocket:CONNECT:/api/pty`
- `websocket:CONNECT:/api/pub`

### 代理间访问

代理间路由依赖预先发放的 peer pass 和对应目标。该凭据不是手机 Webapp 登录，并且路由本身会转发任意代理路径。

- `rest:DELETE:/api/peer/{path:path}`
- `rest:GET:/api/peer/{path:path}`
- `rest:PATCH:/api/peer/{path:path}`
- `rest:POST:/api/peer/{path:path}`

### 凭据与完整配置

涉及 API 密钥池、环境变量明文或完整配置。手机界面只投影已知状态字段，用户在官方管理面操作服务商凭据。

- `rest:DELETE:/api/credentials/pool/{provider}/{index}`
- `rest:GET:/api/config`
- `rest:GET:/api/config/raw`
- `rest:GET:/api/credentials/pool`
- `rest:POST:/api/credentials/pool`
- `rest:POST:/api/env/reveal`

### 完整备份和主机迁移

运营备份和接管涉及主机归档、分块续传、身份或配置恢复以及后台导入，使用主机操作流程。手机档案文件入口单独使用 profiles 的限定导入导出。

- `rest:GET:/api/ops/backup/chunk`
- `rest:GET:/api/ops/backup/download`
- `rest:POST:/api/ops/transfer/chunk`
- `rest:POST:/api/ops/transfer/restore`

### 外部媒体代理

接收远程图片 URL 并代为取回数据。原生资源入口读取已选的 Hermes 受管路径，HTTPS 外部链接由系统浏览器访问。

- `rest:GET:/api/media/proxy`

### 服务商 OAuth 边界

已支持服务商设备码的开始、查询和取消。Nous handoff 是免费层迁移状态，按上游主页管理。通用 submit 处理器明确始终返回 HTTP 400。

- `rest:GET:/api/providers/oauth/nous/handoff`
- `rest:POST:/api/providers/oauth/{provider_id}/submit`

### 插件运行和动态扩展

这些路径依赖具体已安装插件、动态参数、静态前端、第三方插件激活或较深的运行日志。现有固定看板和成就功能依照各自已知操作开放。

- `rest:DELETE:/api/plugins/{name}/{path:path}`
- `rest:GET:/api/plugins/kanban/runs/{run_id}/inspect`
- `rest:GET:/api/plugins/kanban/tasks/{task_id}/log`
- `rest:GET:/api/plugins/{name}/{path:path}`
- `rest:GET:/dashboard-plugins/@{profile}/{plugin_name}/{file_path:path}`
- `rest:GET:/dashboard-plugins/{plugin_name}/{file_path:path}`
- `rest:HEAD:/api/plugins/{name}/{path:path}`
- `rest:OPTIONS:/api/plugins/{name}/{path:path}`
- `rest:PATCH:/api/plugins/{name}/{path:path}`
- `rest:POST:/api/dashboard/agent-plugins/activate`
- `rest:POST:/api/dashboard/agent-plugins/{name:path}/update`
- `rest:POST:/api/plugins/kanban/boards/import`
- `rest:POST:/api/plugins/kanban/boards/{slug}/export`
- `rest:POST:/api/plugins/kanban/dispatch`
- `rest:POST:/api/plugins/kanban/profiles/{profile_name}/describe-auto`
- `rest:POST:/api/plugins/kanban/runs/{run_id}/terminate`
- `rest:POST:/api/plugins/kanban/tasks/{task_id}/decompose`
- `rest:POST:/api/plugins/kanban/tasks/{task_id}/specify`
- `rest:POST:/api/plugins/{name}/{path:path}`
- `rest:PUT:/api/plugins/kanban/orchestration`
- `rest:PUT:/api/plugins/{name}/{path:path}`

### 有独立身份或生命周期的动作

Cron fire 是带签名 JWT 的触发入口。退役是后端生命周期交换，已挂载网关时后端会拒绝。open-terminal 请求打开主机终端，手机已有用户发起的终端连接。

- `rest:POST:/api/cron/fire`
- `rest:POST:/api/health/retirement`
- `rest:POST:/api/profiles/{name}/open-terminal`

覆盖此处清单中的 105 个目录 ID。访问权限、服务商配置和插件条件在运行时由 Hermes 判定。
