#!/usr/bin/env python3
"""Extract the Store Webapp's registered interfaces without running Hermes."""

import argparse
import ast
from collections import Counter
from functools import cache
import hashlib
import json
from pathlib import Path
import re


EXPECTED_REF = "ae2a81ccc00e9b7c06301523e5bb09608397149c"
PACKAGE_ID = "NousResearchInc.HermesAgent_2026.7675.1816.0_x64__sznbd6dv1j3v4"
OUTPUT = Path(__file__).resolve().parents[1] / "android/app/src/main/assets/webapp-contract.json"
HTTP_METHODS = {"get", "post", "put", "patch", "delete", "head", "options", "trace"}
FUNCTIONS = ast.FunctionDef, ast.AsyncFunctionDef

GROUPS = {
    "actions": "服务操作", "analytics": "使用统计", "audio": "语音",
    "chat_workspaces": "会话工作区", "chat_ws": "网关与聊天",
    "config_env": "配置与模型提供方", "cron": "定时任务", "dashboard_ui": "主题与外观",
    "delegation": "委派", "desktop_plugins": "桌面插件", "display": "屏幕",
    "files": "文件", "gateway_connections": "网关连接", "gateway_connections_reach": "网关连接",
    "git": "Git 与工作树", "local_models": "本地模型", "mcp": "MCP",
    "memory_providers": "记忆服务", "memory_oauth": "记忆服务", "messaging": "消息平台",
    "messaging_relay": "消息中继", "models": "模型", "oauth": "提供方登录",
    "ops": "运营控制台", "peer": "代理间访问", "profiles": "配置档案", "routes": "身份与登录",
    "sessions": "会话与历史", "shared_metrics": "共享指标", "skills": "技能", "status": "服务状态",
    "tools": "工具权限", "transfer": "导入与导出", "ui_preferences": "界面偏好",
    "uploads": "文件", "webapp": "Webapp 连接", "webapp_access": "Webapp 网络接入",
    "webapp_relay_proxy": "网关中继", "web_server_dashboard": "扩展插件",
}
RPC_GROUPS = {
    "approval": "审批", "billing": "账单", "browser": "浏览器", "clarify": "澄清问题",
    "complete": "输入补全", "config": "配置", "connection": "连接器", "connectors": "连接器",
    "cron": "定时任务", "delegation": "委派", "display": "屏幕", "gateway": "网关",
    "groups": "群聊协作", "handoff": "消息交接", "machine": "设备", "mcp": "MCP",
    "message": "消息", "model": "模型", "plugins": "插件", "process": "后台任务",
    "profiles": "配置档案", "projects": "项目", "prompt": "消息与队列", "runtime": "网关",
    "session": "会话与历史", "setup": "初始化", "skills": "技能", "subagent": "子代理",
    "tools": "工具", "vault": "知识库", "voice": "语音", "worker": "计算进程",
}
STANDALONE_RPCS = {
    "gateway.ping", "ping", "runtime.describe", "gateway.capabilities", "profiles.list",
    "session.list", "session.active_list", "session.info", "session.stats", "session.transcript", "prompt.receipt",
    "plugins.list", "projects.list", "projects.get", "projects.tree", "model.options",
    "groups.capabilities", "groups.list", "groups.state", "groups.log",
    "config.get", "mcp.catalog", "profiles.describe", "projects.for_cwd", "projects.project_sessions",
    "session.most_recent", "shared_metrics.status", "usage.bars", "subscription.state", "billing.state",
    "display.status", "billing.charge_status", "subscription.preview", "command.resolve",
    "commands.catalog", "complete.slash", "connectors.catalog",
    "connectors.accounts", "connectors.policy.get", "delegation.status", "free_tier.status",
    "i18n.catalog", "i18n.languages", "insights.get", "learning.detail", "learning.frames",
    "mcp.servers.list", "mcp.servers.status", "onboarding.state", "pet.info.meta",
    "pet.gallery", "pet.generate.status", "project.facts", "session.events.stats",
    "setup.status", "setup.runtime_check", "subagent.roster", "system.battery",
    "tools.list", "toolsets.list", "verification.status", "skills.manage",
    "display.thumbnail", "groups.attachment.download", "pet.export", "pet.thumb",
    "profiles.get_asset", "plugins.catalog.lookup",
}
STANDALONE_RPC_WRITES = {
    "projects.create", "projects.update", "projects.add_folder", "projects.remove_folder",
    "projects.set_primary", "projects.archive", "projects.delete", "projects.set_active",
    "model.save_key", "model.disconnect", "profiles.configure",
    "groups.rename", "groups.context", "groups.disband", "groups.stop",
    "groups.create", "groups.send", "groups.continue", "groups.retry", "groups.discard",
    "config.set", "connectors.accounts.rename", "connectors.accounts.remove",
    "learning.edit", "learning.delete", "mcp.servers.remove", "mcp.servers.test",
    "pet.disable", "pet.rename", "pet.remove", "pet.scale", "pet.select",
    "projects.discover_repos", "projects.record_repos", "reload.mcp",
    "shared_metrics.set",
}
WORKSPACE_ATTACHED_READS = {
    "session.context_breakdown", "session.control.read", "session.events.since", "session.history",
    "subagent.list", "tools.show", "process.list", "handoff.state", "session.account_usage",
    "session.export", "rollback.list", "complete.path",
}
WORKSPACE_ATTACHED_WRITES = {
    "handoff.request", "process.kill", "process.stop", "session.save", "skills.reload",
    "subagent.interrupt", "subagent.steer",
}
WORKSPACE_OPTIONAL_SESSION = {
    "commands.catalog", "complete.slash", "config.get", "config.set", "reload.mcp",
}
WORKSPACE_TICKET_PROFILE = {
    "learning.detail", "learning.edit", "learning.delete", "learning.frames",
    "model.save_key", "model.disconnect",
}
WORKSPACE_RPC_FILES = {
    "display.thumbnail", "groups.attachment.download", "pet.export", "pet.thumb",
    "profiles.get_asset", "session.export",
}
VERBS = {"GET": "读取", "HEAD": "检查", "POST": "提交", "PUT": "设置", "PATCH": "更新",
         "DELETE": "删除", "OPTIONS": "查询协议", "CONNECT": "连接"}

