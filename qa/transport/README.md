# Hermes Pocket 协议测试

使用 JDK 17 或更高版本。在 `apps/hermes-pocket` 目录运行：

```powershell
.\android\gradlew.bat -p qa/transport test
```

macOS 或 Linux 使用 `./android/gradlew -p qa/transport test`。首次运行会下载 Maven 测试依赖。报告位于 `qa/transport/build/reports/tests/test/index.html`。

测试在系统分配的本机端口启动模拟 Webapp，使用临时凭据和安装包内置的接口目录，直接编译并执行 App 的聊天和工作台传输代码。运行环境只需要 JDK。测试覆盖：

- 授权失败、按请求地址取凭据和 HTTP 直连
- 复用连接在返回响应头前断开时，GET 只通过新连接重试一次。网关票据 POST 不重试
- 会话归属、空会话过滤、压缩前后去重、刷新读到电脑端更新
- 一次性网关票据、同源校验、同会话发送和查询结果
- 会话不存在时拒绝投递，以及按固定请求编号重试新建会话
- 工作台 REST 与独立 JSON-RPC 真实发包，包括 JSON、附件上传、二进制下载与服务端拒绝
- 同源凭据、方法与参数白名单、路径校验、变更确认、重复提交和跳转拦截

真实 Nous 登录、模型回复及跨设备交互由真机联调验证。
