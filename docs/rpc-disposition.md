# Hermes Webapp v0.23.2 RPC 接入明细

目录收录 277 项 RPC。当前手机通用入口可调用 123 项，表中列出其余 154 项的原生入口或上游前提。调用还取决于登录权限、所属会话、配置档案和服务端功能开关。

Windows 安装版的 legacy 注册表共 262 项，其中 226 项通过 Webapp 的 legacy fallback 命名空间筛选。接口目录将它们与会话 Authority 方法、网关心跳和已声明的插件命名空间合并。循环和装饰器工厂提取覆盖学习图、MCP Server 和工具清单的实际注册。

## 通用入口

每项请求使用登录绑定的一次性网关票据。指定会话的方法按目录中的 sessionBinding 在同一连接恢复会话。服务端校验角色和会话归属。配置改动、进程控制、技能维护和群聊写入保留逐项确认。

进程列表省略命令和输出尾部，MCP 服务器列表返回注册元数据。会话导出、宠物资源、配置档案头像、截图和群聊附件可在手机保存。群聊附件先校验长度和 SHA-256。安装技能使用既有技能 Hub 审核流程。

## 其余接口

| 接入前提 | 数量 |
| --- | ---: |
| 专用原生流程 | 12 |
| 客户端会话事件 | 15 |
| 异步模型和渲染任务 | 11 |
| 文件选择与会话迁移 | 14 |
| 跨网关身份与中继 | 14 |
| 浏览器授权和连接器策略 | 10 |
| 账户支付和首次试用 | 14 |
| 凭据和主机配置 | 13 |
| 主机执行和插件加载 | 10 |
| 桌面设备和机器人桌面控制 | 12 |
| 本机音频和唤醒设备 | 9 |
| 任务驱动与运行收据 | 9 |
| 客户端反馈和历史快照 | 11 |

### 专用原生流程

手机通过对话、审批、排队、附件或机器人桌面流程使用这些方法。这些入口附带会话、登录、代次或票据状态。

- `rpc:approval.respond`、`rpc:clarify.respond`、`rpc:display.observe`、`rpc:file.attach`、`rpc:image.attach_bytes`
- `rpc:prompt.cancel`、`rpc:prompt.steer_queued`、`rpc:prompt.submit`、`rpc:session.create`、`rpc:session.interrupt`
- `rpc:session.mutate`、`rpc:session.resume`

### 客户端会话事件

需要对应会话或房间的当前订阅、待审请求、排队收据或持久化代次。通用表单未取得这些活跃事件的操作上下文。

- `rpc:approval.pending`、`rpc:approval.received`、`rpc:clarify.lock`、`rpc:command.observe`、`rpc:connection.respond`
- `rpc:groups.approve`、`rpc:handoff.fail`、`rpc:message.react`、`rpc:prompt.replace_queued`、`rpc:prompt.resolve_unknown`
- `rpc:request.answer`、`rpc:session.control`、`rpc:session.detach`、`rpc:session.redirect`、`rpc:session.steer`

### 异步模型和渲染任务

需要持续接收任务事件或展示生成结果，包括侧路会话、资产生成、终端图像和宠物动画。上层客户端要管理异步收据、载荷和取消。

- `rpc:image.generate`、`rpc:image.render`、`rpc:llm.oneshot`、`rpc:pet.cancel`、`rpc:pet.cells`
- `rpc:pet.generate`、`rpc:pet.hatch`、`rpc:pet.info`、`rpc:preview.restart`、`rpc:prompt.background`
- `rpc:prompt.btw`

### 文件选择与会话迁移

需要按来源选择历史数据库、绑定已暂存的附件或配置档案资源，或按回滚确认恢复文件和会话。已接入的 App 聊天附件、托管文件、会话导出和检查点列表使用对应的原生路径。

- `rpc:groups.attachment.upload`、`rpc:image.attach`、`rpc:image.detach`、`rpc:pdf.attach`、`rpc:profiles.create`
- `rpc:profiles.set_asset`、`rpc:rollback.diff`、`rpc:rollback.restore`、`rpc:rollback.session_diff`、`rpc:session.foreign.import`
- `rpc:session.foreign.list`、`rpc:session.foreign.preview`、`rpc:session.prune`、`rpc:spawn_tree.load`

### 跨网关身份与中继

需要已注册的中继对等方、房间 authority 代次、设备身份或专用授权。App 在当前登录服务内执行明确的已授权房间操作。

- `rpc:a2a.forward`、`rpc:bot_relay.cancel`、`rpc:bot_relay.deliver`、`rpc:bot_relay.outbox.drain`、`rpc:bot_relay.reply`
- `rpc:bot_relay.roster.sync`、`rpc:bot_relay.settle`、`rpc:groups.demote`、`rpc:groups.peer.invite`、`rpc:groups.peer.register`
- `rpc:groups.peer.revoke`、`rpc:groups.promote`、`rpc:groups.replica_state`、`rpc:groups.replicate`