LABELS = {
    "/api/health": "检查网关存活", "/api/status": "读取 Hermes 状态",
    "/api/auth/me": "查看当前登录身份", "/api/auth/providers": "查看登录方式",
    "/api/sessions": "会话列表", "/api/profiles/sessions": "所有配置档案的会话",
    "/api/sessions/{session_id}/messages": "读取会话历史", "/api/ws": "连接共享会话网关",
    "/api/webapp/socket-ticket": "获取一次性 WebSocket 票据",
    "/auth/native/authorize": "打开系统浏览器授权", "/auth/native/token": "兑换原生登录凭据",
    "/auth/native/refresh": "续期原生登录凭据", "/auth/logout": "退出网页会话",
    "/api/chat/file-upload": "上传聊天附件", "/api/files/upload-stream": "分块上传文件",
    "/api/audio/transcribe-stream": "连接实时语音转写", "/api/audio/speak-stream": "连接语音合成",
    "ping": "检查网关响应",
    "gateway.ping": "网关连接心跳", "runtime.describe": "读取网关能力",
    "session.create": "新建会话", "session.resume": "恢复会话", "session.list": "列出会话",
    "session.transcript": "读取存档对话", "prompt.submit": "发送消息",
    "prompt.receipt": "查询消息处理结果", "session.events.since": "补读会话事件",
}

RESPONSE_FORMATS = {
    "/auth/native/authorize": "redirect-or-html",
    "/auth/callback": "redirect",
    "/webapp/launch": "html",
    "/webapp/window": "html",
    "/api/audio/speak": "binary",
    "/api/display/ws": "websocket",
    "/api/files/download": "binary",
    "/api/files/stream": "binary",
    "/api/fs/download": "binary",
    "/api/media": "binary",
    "/api/media/proxy": "binary",
}
SOCKET_TICKETS = {
    "/api/ws": ("webapp_gateway_ticket", "票据类别 gateway，可通过专用 WebSocket 子协议或 ticket 查询参数携带。"),
    "/api/host-terminal": ("host_terminal_ticket", "票据类别 host-terminal，还要求 Webapp 终端启用和主机具备 PTY。"),
    "/api/audio/speak-stream": ("speak_stream_ticket", "票据类别 speak-stream，传输语音专用事件和音频帧。"),
    "/api/audio/transcribe-stream": ("transcribe_stream_ticket", "票据类别 transcribe-stream，传输语音专用事件和音频帧。"),
    "/api/display/ws": ("display_ticket", "display.observe 签发屏幕专用票据。连接传输屏幕协议。"),
    "/api/relay/{connection_id}/{path:path}": (
        "relay_browser_ticket", "浏览器中继要求类别 relay 且绑定网页登录或本机网页凭据，原生登录票据本身无浏览器绑定。"),
    "/api/console": ("dashboard_socket_auth", "有状态的控制台命令与显式确认协议。"),
    "/api/pty": ("dashboard_socket_auth", "聊天终端的 PTY 连接，需有效 channel 和主机 PTY 能力。"),
    "/api/pub": ("dashboard_socket_auth", "PTY/Sidecar 内部事件发布通道。"),
    "/api/events": ("dashboard_socket_auth", "PTY/Sidecar 对当前 channel 的事件订阅。"),
    "/api/plugins/kanban/events": (
        "dashboard_socket_auth", "看板插件启用后可用 POST /api/auth/ws-ticket 的一次性票据订阅。"),
}
SOCKET_QUERY = {
    "/api/ws": [("ticket", "string", False, "", "可用一次性子协议票据替代此查询参数。")],
    "/api/host-terminal": [
        ("ticket", "string", True, None, "终端专用票据。"),
        ("profile", "string", False, None, "终端所用的配置档案。"),
        ("cwd", "string", False, None, "打开终端时的工作目录。"),
        ("cols", "integer", False, 80, "终端列数。"),
        ("rows", "integer", False, 24, "终端行数。"),
        ("attach", "string", False, None, "恢复已有终端的附接票据。"),
        ("action", "string", False, None, "对已有终端执行的子操作。"),
    ],
    "/api/audio/speak-stream": [("ticket", "string", False, "", "Webapp 语音合成票据。"),
                                ("profile", "string", False, None, "选择语音配置所属档案。")],
    "/api/audio/transcribe-stream": [("ticket", "string", False, "", "Webapp 语音转写票据。"),
                                     ("profile", "string", False, None, "选择语音配置所属档案。")],
    "/api/display/ws": [("display_ticket", "string", True, None, "屏幕专用票据。")],
    "/api/relay/{connection_id}/{path:path}": [("relay_ticket", "string", True, None, "浏览器中继票据。")],
    "/api/console": [("profile", "string", False, None, "控制台的配置档案。")],
    "/api/pty": [
        ("channel", "string", True, None, "聊天终端及事件对应的通道。"),
        ("resume", "string", False, None, "恢复的会话。"),
        ("profile", "string", False, None, "配置档案。"),
        ("fresh", "boolean", False, False, "是否强制新建。"),
        ("cwd", "string", False, None, "新会话的工作目录。"),
        ("attach", "string", False, None, "附接既有终端的授权。"),
    ],
    "/api/pub": [("channel", "string", True, None, "发布事件的通道。")],
    "/api/events": [("channel", "string", True, None, "订阅事件的通道。")],
    "/api/plugins/kanban/events": [
        ("board", "string", False, None, "看板标识，缺省使用当前看板。"),
        ("profile", "string", False, None, "看板所属配置档案。"),
        ("ticket", "string", False, None, "通过登录会话取得的一次性仪表板连接票据。"),
        ("since", "integer", False, None, "恢复事件流的游标，缺省从当前末尾开始。"),
    ],
}
SPECIAL_JSON_BODIES = {
    "/api/gateway/drain": [
        ("action", "string", False, "drain", "drain 或 cancel。"),
        ("suppress_notification", "boolean", False, False, "抑制网关关闭通知。"),
    ],
    "/api/ops/transfer/chunk": [
        ("offset", "integer", True, None, "分块在归档中的字节偏移，第一块为 0。"),
        ("upload_id", "string", False, "", "第一块为空，后续用已返回的上传 ID。"),
        ("data", "string", False, "", "base64 分块。"),
    ],
    "/api/ops/transfer/restore": [
        ("upload_id", "string", True, None, "已上传的归档 ID。"),
        ("size", "integer", True, None, "归档总字节数。"),
    ],
}
SENSITIVE_RESPONSES = {
    ("GET", "/api/config"), ("POST", "/api/env/reveal"),
    ("GET", "/api/ops/backup/chunk"), ("GET", "/api/ops/backup/download"),
}


class Source:
    def __init__(self, root, module, relative):
        self.module = module
        self.relative = relative.as_posix()
        raw = (root / relative).read_bytes()
        self.digest = hashlib.sha256(raw).hexdigest()
        self.tree = ast.parse(raw.decode("utf-8-sig"), filename=self.relative)
        self.assignments = {}
        self.imports = {}
        self.definitions = {}
        for node in self.tree.body:
            if isinstance(node, ast.ImportFrom):
                for alias in node.names:
                    prefix = node.module or ""
                    if node.level:
                        parent = module.split(".")[:-node.level]
                        prefix = ".".join(parent + ([prefix] if prefix else []))
                    self.imports[alias.asname or alias.name] = prefix, alias.name
            elif isinstance(node, ast.Import):
                for alias in node.names:
                    self.imports[alias.asname or alias.name.split(".")[0]] = alias.name, ""
            elif isinstance(node, ast.Assign):
                for target in node.targets:
                    if isinstance(target, ast.Name):
                        self.assignments[target.id] = node.value
            elif isinstance(node, ast.AnnAssign) and isinstance(node.target, ast.Name):
                self.assignments[node.target.id] = node.value
            elif isinstance(node, (*FUNCTIONS, ast.ClassDef)):
                self.definitions[node.name] = node