### 浏览器授权和连接器策略

这些流程绑定登录授权、连接器 owner、修订号或公共网关的回调。需要沿原流程获取并核对状态和凭据。

- `rpc:connectors.connect`、`rpc:connectors.list`、`rpc:connectors.operation.status`、`rpc:connectors.operation.wake`、`rpc:connectors.policy.set`
- `rpc:connectors.tools`、`rpc:mcp.servers.oauth.callback`、`rpc:mcp.servers.oauth.cancel`、`rpc:mcp.servers.oauth.poll`、`rpc:mcp.servers.oauth.start`

### 账户支付和首次试用

需要 Nous 账户、价格或一步验证凭证。免费试用和 onboarding 还有桌面首次使用状态、挑战或提示完成事件。可用的状态和无费用预览走查询接口。

- `rpc:billing.auto_reload`、`rpc:billing.charge`、`rpc:billing.keep_going_on_credits`、`rpc:billing.step_up`、`rpc:free_tier.ack_notice`
- `rpc:free_tier.challenge_result`、`rpc:free_tier.claim_nudge`、`rpc:free_tier.provision`、`rpc:onboarding.set_run`、`rpc:onboarding.skip`
- `rpc:onboarding.start`、`rpc:subscription.change`、`rpc:subscription.resume`、`rpc:subscription.upgrade`

### 凭据和主机配置

涉及密钥、凭据保险库本机来源、配置写入的任意程序或授权设置。移动端已有的密钥录入、配置读取和已配置服务管理使用显式字段约束。

- `rpc:config.show`、`rpc:mcp.servers.add`、`rpc:mcp.servers.set_api_key`、`rpc:reload.env`、`rpc:tools.configure`
- `rpc:vault.add`、`rpc:vault.list`、`rpc:vault.lock`、`rpc:vault.preview.fill`、`rpc:vault.remove`
- `rpc:vault.source.set`、`rpc:vault.sources`、`rpc:vault.unlock`

### 主机执行和插件加载

需要本机命令分派、任意 argv 或目标地址、进程范围的委派开关、插件检查和执行同意、或本机明确选择并分享诊断材料。

- `rpc:browser.manage`、`rpc:cli.exec`、`rpc:command.dispatch`、`rpc:delegation.pause`、`rpc:diagnostics.share_nous`
- `rpc:plugin.{plugin_id}.{name}`、`rpc:plugins.manage`、`rpc:session.automation`、`rpc:shell.exec`、`rpc:slash.exec`

### 桌面设备和机器人桌面控制

需要桌面剪贴板、前端会话能力、终端栅格，或机器人桌面的本机安装、进程控制权限和服务端租约。手机机器人桌面提供已运行画面的只读会话。

- `rpc:client.capabilities`、`rpc:clipboard.paste`、`rpc:display.install`、`rpc:display.lease.acquire`、`rpc:display.lease.release`
- `rpc:display.start`、`rpc:display.stop`、`rpc:display.switchSandboxImage`、`rpc:input.detect_drop`、`rpc:machine.facts`
- `rpc:paste.collapse`、`rpc:terminal.resize`

### 本机音频和唤醒设备

这些方法管理主机录音、唤醒流和语音会话表面。手机语音采用 Webapp 的专用音频路由。

- `rpc:voice.record`、`rpc:voice.toggle`、`rpc:voice.tts`、`rpc:wake.feed`、`rpc:wake.pause`
- `rpc:wake.resume`、`rpc:wake.start`、`rpc:wake.status`、`rpc:wake.stop`

### 任务驱动与运行收据

看板执行需要服务端分配的 claim。定时任务 RPC 绑定已提交的任务运行实例，移动端的手动定时任务管理使用既有业务 REST 入口。

- `rpc:cron.cancel`、`rpc:cron.manage`、`rpc:cron.recover`、`rpc:cron.status`、`rpc:cron.submit`
- `rpc:kanban.run`、`rpc:worker.adopt`、`rpc:worker.persist`、`rpc:worker.register`

### 客户端反馈和历史快照

需要前端产生的真实反馈或诊断事件，或指定前端的子代理输出和任务快照。当前会话的进程和子代理信息已有对应的只读接口。

- `rpc:shared_metrics.desktop_daily`、`rpc:shared_metrics.desktop_dislike`、`rpc:shared_metrics.desktop_feature_use`、`rpc:shared_metrics.desktop_friction`、`rpc:shared_metrics.desktop_onboarding`
- `rpc:shared_metrics.slash_command`、`rpc:shared_metrics.startup_latency`、`rpc:shared_metrics.update_run`、`rpc:spawn_tree.list`、`rpc:spawn_tree.save`
- `rpc:subagent.tail`