class Catalog:
    def __init__(self, root):
        self.root = root
        self.files = {}

    @cache
    def load(self, module):
        relative = Path(*module.split(".")).with_suffix(".py")
        if not (self.root / relative).is_file():
            return None
        source = Source(self.root, module, relative)
        self.files[source.relative] = source.digest
        return source

    def resolve(self, source, expression):
        if isinstance(expression, ast.Constant) and isinstance(expression.value, str):
            try:
                expression = ast.parse(expression.value, mode="eval").body
            except SyntaxError:
                return source, None
        if isinstance(expression, ast.Name):
            name = expression.id
            if name in source.definitions:
                return source, source.definitions[name]
            if name in source.assignments:
                return source, source.assignments[name]
            if name in source.imports:
                module, symbol = source.imports[name]
                target = self.load(module)
                if target is not None and symbol:
                    return self.resolve(target, ast.Name(id=symbol))
        if isinstance(expression, ast.Attribute) and isinstance(expression.value, ast.Name):
            module, symbol = source.imports.get(expression.value.id, ("", ""))
            if module:
                target = self.load(".".join(filter(None, [module, symbol])))
                if target is not None:
                    return self.resolve(target, ast.Name(id=expression.attr))
        return source, None

    def value(self, source, expression):
        if expression is None:
            return None
        try:
            return ast.literal_eval(expression)
        except (TypeError, ValueError):
            pass
        if isinstance(expression, ast.BinOp) and isinstance(expression.op, ast.Add):
            return self.value(source, expression.left) + self.value(source, expression.right)
        if isinstance(expression, ast.Call) and name_of(expression.func) in {"frozenset", "set"}:
            return self.value(source, expression.args[0])
        target, node = self.resolve(source, expression)
        if node is not None and node is not expression and not isinstance(node, (*FUNCTIONS, ast.ClassDef)):
            return self.value(target, node)
        raise ValueError(ast.unparse(expression))

    def model_fields(self, source, reference, seen=frozenset()):
        origin, node = self.resolve(source, reference)
        if not isinstance(node, ast.ClassDef):
            return None
        key = origin.module, node.name
        if key in seen:
            return None
        fields = {}
        for base in node.bases:
            for field in self.model_fields(origin, base, seen | {key}) or []:
                fields[field["name"]] = field
        for member in node.body:
            if isinstance(member, ast.AnnAssign) and isinstance(member.target, ast.Name):
                name = member.target.id
                annotation = member.annotation
                if name != "model_config" and not name.startswith("_") and not ast.unparse(annotation).startswith("ClassVar"):
                    field = self.field(origin, name, annotation, member.value)
                    fields[field["name"]] = field
        return list(fields.values())

    def field(self, source, name, annotation, default, required_override=None):
        wrapper = name_of(default.func) if isinstance(default, ast.Call) else ""
        options = keywords(default) if wrapper else {}
        description = ""
        if "description" in options:
            description = self.value(source, options["description"])
        original = default
        if wrapper in {"Query", "Path", "Body", "File", "Form", "Field", "Header", "Cookie"}:
            default = options.get("default") or (original.args[0] if original.args else None)
        required = (default is None and "default_factory" not in options) or (
            isinstance(default, ast.Constant) and default.value is Ellipsis)
        default_value = None
        default_expression = None
        if not required:
            try:
                default_value = self.value(source, default)
            except (ValueError, TypeError):
                default_expression = ast.unparse(default)
        if "default_factory" in options:
            default_expression = ast.unparse(options["default_factory"])
            default_value = {"dict": {}, "list": []}.get(default_expression)
        schema = schema_type(annotation)
        alias = self.value(source, options.get("alias")) if "alias" in options else None
        out = {
            "name": alias or name, "type": schema["type"],
            "required": required if required_override is None else required_override,
            "default": default_value, "description": description,
        }
        if default_expression is not None:
            out["defaultExpression"] = default_expression
        if schema.get("nullable"):
            out["nullable"] = True
        if len(schema) > 1:
            out["schema"] = schema
        limits = {}
        for limit in ("ge", "gt", "le", "lt", "min_length", "max_length", "pattern"):
            if limit in options:
                try:
                    limits[limit] = self.value(source, options[limit])
                except (TypeError, ValueError):
                    limits[limit] = ast.unparse(options[limit])
        if limits:
            out["constraints"] = limits
        return out

    def rest_params(self, source, fn, path):
        names = re.findall(r"{([A-Za-z_]\w*)(?::\w+)?}", path)
        params = empty_params()
        args = fn.args
        positional = args.posonlyargs + args.args
        defaults = [None] * (len(positional) - len(args.defaults)) + args.defaults
        for argument, default in zip(positional + args.kwonlyargs, defaults + args.kw_defaults):
            name, annotation = argument.arg, argument.annotation
            wrapper = name_of(default.func) if isinstance(default, ast.Call) else ""
            type_name = name_of(annotation)
            if (fn.name == "__call__" and name in {"self", "scope", "receive", "send"}) or wrapper in {
                "Depends", "Security",
            } or type_name in {
                "Request", "Response", "WebSocket", "BackgroundTasks", "HTTPConnection",
            }:
                continue
            if name in names or wrapper == "Path":
                params["path"].append(self.field(source, name, annotation, default, True))
            elif wrapper in {"Header", "Cookie"}:
                params["headers"].append(self.field(source, name.replace("_", "-"), annotation, default))
            elif wrapper in {"File", "Form"} or "UploadFile" in (ast.unparse(annotation) if annotation else ""):
                params["body"].append(self.field(source, name, annotation, default))
                if wrapper == "File" or schema_type(annotation)["type"] == "file":
                    params["contentType"] = "multipart/form-data"
                elif not params["contentType"]:
                    params["contentType"] = "application/x-www-form-urlencoded"
            else:
                fields = self.model_fields(source, unwrap_optional(annotation))
                if fields is not None and wrapper != "Query":
                    params["body"].extend(fields)
                    params["contentType"] = "application/json"
                elif wrapper == "Body" or (annotation and isinstance(annotation, ast.Subscript)
                                           and name_of(annotation.value) in {"dict", "Dict"}):
                    embedded = isinstance(default, ast.Call) and keywords(default).get("embed")
                    field_name = name if embedded and self.value(source, embedded) else "$body"
                    params["body"].append(self.field(source, field_name, annotation, default))
                    media = keywords(default).get("media_type") if wrapper == "Body" else None
                    params["contentType"] = self.value(source, media) if media else "application/json"
                else:
                    params["query"].append(self.field(source, name, annotation, default))
        registered = {field["name"] for field in params["path"]}
        for name in names:
            if name not in registered:
                params["path"].append(self.field(source, name, ast.Name(id="str"), None, True))
        if not params["body"]:
            for node in ast.walk(fn):
                if not isinstance(node, ast.Call) or not isinstance(node.func, ast.Attribute):
                    continue
                method = node.func.attr
                receiver = name_of(node.func.value)
                if receiver in {"request", "req"} and method in {"json", "body", "stream", "form"}:
                    params["contentType"] = {
                        "json": "application/json", "body": "application/octet-stream",
                        "stream": "application/octet-stream", "form": "multipart/form-data",
                    }[method]
                    annotation = ast.Name(id="dict" if method == "json" else "bytes")
                    field = self.field(source, "$body", annotation, None, True)
                    field["description"] = "请求正文由源处理函数解析，字段约束取决于处理逻辑。"
                    params["body"].append(field)
                    break
        return params

    def rest(self):
        server = self.load("hermes_cli.web_server")
        surface = self.load("hermes_cli.web_server_surface")
        policy = next(v for k, v in zip(surface.assignments["POLICIES"].keys,
                                        surface.assignments["POLICIES"].values)
                      if self.value(surface, k) == "webapp")
        flags = {key: self.value(surface, v) for key, v in keywords(policy).items()}
        paths = self.load("hermes_cli.dashboard_auth.public_paths")
        public = self.value(paths, paths.assignments["PUBLIC_API_PATHS"])
        middleware = self.load("hermes_cli.dashboard_auth.middleware")
        public_prefixes = self.value(middleware, middleware.assignments["_GATE_PUBLIC_PREFIXES"])
        for stmt in server.tree.body:
            if not isinstance(stmt, ast.Expr) or not isinstance(stmt.value, ast.Call):
                continue
            call = stmt.value
            if ast.unparse(call.func) != "app.include_router":
                continue
            route_flags = [self.value(server, node.args[0])
                           for node in ast.walk(call) if isinstance(node, ast.Call)
                           and name_of(node.func) == "_route_on"]
            if any(not flags[flag] for flag in route_flags):
                continue
            source, router = self.resolve(server, call.args[0])
            if not isinstance(router, ast.Call) or name_of(router.func) != "APIRouter":
                raise ValueError(f"Cannot resolve mounted router: {ast.unparse(call)}")
            router_name = name_of(call.args[0])
            if source is not server and isinstance(call.args[0], ast.Name):
                router_name = server.imports[router_name][1]
            prefix = self.value(source, keywords(router).get("prefix")) or ""
            mount_prefix = self.value(server, keywords(call).get("prefix")) or ""
            yield from self.router_entries(source, router_name, mount_prefix + prefix, flags,
                                           route_flags, public, public_prefixes)
        dashboard = self.load("hermes_cli.web_server_dashboard")
        fallback = dashboard.definitions["_register_plugin_api_fallback"]
        registration = next(call for call in ast.walk(fallback)
                            if isinstance(call, ast.Call) and name_of(call.func) == "add_route")
        endpoint = next(fn for fn in dashboard.definitions["_PluginApiHotMount"].body
                        if isinstance(fn, FUNCTIONS) and fn.name == "__call__")
        for method in self.value(dashboard, keywords(registration)["methods"]):
            yield self.http_entry(dashboard, endpoint,
                                  registration, method, self.value(dashboard, registration.args[0]), False, [])

        for path in sorted((self.root / "plugins").glob("*/dashboard/manifest.json")):
            raw = path.read_bytes()
            manifest = json.loads(raw.decode("utf-8-sig"))
            if "api" not in manifest:
                continue
            relative = path.relative_to(self.root)
            self.files[relative.as_posix()] = hashlib.sha256(raw).hexdigest()
            api = path.parent / manifest["api"]
            if api.suffix != ".py" or api.parent != path.parent:
                raise ValueError(f"Unexpected bundled plugin API path: {relative}")
            source = self.load(".".join(api.relative_to(self.root).with_suffix("").parts))
            router = source.assignments["router"]
            prefix = self.value(source, keywords(router).get("prefix")) or ""
            yield from self.router_entries(source, "router", f"/api/plugins/{manifest['name']}" + prefix,
                                           flags, [], public, public_prefixes)

    def router_entries(self, source, router_name, prefix, flags, route_flags, public, public_prefixes):
        for fn in source.tree.body:
            if not isinstance(fn, FUNCTIONS):
                continue
            for decor in fn.decorator_list:
                if not isinstance(decor, ast.Call) or not isinstance(decor.func, ast.Attribute):
                    continue
                if name_of(decor.func.value) != router_name:
                    continue
                decorator = decor.func.attr
                if decorator not in HTTP_METHODS | {"websocket", "api_route"}:
                    raise ValueError(f"Unknown route decorator: {source.relative}:{decor.lineno}")
                path = prefix + self.value(source, decor.args[0])
                methods = [decorator.upper()]
                if decorator == "api_route":
                    methods = self.dynamic_methods(source, keywords(decor)["methods"])
                method_flags = [self.value(source, node.args[0]) for node in ast.walk(decor)
                                if isinstance(node, ast.Call) and name_of(node.func) == "route_on"]
                if any(not flags[flag] for flag in method_flags):
                    continue
                is_public = path in public or any(path.startswith(item) for item in public_prefixes)
                for method in sorted(methods):
                    yield self.http_entry(source, fn, decor, method, path, is_public,
                                          route_flags + method_flags)

    def dynamic_methods(self, source, expression):
        try:
            return self.value(source, expression)
        except (ValueError, TypeError):
            if source.module.endswith(".peer") and name_of(expression) == "_METHODS":
                return {node.elts[0].value for node in ast.walk(source.assignments["_ROUTES"])
                        if isinstance(node, ast.Tuple) and len(node.elts) == 2
                        and isinstance(node.elts[0], ast.Constant) and isinstance(node.elts[1], ast.Constant)}
            raise

    def http_entry(self, source, fn, decor, method, path, public, flags):
        slug = source.module.rsplit(".", 1)[-1]
        kind = "websocket" if method == "WEBSOCKET" else ("auth" if slug == "routes" else "rest")
        if kind == "websocket":
            method = "CONNECT"
        params = self.rest_params(source, fn, path)
        if path in SPECIAL_JSON_BODIES:
            params["contentType"] = "application/json"
            params["body"] = [explicit_field(*fields) for fields in SPECIAL_JSON_BODIES[path]]
        if path == "/api/webapp/socket-ticket":
            ws_routes = self.load("hermes_cli.web_routers.webapp")
            socket_defs = self.load("hermes_cli.web_session_sockets")
            socket_types = [self.value(socket_defs, key) for key in ws_routes.assignments["SOCKET_TICKETS"].keys]
            for field in params["query"]:
                if field["name"] == "socket":
                    field.update(required=True, description="票据的授权目标。",
                                 schema={"type": "string", "enum": socket_types})
        if path == "/auth/native/authorize":
            for field in params["query"]:
                if field["name"] in {"code_challenge", "code_challenge_method", "redirect_uri"}:
                    field["required"] = True
                if field["name"] == "code_challenge_method":
                    field["description"] = "服务端要求 S256。"
                elif field["name"] == "redirect_uri":
                    field["description"] = "原生客户端的 HTTP loopback 回调地址。"
        if kind == "websocket":
            params["query"].extend(explicit_field(*fields) for fields in SOCKET_QUERY.get(path, ()))
        if slug == "plugin_api" and kind == "rest" and not any(f["name"] == "profile" for f in params["query"]):
            params["query"].append(explicit_field("profile", "string", False, None,
                                                  "由 Webapp 选择插件所属的配置档案。"))
        if path == "/webapp/window-session":
            params["headers"].append(explicit_field("X-Hermes-Window-Ticket", "string", True, None,
                                                    "私有 Webapp 启动或窗口交接票据。"))
        if path.startswith("/api/peer/") and method == "GET":
            params["body"] = []
            params["contentType"] = None
        access, auth, notes = http_access(slug, kind, method, path, public, params)
        for flag in flags:
            notes.append(f"Webapp surface policy 已开启 {flag}。")
        response_format = response_type(fn, decor, kind, method, path)
        group = GROUPS.get(slug, "扩展插件")
        label = LABELS.get(path) or (VERBS.get(method, method) + group + " · " + path.removeprefix("/api/"))
        description = f"{label}。上游处理函数为 {fn.name}。"
        result = entry(f"{kind}:{method}:{path}", kind, method, path, group, label,
                       description, params, response_format, access, auth, notes,
                       method not in {"GET", "HEAD", "OPTIONS"}, source, decor.lineno)
        result["groupId"] = slug
        if summary := doc_summary(fn):
            result["upstreamDescription"] = summary
        if (method, path) in SENSITIVE_RESPONSES:
            result["constraints"]["sensitiveResponse"] = True
        return result

    def rpc_contracts(self):
        normal, canonical, namespaces = {}, {}, {}
        for path in sorted((self.root / "tui_gateway/contracts").glob("*.py")):
            source = self.load(f"tui_gateway.contracts.{path.stem}")
            for stmt in source.tree.body:
                if isinstance(stmt, ast.Expr) and isinstance(stmt.value, ast.Call):
                    call = stmt.value
                    target = name_of(call.func)
                    if target in {"method", "canonical_method", "canonical_namespace"}:
                        options = keywords(call)
                        table = {"method": normal, "canonical_method": canonical,
                                 "canonical_namespace": namespaces}[target]
                        table[self.value(source, call.args[0])] = (
                            source, call.lineno, options["params"], options.get("result"),
                            self.value(source, options.get("doc")) or "",
                        )
                elif isinstance(stmt, ast.For) and isinstance(stmt.iter, (ast.Tuple, ast.List)):
                    call = next((node for node in ast.walk(stmt)
                                 if isinstance(node, ast.Call) and name_of(node.func) in
                                 {"method", "canonical_method"}), None)
                    if call is None:
                        continue
                    table = canonical if name_of(call.func) == "canonical_method" else normal
                    if not isinstance(stmt.target, ast.Tuple):
                        raise ValueError(f"Unknown contract loop: {source.relative}:{stmt.lineno}")
                    for item in stmt.iter.elts:
                        bindings = dict(zip((target.id for target in stmt.target.elts), item.elts))
                        param, result = (bindings.get(name_of(keywords(call)[key]), keywords(call)[key])
                                         for key in ("params", "result"))
                        table[self.value(source, bindings[name_of(call.args[0])])] = (
                            source, item.lineno, param, result, "",
                        )
        return normal, canonical, namespaces

    def handler_keys(self, source, expr):
        if isinstance(expr, ast.Dict):
            output = []
            for key, value in zip(expr.keys, expr.values):
                if key is None:
                    output.extend(self.handler_keys(source, value))
                else:
                    output.append((self.value(source, key), key.lineno))
            return output
        if isinstance(expr, ast.DictComp):
            generator = expr.generators[0]
            if isinstance(generator.iter, ast.Call) and name_of(generator.iter.func) == "items":
                return self.handler_keys(source, generator.iter.func.value)
            return [(key, expr.lineno) for key in self.value(source, generator.iter)]
        target, node = self.resolve(source, expr)
        if node is not None:
            return self.handler_keys(target, node)
        raise ValueError(f"Unknown handler-table expression {source.relative}: {ast.unparse(expr)}")

    def authority_methods(self):
        source = self.load("gateway.session_controls")
        owner = source.definitions["AuthorityConnection"]
        fn = next(node for node in owner.body if isinstance(node, ast.FunctionDef) and node.name == "handlers")
        imports = {}
        methods = {}
        for node in fn.body:
            if isinstance(node, ast.ImportFrom):
                for alias in node.names:
                    imports[alias.asname or alias.name] = node.module, alias.name
            elif isinstance(node, ast.Assign):
                target = node.targets[0]
                if isinstance(target, ast.Name) and target.id == "handlers":
                    methods.update({name: (source, line) for name, line in self.handler_keys(source, node.value)})
                elif isinstance(target, ast.Subscript) and name_of(target.value) == "handlers":
                    methods[self.value(source, target.slice)] = source, target.lineno
            elif isinstance(node, ast.Expr) and isinstance(node.value, ast.Call) and (
                ast.unparse(node.value.func) == "handlers.update"
            ):
                call = node.value.args[0]
                module, symbol = imports[name_of(call.func)]
                ext = self.load(module)
                function = ext.definitions[symbol]
                for ret in (item for item in ast.walk(function) if isinstance(item, ast.Return)):
                    methods.update({name: (ext, line) for name, line in self.handler_keys(ext, ret.value)})
        groups = self.load("gateway.session_group_controls")
        group_table = groups.assignments["GROUP_METHODS"]
        methods.update({name: (groups, line) for name, line in self.handler_keys(groups, group_table)})
        methods["profiles.list"] = groups, groups.assignments["_FIELDS"].lineno
        return methods

    def legacy_methods(self):
        server = self.load("tui_gateway.server")
        active = [server]
        for node in server.tree.body:
            if isinstance(node, ast.For) and isinstance(node.iter, ast.Tuple) and any(
                isinstance(call, ast.Call) and ast.unparse(call.func) == "_m.register" for call in ast.walk(node)
            ):
                for name in node.iter.elts:
                    module, symbol = server.imports[name.id]
                    active.append(self.load(f"{module}.{symbol}"))
        fallback = self.load("tui_gateway.ws_legacy_fallback")
        denied = self.value(fallback, fallback.assignments["_PROCESS_GLOBAL"])
        namespaces = tuple(self.value(fallback, fallback.assignments["_SESSION_NAMESPACES"]))
        allowed = self.value(fallback, fallback.assignments["_SESSION_FALLBACK_ALLOWED"])
        methods = {}
        for source in active:
            registration = source.definitions.get("register")
            if isinstance(registration, FUNCTIONS):
                for node in ast.walk(registration):
                    if isinstance(node, ast.ImportFrom) and node.level == 1 and not node.module:
                        for alias in node.names:
                            name = alias.asname or alias.name
                            if any(isinstance(call, ast.Call) and ast.unparse(call.func) == f"{name}.register"
                                   for call in ast.walk(registration)):
                                dependency = self.load(f"tui_gateway.{alias.name}")
                                if dependency not in active:
                                    active.append(dependency)
            decorators = {"method": ""}
            for defn in source.definitions.values():
                if not isinstance(defn, FUNCTIONS) or not defn.args.args:
                    continue
                parameter = defn.args.args[0].arg
                for call in ast.walk(defn):
                    if (isinstance(call, ast.Call) and call.args
                            and (wrapped := name_of(call.func)) in decorators
                            and (prefix := rpc_name_prefix(call.args[0], parameter)) is not None):
                        decorators[defn.name] = decorators[wrapped] + prefix
                        break
            for fn in source.tree.body:
                if not isinstance(fn, FUNCTIONS):
                    continue
                for call in fn.decorator_list:
                    if not isinstance(call, ast.Call) or name_of(call.func) not in decorators:
                        continue
                    method = decorators[name_of(call.func)] + self.value(source, call.args[0])
                    if method not in denied and (not method.startswith(namespaces) or method in allowed):
                        methods[method] = source, call.lineno
            registrars = {}
            for fn in source.tree.body:
                if not isinstance(fn, FUNCTIONS) or not fn.args.args:
                    continue
                parameter = fn.args.args[0].arg
                for nested in ast.walk(fn):
                    if nested is fn or not isinstance(nested, FUNCTIONS):
                        continue
                    for call in nested.decorator_list:
                        if not isinstance(call, ast.Call) or name_of(call.func) not in decorators or not call.args:
                            continue
                        declared = call.args[0]
                        if name_of(declared) == parameter:
                            registrars[fn.name] = ""
                        elif (isinstance(declared, ast.JoinedStr) and len(declared.values) == 2
                              and isinstance(declared.values[0], ast.Constant)
                              and isinstance(declared.values[1], ast.FormattedValue)
                              and name_of(declared.values[1].value) == parameter):
                            registrars[fn.name] = declared.values[0].value
                        else:
                            raise ValueError(f"Unsupported RPC registration at {source.relative}:{call.lineno}")
            for stmt in source.tree.body:
                if isinstance(stmt, ast.Expr) and isinstance(stmt.value, ast.Call):
                    call = stmt.value
                    registrar = name_of(call.func)
                    if registrar in registrars:
                        method = registrars[registrar] + self.value(source, call.args[0])
                        if method not in denied and (not method.startswith(namespaces) or method in allowed):
                            methods[method] = source, call.lineno
                for method, line in self.legacy_applications(source, stmt, decorators):
                    if method not in denied and (not method.startswith(namespaces) or method in allowed):
                        methods[method] = source, line
        return methods

    def legacy_applications(self, source, statement, decorators):
        """Decorator factories applied to top-level lambdas and finite registration loops."""
        if isinstance(statement, ast.For):
            target = statement.target
            first = target.elts[0] if isinstance(target, ast.Tuple) else target
            if not isinstance(first, ast.Name):
                return
            binding = first.id
            body = statement.body
        else:
            binding = None
            body = [statement]
        for node in body:
            if not isinstance(node, ast.Expr) or not isinstance(node.value, ast.Call):
                continue
            factory = node.value.func
            if not isinstance(factory, ast.Call) or not factory.args:
                continue
            name = name_of(factory.func)
            if name not in decorators:
                continue
            declared = factory.args[0]
            if binding is None:
                yield decorators[name] + self.value(source, declared), factory.lineno
                continue
            prefix = rpc_name_prefix(declared, binding)
            if prefix is None:
                continue
            for suffix in self.legacy_loop_names(source, statement.iter):
                yield decorators[name] + prefix + suffix, factory.lineno

    def legacy_loop_names(self, source, expression):
        if isinstance(expression, ast.Call) and isinstance(expression.func, ast.Attribute):
            if expression.func.attr == "items":
                origin, assignment = self.resolve(source, expression.func.value)
                if isinstance(assignment, ast.Dict):
                    return [self.value(origin, key) for key in assignment.keys]
        if isinstance(expression, (ast.List, ast.Tuple)):
            return [self.value(source, value.elts[0] if isinstance(value, ast.Tuple) else value)
                    for value in expression.elts]
        raise ValueError(f"Unsupported legacy RPC registration loop: {source.relative}:{expression.lineno}")

    def rpc(self):
        normal, canonical, namespaces = self.rpc_contracts()
        owners = self.authority_methods()
        legacy = self.legacy_methods()
        gateway = self.load("tui_gateway.ws")
        heartbeat = next(compare for compare in ast.walk(gateway.tree) if isinstance(compare, ast.Compare)
                         and any(isinstance(value, ast.Constant) and value.value == "gateway.ping"
                                 for value in compare.comparators))
        registered = {**legacy, **owners, "gateway.ping": (gateway, heartbeat.lineno)}
        for method, (source, line) in sorted(registered.items()):
            contract = (canonical.get(method) if method in owners else None) or normal.get(method)
            if contract is None and method != "gateway.ping":
                raise ValueError(f"RPC has no statically extracted contract: {method}")
            yield self.rpc_entry(method, source, line, contract, method in owners, False)
        for method, contract in namespaces.items():
            source, line = contract[:2]
            yield self.rpc_entry(method, source, line, contract, True, True)

    def rpc_entry(self, method, source, line, contract, canonical, dynamic):
        params = empty_params()
        params["contentType"] = "application/json"
        result_name = None
        description = ""
        if contract:
            model_source, model_line, model, result, description = contract
            params["body"] = self.model_fields(model_source, model)
            if params["body"] is None:
                raise ValueError(f"Unresolved RPC params: {method} ({ast.unparse(model)})")
            result_name = ast.unparse(result) if result else None
        group = RPC_GROUPS.get(method.split(".")[0], "网关扩展")
        label = LABELS.get(method, group + " · " + method)
        auth = "webapp_gateway_ticket"
        access = "remote" if method in STANDALONE_RPCS | STANDALONE_RPC_WRITES else "special_transport"
        notes = ["在 /api/ws 的 JSON-RPC 连接调用。服务端检查身份、授权能力、会话与配置档案。"]
        if method.startswith("worker."):
            access = "local_only"
            auth = "native_gateway_worker"
            notes.append("工作进程注册、接管或持久化使用本机 gateway worker 身份。")
        elif dynamic:
            access = "conditional"
            notes.append("方法名由获准的已启用插件声明，目录仅列上游命名空间。")
        if not canonical and method != "gateway.ping":
            notes.append("通过网关 legacy fallback，要求完整的交互授权。")
        if method == "session.resume":
            notes.append("须指定 session_id、title 或 latest；返回的订阅只在此连接有效。")
        elif method == "prompt.submit":
            notes.append("同一连接须先附接会话，并提供 submission_id 或 input_id。")
        elif method in WORKSPACE_ATTACHED_READS | WORKSPACE_ATTACHED_WRITES:
            notes.append("手机通过已授权的网关连接恢复 session_id，再调用会话接口。")
            for field in params["body"]:
                if field["name"] == "session_id":
                    field.update(required=True, nullable=False)
            if not any(field["name"] == "profile" for field in params["body"]):
                params["query"].append(explicit_field("profile", "string", False, None,
                                                       "请求网关票据时选择配置档案。"))
        elif method in WORKSPACE_TICKET_PROFILE:
            params["query"].append(explicit_field("profile", "string", False, None,
                                                   "请求网关票据时选择配置档案。"))
        elif method in WORKSPACE_OPTIONAL_SESSION:
            notes.append("指定会话时，手机先在同一网关连接恢复该会话。")
        elif method in {"cron.submit", "cron.cancel", "cron.recover", "cron.status"}:
            notes.append("网关还检查此定时任务和当前配置档案的权限。")
        value = entry("rpc:" + method, "rpc", method, "/api/ws", group, label,
                      f"{label}。使用 Hermes 网关 JSON-RPC 调用。",
                      params, "binary" if method in WORKSPACE_RPC_FILES else "jsonrpc", access, auth,
                      notes, rpc_mutates(method), source, line)
        value["groupId"] = method.split(".")[0]
        if method in WORKSPACE_ATTACHED_READS | WORKSPACE_ATTACHED_WRITES:
            value["constraints"]["sessionBinding"] = "required"
        elif method in WORKSPACE_OPTIONAL_SESSION:
            value["constraints"]["sessionBinding"] = "optional"
        if method in WORKSPACE_RPC_FILES:
            value["response"]["wireFormat"] = "jsonrpc"
        if contract:
            value["contractSource"] = {"file": model_source.relative, "line": model_line}
        if result_name:
            value["response"]["schemaName"] = result_name
        if description:
            value["upstreamDescription"] = description.strip().split("\n", 1)[0][:180]
        return value


def name_of(node):
    if isinstance(node, ast.Name):
        return node.id
    if isinstance(node, ast.Attribute):
        return node.attr
    return ""


def rpc_name_prefix(node, parameter):
    if isinstance(node, ast.Name) and node.id == parameter:
        return ""
    if (isinstance(node, ast.JoinedStr) and len(node.values) == 2
            and isinstance(node.values[0], ast.Constant)
            and isinstance(node.values[1], ast.FormattedValue)
            and name_of(node.values[1].value) == parameter):
        return node.values[0].value
    return None


def keywords(call):
    return {item.arg: item.value for item in call.keywords}


def unwrap_optional(annotation):
    if isinstance(annotation, ast.Subscript) and name_of(annotation.value) == "Optional":
        return annotation.slice
    if isinstance(annotation, ast.BinOp) and isinstance(annotation.op, ast.BitOr):
        if isinstance(annotation.right, ast.Constant) and annotation.right.value is None:
            return annotation.left
    return annotation


def schema_type(annotation):
    if isinstance(annotation, ast.Constant) and isinstance(annotation.value, str):
        annotation = ast.parse(annotation.value, mode="eval").body
    unwrapped = unwrap_optional(annotation)
    if unwrapped is not annotation:
        return {**schema_type(unwrapped), "nullable": True}
    if isinstance(annotation, ast.BinOp) and isinstance(annotation.op, ast.BitOr):
        return {"type": "any", "oneOf": [schema_type(annotation.left), schema_type(annotation.right)]}
    if isinstance(annotation, ast.Subscript):
        kind = name_of(annotation.value)
        item = annotation.slice
        if kind in {"list", "List", "Sequence", "set", "Set", "tuple", "Tuple"}:
            return {"type": "array", "items": schema_type(item)}
        if kind in {"dict", "Dict", "Mapping"}:
            return {"type": "object"}
        if kind == "Literal":
            values = ast.literal_eval(item)
            values = list(values) if isinstance(values, tuple) else [values]
            return {**schema_type(ast.Name(id=type(values[0]).__name__)), "enum": values}
        if kind == "Annotated":
            return schema_type(item.elts[0])
    name = name_of(annotation)
    scalar = {"str": "string", "int": "integer", "float": "number", "bool": "boolean",
              "dict": "object", "list": "array", "UploadFile": "file", "bytes": "file",
              "Any": "any", "JsonValue": "any", "object": "object"}
    if name in scalar:
        return {"type": scalar[name]}
    return {"type": "any", **({"annotation": ast.unparse(annotation)} if annotation else {})}


def empty_params():
    return {"path": [], "query": [], "body": [], "headers": [], "contentType": None}


def explicit_field(name, type_, required, default, description):
    return {"name": name, "type": type_, "required": required, "default": default,
            "description": description}


def doc_summary(node):
    if not isinstance(node, (*FUNCTIONS, ast.ClassDef)):
        return ""
    doc = ast.get_docstring(node)
    return doc.split("\n", 1)[0].strip()[:180] if doc else ""


def response_type(fn, decor, kind, method, path=None):
    if kind == "websocket":
        return "websocket"
    if method == "HEAD":
        return "empty"
    if path in RESPONSE_FORMATS:
        return RESPONSE_FORMATS[path]
    response_classes = {name_of(keywords(decor).get("response_class")), name_of(fn.returns)}
    for node in ast.walk(fn):
        if isinstance(node, ast.Return) and isinstance(node.value, ast.Call):
            response_classes.add(name_of(node.value.func))
    if "StreamingResponse" in response_classes:
        return "sse" if "text/event-stream" in ast.unparse(fn) else "binary"
    for cls, name in (("FileResponse", "binary"), ("OpenedFileResponse", "binary"), ("HTMLResponse", "html"),
                      ("RedirectResponse", "redirect"), ("PlainTextResponse", "text")):
        if cls in response_classes:
            return name
    return "binary" if "media_type=" in ast.unparse(fn) and "application/octet-stream" in ast.unparse(fn) else "json"


def http_access(slug, kind, method, path, public, params):
    auth = "public" if public else "webapp_session"
    notes = []
    if slug == "webapp_access":
        return "desktop_only", "native_http_grant", ["这台电脑的 Hermes Desktop 通过本机控制套接字取得许可。"]
    if slug == "webapp" and ("/window" in path or path.endswith("/launch")):
        return "local_only", "webapp_private_launch", ["仅未启用远程登录门禁的 Webapp。票据需按本机发起的交接流程使用。"]
    if kind == "websocket":
        credential, protocol = SOCKET_TICKETS[path]
        access = "conditional" if slug == "plugin_api" else "special_transport"
        return access, credential, [protocol, "连接仍受服务端 Host、Origin 与会话权限限制。"]
    if slug == "peer":
        return "special_transport", "peer_pass", ["仅转发源码许可表中的下游请求，需 Peer 授权和对应权限。"]
    if slug in {"webapp_relay_proxy", "web_server_dashboard", "plugin_api"}:
        return "conditional", auth, ["目标中继或插件须已启用，实际接口由它的许可表或注册路由决定。"]
    if kind == "auth":
        if path in {"/auth/native/token", "/auth/native/refresh", "/api/auth/ws-ticket"}:
            return "special_transport", auth, ["由原生登录或 WebSocket 握手流程使用。"]
        if path in {"/api/auth/me", "/api/auth/providers"}:
            return "remote", auth, notes
        return "browser", auth, ["按上游网页登录、回调或退出流程调用。"]
    if path == "/api/cron/fire":
        return "special_transport", "cron_fire_jwt", ["Chronos 触发器必须提交对应目的的签名令牌。"]
    if path == "/api/webapp/socket-ticket":
        return "special_transport", auth, ["按 socket 类型获取短效一次性票据。"]
    if path == "/api/gateway-connections" and method == "GET":
        return "remote", auth, ["只读取 Webapp 的公开连接目录。条目省略令牌和请求头值。"]
    if (method, path) in SENSITIVE_RESPONSES or path.startswith("/api/credentials/"):
        return "conditional", auth, ["上游可向有权限的调用者返回敏感内容。通用界面不读取或保存此类响应。"]
    if (path in {"/api/media/proxy", "/api/ssh/ownership"}
            or path.startswith("/api/gateway-connections") or path.startswith("/api/ops/transfer/")):
        return "special_transport", auth, ["按对应连接、分块或远端资源的专用流程使用。"]
    if path.startswith("/dashboard-plugins/"):
        return "browser", auth, ["扩展的静态资源由浏览器加载，仍受插件启用状态和资源路径检查。"]
    if path.endswith("/open-terminal"):
        return "special_transport", auth, ["该操作在服务端电脑上打开系统终端。"]
    if "/oauth/" in path or path.endswith("/oauth") or path.startswith("/webapp/"):
        return "browser", auth, ["身份提供方和回调状态由对应的授权流程管理。"]
    if params["contentType"] in {"application/octet-stream", "application/x-www-form-urlencoded"}:
        return "special_transport", auth, ["按接口正文格式与相关流式会话约束传输。"]
    if any(field["name"] == "$body" and field["description"] for field in params["body"]):
        return "conditional", auth, ["处理函数自行解析请求正文，请核对上游的内容格式。"]
    return "remote", auth, notes


def rpc_mutates(method):
    reads = {"gateway.ping", "ping", "runtime.describe", "session.transcript", "session.events.since",
             "prompt.receipt", "config.get", "complete.path", "complete.slash"}
    return method not in reads | STANDALONE_RPCS | WORKSPACE_ATTACHED_READS and not method.endswith(
        (".list", ".status", ".info", ".capabilities", ".get", ".preview", ".stats", ".describe"))


def entry(id_, kind, method, path, group, label, description, params, format_, access,
          auth, notes, mutating, source, line):
    return {
        "id": id_, "kind": kind, "method": method, "path": path, "group": group,
        "label": label, "description": description, "params": params,
        "response": {"format": format_, "description": "由上游接口返回。"},
        "constraints": {"access": access, "auth": auth, "notes": notes, "mutating": mutating,
                        "confirmationRequired": mutating},
        "source": {"file": source.relative, "line": line},
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True, help="Store agent-payload/hermes-agent directory")
    parser.add_argument("--check", action="store_true", help="Verify the bundled asset against the source")
    args = parser.parse_args()
    manifest = json.loads((args.source.parent / "manifest.json").read_text(encoding="utf-8-sig"))
    if manifest.get("ref") != EXPECTED_REF:
        parser.error("The package ref differs from the verified v0.23.2 Store source")
    catalog = Catalog(args.source)
    entries = sorted([*catalog.rest(), *catalog.rpc()], key=lambda item: item["id"])
    ids = Counter(item["id"] for item in entries)
    if any(count != 1 for count in ids.values()):
        raise ValueError(f"Duplicate IDs: {[key for key, count in ids.items() if count != 1]}")
    coverage = dict(sorted(Counter(item["kind"] for item in entries).items()))
    result = {
        "schemaVersion": 1,
        "upstream": {"product": "Hermes Agent Webapp", "version": "0.23.2",
                     "packageId": PACKAGE_ID, "ref": EXPECTED_REF},
        "coverage": coverage,
        "sourceDigests": dict(sorted(catalog.files.items())),
        "entries": entries,
    }
    rendered = json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False, default=list) + "\n"
    if args.check:
        if OUTPUT.read_bytes() != rendered.encode("utf-8"):
            parser.error("Bundled contract differs from the verified sources")
    else:
        temporary = OUTPUT.with_suffix(".json.tmp")
        temporary.write_text(rendered, encoding="utf-8", newline="\n")
        temporary.replace(OUTPUT)
    print("Verified Webapp operations:", json.dumps(coverage, sort_keys=True))


if __name__ == "__main__":
    main()
