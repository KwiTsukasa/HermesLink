package cn.xhs.hermespocket;

import static cn.xhs.hermespocket.HermesText.t;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Runs one authenticated operation from the bundled directory on a worker thread. */
public final class WorkspaceClient {
    static final String MANAGED_FILES = "rest:GET:/api/files";
    static final String MANAGED_DOWNLOAD = "rest:GET:/api/files/download";
    // Even when every character requires JSON escaping, the preview fits the 256 KiB bridge limit.
    static final int FILE_PREVIEW_BYTES = 32 * 1024;
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final MediaType TEXT = MediaType.get("text/plain; charset=utf-8");
    private static final int MAX_BODY_BYTES = 32 * 1024 * 1024;
    private static final int KANBAN_ATTACHMENT_BYTES = 25 * 1024 * 1024;
    private static final int MAX_DEPTH = 40;
    private static final long DUPLICATE_WINDOW_NANOS = TimeUnit.SECONDS.toNanos(3);
    private static final RequestBody EMPTY = RequestBody.create(new byte[0], null);
    private static final Set<String> HTTP_METHODS = names("GET", "HEAD", "OPTIONS", "POST",
            "PUT", "PATCH", "DELETE");
    private static final Set<String> BODY_METHODS = names("POST", "PUT", "PATCH", "DELETE");
    private static final Set<String> STALE_CONNECTION_READS = names("rest:GET:/api/model/info",
            "rest:GET:/api/analytics/models", MANAGED_FILES, MANAGED_DOWNLOAD);
    private static final Set<String> EXTERNAL_KEYS = names("url", "uri", "origin", "host",
            "hostname", "endpoint", "webhook", "baseurl", "apiurl", "proxy", "address",
            "proxyurl", "endpointurl", "serverurl", "serveruri", "remoteurl", "remoteuri",
            "sourceurl", "downloadurl", "uploadurl", "targeturl", "redirecturl", "callbackurl",
            "webhookurl", "apiendpoint", "apibase", "baseuri", "serveraddress");
    private static final Set<String> FILE_KEYS = names("path", "filepath", "filename", "folder",
            "directory", "directorypath", "folderpath", "workspacepath", "relativepath",
            "subpath", "attachmentpath", "cwd", "archive", "workdir", "rootpath", "workspace", "primarypath",
            "folders");
    private static final Set<String> SECRET_FILE_PARTS = names(".ssh", ".netrc", ".aws", ".gnupg",
            ".docker", "credentials.json", "secrets.json", "auth.json", ".git-credentials",
            "credential_pool.json", "id_rsa", "id_ed25519");
    private static final Set<String> STREAM_FORMATS = names("sse", "event-stream", "websocket", "stream", "html");
    private static final Set<String> REQUEST_TYPES = names("", "application/json", "text/plain",
            "multipart/form-data", "application/x-www-form-urlencoded");
    private static final Set<String> BUNDLED_PLUGIN_ROUTES = names(
            "rest:GET:/api/plugins/hermes-achievements/achievements",
            "rest:GET:/api/plugins/hermes-achievements/recent-unlocks",
            "rest:GET:/api/plugins/hermes-achievements/scan-status",
            "rest:GET:/api/plugins/hermes-achievements/sessions/{session_id}/badges",
            "rest:POST:/api/plugins/hermes-achievements/rescan",
            "rest:POST:/api/plugins/hermes-achievements/reset-state",
            "rest:GET:/api/plugins/kanban/assignees",
            "rest:GET:/api/plugins/kanban/attachments/{attachment_id}",
            "rest:GET:/api/plugins/kanban/board",
            "rest:GET:/api/plugins/kanban/boards",
            "rest:GET:/api/plugins/kanban/config",
            "rest:GET:/api/plugins/kanban/diagnostics",
            "rest:GET:/api/plugins/kanban/home-channels",
            "rest:GET:/api/plugins/kanban/model-options",
            "rest:GET:/api/plugins/kanban/orchestration",
            "rest:GET:/api/plugins/kanban/profiles",
            "rest:GET:/api/plugins/kanban/projects",
            "rest:GET:/api/plugins/kanban/runs/{run_id}",
            "rest:GET:/api/plugins/kanban/stats",
            "rest:GET:/api/plugins/kanban/tasks/{task_id}",
            "rest:GET:/api/plugins/kanban/tasks/{task_id}/attachments",
            "rest:GET:/api/plugins/kanban/workers/active",
            "rest:GET:/api/plugins/kanban/workflow",
            "rest:POST:/api/plugins/kanban/boards",
            "rest:POST:/api/plugins/kanban/boards/{slug}/switch",
            "rest:POST:/api/plugins/kanban/links",
            "rest:POST:/api/plugins/kanban/tasks",
            "rest:POST:/api/plugins/kanban/tasks/{task_id}/attachments",
            "rest:POST:/api/plugins/kanban/tasks/{task_id}/comments",
            "rest:POST:/api/plugins/kanban/tasks/{task_id}/home-subscribe/{platform}",
            "rest:POST:/api/plugins/kanban/tasks/{task_id}/estimate",
            "rest:POST:/api/plugins/kanban/tasks/{task_id}/reassign",
            "rest:POST:/api/plugins/kanban/tasks/{task_id}/reclaim",
            "rest:POST:/api/plugins/kanban/tasks/bulk",
            "rest:POST:/api/plugins/kanban/estimate",
            "rest:PATCH:/api/plugins/kanban/boards/{slug}",
            "rest:PATCH:/api/plugins/kanban/profiles/{profile_name}",
            "rest:PATCH:/api/plugins/kanban/tasks/{task_id}",
            "rest:DELETE:/api/plugins/kanban/attachments/{attachment_id}",
            "rest:DELETE:/api/plugins/kanban/boards/{slug}",
            "rest:DELETE:/api/plugins/kanban/links",
            "rest:DELETE:/api/plugins/kanban/tasks/{task_id}",
            "rest:DELETE:/api/plugins/kanban/tasks/{task_id}/home-subscribe/{platform}");
    static final String CREATE_KANBAN_TASK = "rest:POST:/api/plugins/kanban/tasks";
    static final String CREATE_GROUP = "rpc:groups.create";
    static final String SEND_GROUP_MESSAGE = "rpc:groups.send";
    private static final String REDACTED_SESSION_EXPORT = "rpc:session.export";
    static final String OAUTH_PROVIDER_INVENTORY = "rest:GET:/api/providers/oauth";
    private static final Set<String> OAUTH_MANAGEMENT_ROUTES = names(OAUTH_PROVIDER_INVENTORY,
            "rest:DELETE:/api/providers/oauth/{provider_id}");
    private static final String UPLOAD_KANBAN_ATTACHMENT = "rest:POST:/api/plugins/kanban/tasks/{task_id}/attachments";
    private static final Set<String> HOSTED_GROUP_CONTROLS = names("groups.create", "groups.send", "groups.continue",
            "groups.retry", "groups.discard");
    private static final Set<String> GROUP_MEMBER_FIELDS = names("member_id", "profile", "handle", "display_name",
            "target");
    private static final Set<String> GROUP_TARGET_FIELDS = names("kind", "profile");
    private static final Set<String> GROUP_TEXT_FIELDS = names("thread_id", "text");
    private static final int MAX_GROUP_MESSAGE_BYTES = 64 * 1024;
    private static final Set<String> PROFILE_SETTINGS = names("reasoning", "fast", "personality", "daybreak",
            "skin", "theme", "indicator", "details_mode", "display.message_reactions", "display.in_app_tips",
            "display.in_app_tours", "voice.voice_chat_mode");
    private static final Set<String> SKILL_LOOKUP_ACTIONS = names("list", "browse", "search");
    private static final Set<String> RPC_LIMITED_INPUTS = names(REDACTED_SESSION_EXPORT, "rpc:config.set",
            "rpc:skills.manage", "rpc:learning.frames", "rpc:pet.thumb", "rpc:profiles.get_asset");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z0-9_]+)(:path)?\\}");
    private static final Pattern DISPOSITION_NAME = Pattern.compile(
            "(?:^|;)\\s*filename=(?:\"([^\"]*)\"|([^;]*))", Pattern.CASE_INSENSITIVE);
    private static final Pattern DISPOSITION_UTF8 = Pattern.compile(
            "(?:^|;)\\s*filename\\*=utf-8'[^']*'([^;]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PREVIEW_RANGE = Pattern.compile("bytes 0-([0-9]+)/([0-9]+)",
            Pattern.CASE_INSENSITIVE);

    private final HttpUrl server;
    private final WorkspaceCatalog catalog;
    private final HermesClient hermes;
    private final Set<String> activeMutations = new HashSet<>();
    private final Map<String, Long> recentMutations = new HashMap<>();

    public WorkspaceClient(String server, HermesClient.Auth auth, WorkspaceCatalog catalog) {
        this(server, auth, catalog, false);
    }

    WorkspaceClient(String server, HermesClient.Auth auth, WorkspaceCatalog catalog, boolean loopbackTest) {
        this.server = validateServer(server, loopbackTest);
        this.catalog = Objects.requireNonNull(catalog);
        hermes = new HermesClient(this.server.toString(), Objects.requireNonNull(auth));
    }

    public static final class Attachment {
        public final String field;
        public final String fileName;
        public final String contentType;
        private final byte[] data;

        public Attachment(String field, String fileName, String contentType, byte[] data) {
            this.field = Objects.requireNonNull(field);
            validatePathValue(fileName, false);
            if (fileName.contains("\"") || data.length > MAX_BODY_BYTES) {
                throw new HermesText.InvalidInput("native.invalid_attachment_name_or_size");
            }
            this.fileName = fileName;
            this.contentType = contentType == null || contentType.isEmpty()
                    ? "application/octet-stream" : MediaType.get(contentType).toString();
            this.data = data.clone();
        }
    }

    public static final class Result {
        public final int statusCode;
        public final Integer rpcCode;
        public final String contentType;
        public final String fileName;
        public final byte[] data;

        private Result(int statusCode, Integer rpcCode, String contentType, String fileName, byte[] data) {
            this.statusCode = statusCode;
            this.rpcCode = rpcCode;
            this.contentType = contentType;
            this.fileName = fileName;
            this.data = data;
        }

        public boolean successful() {
            return rpcCode == null && statusCode >= 200 && statusCode < 300;
        }

        public String text() {
            MediaType type = MediaType.parse(contentType);
            return new String(data, type == null ? StandardCharsets.UTF_8
                    : type.charset(StandardCharsets.UTF_8));
        }

        public Object json() throws JSONException {
            return new JSONTokener(text()).nextValue();
        }
    }

    /** Supply only keys defined on the selected operation. Confirmation is scoped to this execution. */
    public Result execute(String id, JSONObject path, JSONObject query, JSONObject payload,
            List<Attachment> files, boolean confirmed) throws IOException {
        WorkspaceCatalog.Entry entry = catalog.find(id);
        String reason = unsupportedReason(entry);
        if (reason != null) {
            throw new IllegalArgumentException(reason);
        }
        boolean mutating = isMutation(entry);
        if (requiresConfirmation(entry) && !confirmed) {
            throw new HermesText.InvalidInput("native.confirm_this_hermes_action_first");
        }
        try {
            JSONObject pathValues = path == null ? new JSONObject() : path;
            JSONObject queryValues = query == null ? new JSONObject() : query;
            JSONObject bodyValues = payload == null ? new JSONObject() : payload;
            List<Attachment> attachments = files == null ? Collections.emptyList() : new ArrayList<>(files);
            Map<String, List<Attachment>> filesByField = groupAttachments(entry, attachments);
            validateFields(entry.pathFields, pathValues, Collections.emptyMap());
            validateFields(entry.queryFields, queryValues, Collections.emptyMap());
            validateFields(entry.bodyFields, bodyValues, filesByField);
            if (!entry.kind.equals("rpc")) {
                if (CREATE_KANBAN_TASK.equals(entry.id)
                        && (!Boolean.TRUE.equals(bodyValues.opt("triage")) || !bodyValues.isNull("assignee"))) {
                    throw new HermesText.InvalidInput("native.create_a_triage_task_on_mobile_set_triage_true_and");
                }
                HttpUrl url = buildRestUrl(entry, pathValues, queryValues);
                RequestBody request = buildRestBody(entry, bodyValues, filesByField);
                if (request != null && request.contentLength() > MAX_BODY_BYTES) {
                    throw new HermesText.InvalidInput("native.the_request_body_exceeds_32_mib");
                }
                return withMutationGuard(entry, mutating, pathValues, queryValues, bodyValues, attachments,
                        () -> executeRest(entry, url,
                                mutating && request != null ? HermesClient.singleUse(request) : request));
            }
            Object suppliedProfile = queryValues.isNull("profile")
                    ? bodyValues.opt("profile") : queryValues.opt("profile");
            String profile = suppliedProfile instanceof String ? (String) suppliedProfile : "";
            validateGroupWrite(entry, bodyValues);
            validateSpecialRpcInput(entry, bodyValues);
            if (needsAttachment(entry, bodyValues)) {
                validatePathValue(bodyValues.optString("session_id", ""), false);
            }
            if (bodyValues.toString().getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
                throw new HermesText.InvalidInput("native.the_request_body_exceeds_32_mib");
            }
            return withMutationGuard(entry, mutating, pathValues, queryValues, bodyValues, attachments,
                    () -> executeRpc(entry, profile, bodyValues));
        } catch (JSONException error) {
            throw new HermesText.Failure(error, "native.invalid_hermes_json_parameters_or_response");
        }
    }

    static JSONObject visibleManagedListing(JSONObject listing, String requestedPath) throws JSONException {
        String directory = listing.getString("path");
        validateFilePath(directory);
        String normalized = directory.replace('\\', '/');
        if ((!normalized.startsWith("/") && !normalized.matches("^[A-Za-z]:/.*"))
                || (requestedPath != null && !normalized.equals(requestedPath.replace('\\', '/')))) {
            throw new HermesText.InvalidInput("native.the_file_directory_changed_go_back_and_reopen_it");
        }
        String prefix = normalized.endsWith("/") ? normalized : normalized + "/";
        JSONArray entries = listing.getJSONArray("entries");
        JSONArray children = new JSONArray();
        for (int index = 0; index < entries.length(); index++) {
            JSONObject item = entries.optJSONObject(index);
            if (item == null || !(item.opt("name") instanceof String) || !(item.opt("path") instanceof String)
                    || !(item.opt("is_directory") instanceof Boolean) || item.optBoolean("broken_link")) {
                continue;
            }
            String name = item.getString("name");
            try {
                validatePathValue(name, false);
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            if ((prefix + name).equals(item.getString("path").replace('\\', '/'))) {
                children.put(item);
            }
        }
        return listing.put("entries", children);
    }

    Result previewManagedFile(String path) throws IOException, JSONException {
        WorkspaceCatalog.Entry entry = catalog.find(MANAGED_DOWNLOAD);
        String reason = unsupportedReason(entry);
        if (reason != null) {
            throw new IllegalArgumentException(reason);
        }
        if (path == null || path.trim().isEmpty()) {
            throw new HermesText.InvalidInput("native.invalid_file_path");
        }
        JSONObject query = new JSONObject().put("path", path);
        validateFields(entry.queryFields, query, Collections.emptyMap());
        HttpUrl url = buildRestUrl(entry, new JSONObject(), query);
        try (Response response = hermes.executeWorkspacePrefixRequest(url, FILE_PREVIEW_BYTES)) {
            String range = response.header("Content-Range", "");
            boolean emptyFile = response.code() == 416 && ("bytes */0".equalsIgnoreCase(range) || "*/0".equals(range));
            if (!response.isSuccessful() && !emptyFile) {
                return new Result(response.code(), null, "", null, new byte[0]);
            }
            JSONObject preview = emptyFile ? readTextPreview(null, 0) : readPreviewResponse(response, range);
            return new Result(200, null, "application/json", null,
                    preview.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static JSONObject readPreviewResponse(Response response, String range) throws IOException, JSONException {
        responseContentType(response);
        if (response.code() == 200) {
            return readTextPreview(response.body(), -1);
        }
        if (response.code() == 206) {
            Matcher coordinates = PREVIEW_RANGE.matcher(range);
            if (coordinates.matches()) {
                try {
                    long end = Long.parseLong(coordinates.group(1));
                    long total = Long.parseLong(coordinates.group(2));
                    if (end == Math.min(total - 1, FILE_PREVIEW_BYTES)) {
                        return readTextPreview(response.body(), (int) end + 1);
                    }
                } catch (NumberFormatException ignored) {
                    // Invalid range sizes cannot describe a valid preview.
                }
            }
        }
        throw new HermesText.Failure("native.hermes_returned_an_invalid_file_preview");
    }

    private static JSONObject readTextPreview(ResponseBody body, int expectedLength) throws IOException, JSONException {
        byte[] prefix = new byte[FILE_PREVIEW_BYTES + 1];
        int size = 0;
        if (body != null) {
            InputStream input = body.byteStream();
            while (size < prefix.length) {
                int count = input.read(prefix, size, prefix.length - size);
                if (count < 0) {
                    break;
                }
                size += count;
            }
        }
        if (expectedLength >= 0 && size != expectedLength) {
            throw new HermesText.Failure("native.hermes_returned_an_incomplete_file_preview");
        }
        boolean truncated = size > FILE_PREVIEW_BYTES;
        int length = Math.min(size, FILE_PREVIEW_BYTES);
        CharBuffer characters = CharBuffer.allocate(length);
        // A bounded prefix can end partway through a character. A complete response must decode fully.
        boolean binary = StandardCharsets.UTF_8.newDecoder()
                .decode(ByteBuffer.wrap(prefix, 0, length), characters, !truncated).isError();
        characters.flip();
        if (characters.hasRemaining() && characters.get(0) == '\uFEFF') {
            characters.get();
        }
        String text = characters.toString();
        binary = binary || text.codePoints().anyMatch(character -> Character.isISOControl(character)
                && character != '\t' && character != '\n' && character != '\r' && character != '\f');
        return new JSONObject().put("binary", binary).put("text", binary ? "" : text)
                .put("truncated", truncated);
    }

    private static HttpUrl validateServer(String value, boolean loopbackTest) {
        try {
            URI raw = new URI(value == null ? "" : value.trim());
            if (raw.getHost() == null || raw.getRawUserInfo() != null || raw.getRawQuery() != null
                    || raw.getRawFragment() != null || raw.getPort() == 0) {
                throw new HermesText.InvalidInput("native.enter_a_valid_hermes_webapp_address");
            }
            String prefix = raw.getPath();
            if (prefix != null && prefix.length() > 1) {
                if (raw.getRawPath().matches("(?i).*%(?:2f|5c|25|3f|23).*")) {
                    throw new HermesText.InvalidInput("native.invalid_path_in_the_hermes_address");
                }
                validatePathValue(prefix.substring(1).replaceFirst("/+$", ""), true);
            }
        } catch (URISyntaxException error) {
            throw new IllegalArgumentException(t("native.enter_a_valid_hermes_webapp_address"), error);
        }
        HttpUrl server = HttpUrl.get(HermesClient.normalizeBaseUrl(value));
        boolean testHost = loopbackTest && (server.host().equals("127.0.0.1") || server.host().equals("::1")
                || server.host().equals("localhost"));
        if (!server.isHttps() && !HermesClient.isTailscaleAddress(server.host()) && !testHost) {
            throw new HermesText.InvalidInput("native.http_connections_require_a_private_tailscale_address");
        }
        return server;
    }

    public static String unsupportedReason(WorkspaceCatalog.Entry entry) {
        if (entry == null) {
            return t("native.this_action_is_unavailable_on_mobile");
        }
        if (entry.sensitiveResponse) {
            return t("native.this_endpoint_may_return_credentials");
        }
        boolean providerManagement = isOAuthManagement(entry);
        boolean eligible = "remote".equals(entry.access) || isSessionBound(entry) || isBundledPlugin(entry)
                || providerManagement;
        if (!eligible) {
            return t("native.this_action_is_unavailable_on_mobile");
        }
        if ("rpc".equals(entry.kind)) {
            if (!entry.path.equals("/api/ws") || !entry.method.matches("[a-zA-Z][a-zA-Z0-9_.]+")
                    || !entry.pathFields.isEmpty() || entry.bodyFields.stream().anyMatch(WorkspaceCatalog.Field::isFile)
                    || entry.queryFields.stream().anyMatch(field -> !field.name.equals("profile"))) {
                return t("native.this_gateway_action_requires_a_dedicated_connection");
            }
        } else if (!"rest".equals(entry.kind) || !HTTP_METHODS.contains(entry.method)
                || !entry.path.startsWith("/api/") || entry.path.contains("?") || entry.path.contains("#")) {
            return t("native.this_action_is_unavailable_on_mobile");
        }
        String target = entry.path.toLowerCase(Locale.ROOT);
        if (target.equals("/api/profiles/import") || target.equals("/api/profiles/{name}/export")) {
            return t("native.import_or_export_from_the_profiles_page_using_the_system");
        }
        if (target.startsWith("/api/webapp/") || target.startsWith("/api/auth/")
                || target.equals("/api/files/ticket")
                || target.equals("/api/env/reveal") || target.equals("/api/ops/backup/download")
                || (target.startsWith("/api/providers/oauth/") && !providerManagement)
                || target.matches(".*(?:^|/)(?:credentials|secrets|tokens|api[-_]keys)(?:/.*)?$")) {
            return t("native.this_endpoint_handles_credentials_or_authorization");
        }
        if ((target.equals("/api/config") || target.equals("/api/config/raw")) && entry.method.equals("GET")) {
            return t("native.the_full_configuration_may_contain_credentials");
        }
        if (entry.headerFields.stream().anyMatch(field -> field.required)
                || STREAM_FORMATS.contains(entry.responseFormat.toLowerCase(Locale.ROOT))
                || (!entry.kind.equals("rpc") && !REQUEST_TYPES.contains(requestType(entry)))) {
            return t("native.this_action_requires_a_dedicated_connection");
        }
        return null;
    }

    public static boolean requiresConfirmation(WorkspaceCatalog.Entry entry) {
        return entry != null && (entry.confirmationRequired || isMutation(entry));
    }

    static boolean isBundledPlugin(WorkspaceCatalog.Entry entry) {
        return "rest".equals(entry.kind) && "conditional".equals(entry.access)
                && "webapp_session".equals(entry.auth) && BUNDLED_PLUGIN_ROUTES.contains(entry.id);
    }

    static boolean isOAuthManagement(WorkspaceCatalog.Entry entry) {
        return "rest".equals(entry.kind) && "browser".equals(entry.access)
                && "webapp_session".equals(entry.auth) && OAUTH_MANAGEMENT_ROUTES.contains(entry.id);
    }

    static boolean isSessionBound(WorkspaceCatalog.Entry entry) {
        return "rpc".equals(entry.kind) && "special_transport".equals(entry.access)
                && "webapp_gateway_ticket".equals(entry.auth) && "required".equals(entry.sessionBinding);
    }

    static boolean hasPartialInput(WorkspaceCatalog.Entry entry) {
        return CREATE_KANBAN_TASK.equals(entry.id) || CREATE_GROUP.equals(entry.id) || SEND_GROUP_MESSAGE.equals(entry.id)
                || RPC_LIMITED_INPUTS.contains(entry.id);
    }

    private static void validateSpecialRpcInput(WorkspaceCatalog.Entry entry, JSONObject body) {
        if ("config.set".equals(entry.method) && !PROFILE_SETTINGS.contains(body.optString("key", ""))) {
            throw new HermesText.InvalidInput("native.on_mobile_you_can_change_model_reasoning_appearance_and_voice");
        }
        if ("skills.manage".equals(entry.method)
                && !SKILL_LOOKUP_ACTIONS.contains(body.optString("action", "list"))) {
            throw new HermesText.InvalidInput("native.this_entry_point_supports_listing_browsing_and_searching_skills");
        }
        if ("learning.frames".equals(entry.method) && (body.optInt("cols", 0) < 20
                || body.optInt("cols", 0) > 80 || body.optInt("rows", 0) < 10 || body.optInt("rows", 0) > 24
                || body.optInt("frames", 0) < 2 || body.optInt("frames", 0) > 12)) {
            throw new HermesText.InvalidInput("native.learning_frames_require_explicit_dimensions_of_20_80_cols_10");
        }
        if ("complete.path".equals(entry.method)) {
            validateFilePath(body.optString("word", ""));
        }
        if ("projects.record_repos".equals(entry.method)) {
            JSONArray repos = body.optJSONArray("repos");
            if (repos != null) {
                for (int index = 0; index < repos.length(); index++) {
                    Object repo = repos.opt(index);
                    if (repo instanceof String) {
                        validateFilePath((String) repo);
                    } else if (repo instanceof JSONObject && ((JSONObject) repo).opt("root") instanceof String) {
                        validateFilePath(((JSONObject) repo).optString("root"));
                    }
                }
            }
        }
        if (REDACTED_SESSION_EXPORT.equals(entry.id) && !Boolean.TRUE.equals(body.opt("redact"))) {
            throw new HermesText.InvalidInput("native.select_redact_true_to_export_a_conversation");
        }
        if ("pet.thumb".equals(entry.method) && !body.optString("url", "").isEmpty()) {
            throw new HermesText.InvalidInput("native.choose_an_installed_pet_thumbnail");
        }
        if ("profiles.get_asset".equals(entry.method) && !body.isNull("asset")
                && !"avatar".equals(body.optString("asset", ""))) {
            throw new HermesText.InvalidInput("native.this_entry_point_reads_a_profile_avatar");
        }
    }

    private static void validateGroupWrite(WorkspaceCatalog.Entry entry, JSONObject body) throws JSONException {
        if (CREATE_GROUP.equals(entry.id)) {
            JSONArray members = body.getJSONArray("members");
            if (members.length() < 2 || members.length() > 6) {
                throw new HermesText.InvalidInput("native.a_group_chat_requires_2_to_6_profiles");
            }
            for (int index = 0; index < members.length(); index++) {
                JSONObject member = members.getJSONObject(index);
                JSONObject target = member.optJSONObject("target");
                if (!GROUP_MEMBER_FIELDS.containsAll(keys(member))
                        || !(member.opt("member_id") instanceof String)
                        || !(member.opt("profile") instanceof String)
                        || !(member.opt("handle") instanceof String)
                        || (!member.isNull("target") && (target == null
                            || !GROUP_TARGET_FIELDS.equals(new HashSet<>(keys(target)))
                            || !"local".equals(target.opt("kind"))
                            || !member.get("profile").equals(target.opt("profile"))))) {
                    throw new HermesText.InvalidInput("native.choose_valid_group_members_on_the_same_gateway");
                }
            }
        } else if (SEND_GROUP_MESSAGE.equals(entry.id)) {
            JSONObject payload = body.getJSONObject("payload");
            Object text = payload.opt("text");
            Object thread = payload.opt("thread_id");
            if (!GROUP_TEXT_FIELDS.equals(new HashSet<>(keys(payload))) || !(text instanceof String)
                    || ((String) text).trim().isEmpty()
                    || ((String) text).getBytes(StandardCharsets.UTF_8).length > MAX_GROUP_MESSAGE_BYTES
                    || !(thread instanceof String) || ((String) thread).trim().isEmpty()
                    || body.optString("event_id", "").trim().isEmpty()) {
                throw new HermesText.InvalidInput("native.group_chats_support_plain_text_provide_a_message_and_thread");
            }
        }
    }

    private static boolean needsAttachment(WorkspaceCatalog.Entry entry, JSONObject params) {
        return isSessionBound(entry) || ("optional".equals(entry.sessionBinding) && !params.isNull("session_id")
                && !params.optString("session_id", "").isEmpty());
    }

    private static boolean isMutation(WorkspaceCatalog.Entry entry) {
        return entry.mutating || BODY_METHODS.contains(entry.method);
    }

    private static Set<String> names(String... values) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(values)));
    }

    static int attachmentLimit(WorkspaceCatalog.Entry entry) {
        return UPLOAD_KANBAN_ATTACHMENT.equals(entry.id) ? KANBAN_ATTACHMENT_BYTES : MAX_BODY_BYTES;
    }

    private static Map<String, List<Attachment>> groupAttachments(WorkspaceCatalog.Entry entry,
            List<Attachment> attachments) {
        Map<String, List<Attachment>> grouped = new HashMap<>();
        long byteCount = 0;
        int limit = attachmentLimit(entry);
        for (Attachment attachment : attachments) {
            byteCount += attachment.data.length;
            if (byteCount > limit) {
                throw new HermesText.InvalidInput("native.attachments_together_exceed", limit / (1024 * 1024));
            }
            grouped.computeIfAbsent(attachment.field, ignored -> new ArrayList<>()).add(attachment);
        }
        return grouped;
    }

    private void validateFields(List<WorkspaceCatalog.Field> fields, JSONObject values,
            Map<String, List<Attachment>> attachments) throws JSONException {
        Set<String> unknown = new HashSet<>(keys(values));
        unknown.addAll(attachments.keySet());
        for (WorkspaceCatalog.Field field : fields) {
            unknown.remove(field.name);
            List<Attachment> files = attachments.get(field.name);
            if (field.isFile()) {
                if (values.has(field.name) || (field.required && files == null)
                        || (files != null && !field.isFileArray() && files.size() != 1)) {
                    throw new HermesText.InvalidInput("native.invalid_attachment_field", field.name);
                }
            } else if (files != null || (field.required && !values.has(field.name))) {
                throw new HermesText.InvalidInput("native.missing_or_invalid_parameter", field.name);
            } else if (values.has(field.name)) {
                Object value = values.get(field.name);
                validateType(field.name, field.type, field.nullable, value);
                validateEnum(field.name, field.schema, value);
                inspectValue(field.name, value, 0);
            }
        }
        if (!unknown.isEmpty()) {
            throw new HermesText.InvalidInput("native.the_request_contains_parameters_not_defined_in_the_hermes_catalog");
        }
    }

    private static void validateType(String field, String type, boolean nullable, Object value) {
        if (value == JSONObject.NULL) {
            if (nullable) {
                return;
            }
            throw new HermesText.InvalidInput("native.parameter_cannot_be_empty", field);
        }
        boolean valid;
        switch (type) {
            case "any":
                valid = true;
                break;
            case "string":
                valid = value instanceof String;
                break;
            case "integer":
                valid = value instanceof Number && Double.isFinite(((Number) value).doubleValue())
                        && new BigDecimal(value.toString()).stripTrailingZeros().scale() <= 0;
                break;
            case "number":
                valid = value instanceof Number && Double.isFinite(((Number) value).doubleValue());
                break;
            case "boolean":
                valid = value instanceof Boolean;
                break;
            case "object":
                valid = value instanceof JSONObject;
                break;
            case "array":
                valid = value instanceof JSONArray;
                break;
            default:
                valid = false;
        }
        if (!valid) {
            throw new HermesText.InvalidInput("native.incorrect_parameter_type", field);
        }
    }

    private void inspectValue(String field, Object value, int depth) throws JSONException {
        if (depth > MAX_DEPTH) {
            throw new HermesText.InvalidInput("native.the_parameters_are_nested_too_deeply");
        }
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            for (Iterator<String> keys = object.keys(); keys.hasNext();) {
                String key = keys.next();
                inspectValue(key, object.get(key), depth + 1);
            }
        } else if (value instanceof JSONArray) {
            JSONArray items = (JSONArray) value;
            for (int index = 0; index < items.length(); index++) {
                inspectValue(field, items.get(index), depth + 1);
            }
        } else if (value instanceof String && !((String) value).trim().isEmpty()) {
            String name = field.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
            String text = (String) value;
            if (EXTERNAL_KEYS.contains(name)) {
                validateDestination(text);
            }
            if (name.equals("filename")) {
                validatePathValue(text, false);
            } else if (FILE_KEYS.contains(name)) {
                validateFilePath(text);
            }
        }
    }

    private static void validateEnum(String field, JSONObject schema, Object value) {
        JSONArray options = schema == null ? null : schema.optJSONArray("enum");
        if (options == null || value == JSONObject.NULL) {
            return;
        }
        for (int index = 0; index < options.length(); index++) {
            if (value.equals(options.opt(index))) {
                return;
            }
        }
        throw new HermesText.InvalidInput("native.parameter_value_is_not_allowed_by_hermes", field);
    }

    private void validateDestination(String value) {
        try {
            URI raw = new URI(value);
            if (raw.getHost() == null || raw.getRawUserInfo() != null) {
                throw new HermesText.InvalidInput("native.the_address_parameter_must_share_the_current_hermes_origin");
            }
        } catch (URISyntaxException error) {
            throw new IllegalArgumentException(t("native.the_address_parameter_must_share_the_current_hermes_origin"), error);
        }
        HttpUrl target = HttpUrl.parse(value);
        if (target == null || !server.scheme().equals(target.scheme()) || !server.host().equals(target.host())
                || server.port() != target.port() || !target.username().isEmpty()
                || !target.password().isEmpty() || target.fragment() != null || target.query() != null) {
            throw new HermesText.InvalidInput("native.the_address_parameter_must_share_the_current_hermes_origin");
        }
    }

    private HttpUrl buildRestUrl(WorkspaceCatalog.Entry entry, JSONObject path, JSONObject query)
            throws JSONException {
        HttpUrl.Builder url = server.newBuilder();
        Set<String> consumedFields = new HashSet<>();
        String[] segments = entry.path.substring(1).split("/", -1);
        for (int index = 0; index < segments.length; index++) {
            String segment = segments[index];
            Matcher placeholder = PLACEHOLDER.matcher(segment);
            if (placeholder.matches()) {
                String key = placeholder.group(1);
                String value = scalar(path.get(key));
                boolean nested = placeholder.group(2) != null;
                validatePathValue(value, nested);
                for (String part : value.split("/", -1)) {
                    url.addPathSegment(part);
                }
                consumedFields.add(key);
            } else {
                if (!(segment.isEmpty() && index == segments.length - 1)
                        && !segment.matches("[A-Za-z0-9_~.-]+")) {
                    throw new HermesText.InvalidInput("native.the_hermes_catalog_contains_a_route_that_cannot_be_called");
                }
                if (segment.equals(".") || segment.equals("..")) {
                    throw new HermesText.InvalidInput("native.the_hermes_catalog_contains_a_route_that_cannot_be_called");
                }
                url.addPathSegment(segment);
            }
        }
        if (!consumedFields.equals(new HashSet<>(keys(path)))) {
            throw new HermesText.InvalidInput("native.invalid_hermes_path_parameter");
        }
        for (WorkspaceCatalog.Field field : entry.queryFields) {
            if (query.isNull(field.name)) {
                continue;
            }
            Object value = query.get(field.name);
            for (String item : scalarValues(value)) {
                url.addQueryParameter(field.name, item);
            }
        }
        return url.build();
    }

    private static void validatePathValue(String path, boolean nested) {
        if (path == null || path.trim().isEmpty() || path.contains("\\") || path.contains("?") || path.contains("#")
                || path.contains("%") || path.contains(":") || (!nested && path.contains("/"))
                || path.codePoints().anyMatch(Character::isISOControl)) {
            throw new HermesText.InvalidInput("native.invalid_path_or_name");
        }
        checkPathParts(path);
    }

    static void validateFilePath(String path) {
        String normalized = path.replace('\\', '/');
        if (normalized.startsWith("//") || normalized.contains("%") || normalized.contains("?")
                || normalized.contains("#") || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw new HermesText.InvalidInput("native.invalid_file_path");
        }
        if (normalized.matches("^[A-Za-z]:/.*")) {
            normalized = normalized.substring(2);
        }
        if (normalized.contains(":")) {
            throw new HermesText.InvalidInput("native.invalid_file_path");
        }
        String relative = normalized.replaceFirst("^/", "").replaceFirst("/$", "");
        if (!relative.isEmpty()) {
            checkPathParts(relative);
        }
    }

    private static void checkPathParts(String path) {
        for (String part : path.split("/", -1)) {
            String normalized = part.toLowerCase(Locale.ROOT).trim();
            if (normalized.isEmpty() || normalized.equals(".") || normalized.equals("..")
                    || normalized.equals(".env") || normalized.startsWith(".env.")
                    || part.endsWith(".") || part.endsWith(" ")
                    || SECRET_FILE_PARTS.contains(normalized)) {
                throw new HermesText.InvalidInput("native.this_path_or_name_is_unavailable_on_mobile");
            }
        }
    }

    private static String requestType(WorkspaceCatalog.Entry entry) {
        return entry.contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }

    private static RequestBody buildRestBody(WorkspaceCatalog.Entry entry, JSONObject body,
            Map<String, List<Attachment>> attachments) throws JSONException {
        if (!BODY_METHODS.contains(entry.method)) {
            if (body.length() > 0 || !attachments.isEmpty()) {
                throw new HermesText.InvalidInput("native.this_action_does_not_accept_a_request_body");
            }
            return null;
        }
        String contentType = requestType(entry);
        switch (contentType) {
            case "multipart/form-data":
                MultipartBody.Builder multipart = new MultipartBody.Builder().setType(MultipartBody.FORM);
                for (WorkspaceCatalog.Field field : entry.bodyFields) {
                    if (!body.isNull(field.name)) {
                        for (String item : scalarValues(body.get(field.name))) {
                            multipart.addFormDataPart(field.name, item);
                        }
                    }
                    for (Attachment file : attachments.getOrDefault(field.name, Collections.emptyList())) {
                        multipart.addFormDataPart(field.name, file.fileName,
                                RequestBody.create(file.data, MediaType.get(file.contentType)));
                    }
                }
                if (body.length() == 0 && attachments.isEmpty()) {
                    throw new HermesText.InvalidInput("native.the_request_form_is_empty");
                }
                return multipart.build();
            case "application/x-www-form-urlencoded":
                requireNoAttachments(attachments);
                FormBody.Builder form = new FormBody.Builder();
                for (WorkspaceCatalog.Field field : entry.bodyFields) {
                    if (!body.isNull(field.name)) {
                        for (String item : scalarValues(body.get(field.name))) {
                            form.add(field.name, item);
                        }
                    }
                }
                return form.build();
            case "text/plain":
                requireNoAttachments(attachments);
                return requestBody(body.getString("$body"), TEXT);
            case "":
                if (entry.bodyFields.isEmpty()) {
                    return EMPTY;
                }
                // Webapp JSON handlers can declare fields without explicitly setting a media type.
            case "application/json":
                requireNoAttachments(attachments);
                Object value = body.has("$body") ? body.get("$body") : body;
                String json = value instanceof String ? JSONObject.quote((String) value) : value.toString();
                return requestBody(json, JSON);
            default:
                throw new HermesText.InvalidInput("native.this_request_body_requires_a_dedicated_transport");
        }
    }

    private static void requireNoAttachments(Map<String, List<Attachment>> attachments) {
        if (!attachments.isEmpty()) {
            throw new HermesText.InvalidInput("native.this_action_does_not_accept_attachments");
        }
    }

    private static RequestBody requestBody(String data, MediaType type) {
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BODY_BYTES) {
            throw new HermesText.InvalidInput("native.the_request_body_exceeds_32_mib");
        }
        return RequestBody.create(bytes, type);
    }

    private static List<String> scalarValues(Object value) throws JSONException {
        if (!(value instanceof JSONArray)) {
            return Collections.singletonList(scalar(value));
        }
        JSONArray array = (JSONArray) value;
        List<String> result = new ArrayList<>(array.length());
        for (int index = 0; index < array.length(); index++) {
            result.add(scalar(array.get(index)));
        }
        return result;
    }

    private static String scalar(Object value) {
        if (value instanceof String || value instanceof Boolean || value instanceof Number) {
            return value.toString();
        }
        throw new HermesText.InvalidInput("native.form_and_query_parameters_must_be_strings_numbers_or_booleans");
    }

    private Result executeRest(WorkspaceCatalog.Entry entry, HttpUrl url, RequestBody body) throws IOException {
        boolean recoverStaleConnection = !isMutation(entry) && STALE_CONNECTION_READS.contains(entry.id);
        try (Response response = hermes.executeWorkspaceRequest(url, entry.method, body, "*/*",
                recoverStaleConnection)) {
            return new Result(response.code(), null, responseContentType(response),
                    fileName(response.header("Content-Disposition")), readBody(response.body()));
        }
    }

    private static String responseContentType(Response response) throws IOException {
        String contentType = response.header("Content-Type", "");
        if (contentType.toLowerCase(Locale.ROOT).startsWith("text/event-stream")) {
            throw new HermesText.Failure("native.this_action_requires_a_live_event_connection");
        }
        return contentType;
    }

    private Result executeRpc(WorkspaceCatalog.Entry entry, String profile, JSONObject params)
            throws IOException, JSONException {
        try {
            Object result;
            if (needsAttachment(entry, params)) {
                result = hermes.executeAttachedWorkspaceRpc(entry.method, profile, params);
            } else if (HOSTED_GROUP_CONTROLS.contains(entry.method)) {
                result = hermes.executeHostedGroupControl(entry.method, profile, params);
            } else {
                result = hermes.executeWorkspaceRpc(entry.method, profile, params);
            }
            if (REDACTED_SESSION_EXPORT.equals(entry.id)) {
                return sessionExport(result);
            }
            if ("binary".equals(entry.responseFormat)) {
                Result file = rpcFile(entry.method, result);
                if (file != null) {
                    return file;
                }
                result = fieldsOnly(result, "ok", "found", "suppressed", "slug");
            }
            result = projectRpcResponse(entry, result);
            String json = result instanceof String ? JSONObject.quote((String) result) : result.toString();
            byte[] data = json.getBytes(StandardCharsets.UTF_8);
            if (data.length > MAX_BODY_BYTES) {
                throw new HermesText.Failure("native.the_hermes_response_exceeds_32_mib");
            }
            return new Result(200, null, "application/json", null, data);
        } catch (HermesClient.RpcException error) {
            JSONObject body = new JSONObject().put("message", error.getMessage()).put("code", error.code);
            return new Result(200, error.code, "application/json", null,
                    body.toString().getBytes(StandardCharsets.UTF_8));
        } catch (HermesClient.HttpException error) {
            return new Result(error.statusCode, null, "text/plain; charset=utf-8", null,
                    error.getMessage().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static Result sessionExport(Object value) throws IOException, JSONException {
        if (!(value instanceof JSONObject)) {
            throw new HermesText.Failure("native.hermes_returned_an_invalid_conversation_export");
        }
        JSONObject response = (JSONObject) value;
        String name = response.getString("filename");
        validatePathValue(name, false);
        Object content = response.opt("content");
        String format = response.getString("format");
        if (!(content instanceof String) || !names("json", "md", "html").contains(format)) {
            throw new HermesText.Failure("native.hermes_returned_an_invalid_conversation_export");
        }
        byte[] data = ((String) content).getBytes(StandardCharsets.UTF_8);
        if (data.length > MAX_BODY_BYTES) {
            throw new HermesText.Failure("native.the_hermes_response_exceeds_32_mib");
        }
        return new Result(200, null, "application/octet-stream", name, data);
    }

    private static Result rpcFile(String method, Object value) throws IOException, JSONException {
        if (!(value instanceof JSONObject)) {
            throw new HermesText.Failure("native.hermes_returned_an_invalid_file");
        }
        JSONObject result = (JSONObject) value;
        switch (method) {
            case "display.thumbnail":
                return imageData(result, "data_url", "desktop", "image/jpeg");
            case "pet.thumb":
                return imageData(result, "dataUri", "pet", "image/png");
            case "profiles.get_asset":
                return imageData(result, "data", "avatar", null);
            case "pet.export":
                return base64File(result.getString("filename"), "application/zip", result.getString("zipBase64"));
            case "groups.attachment.download":
                return roomAttachment(result);
            default:
                throw new HermesText.Failure("native.hermes_returned_an_invalid_file");
        }
    }

    private static Result roomAttachment(JSONObject source) throws IOException, JSONException {
        Result file = base64File(source.getString("name"), source.getString("mime"), source.getString("data_base64"));
        String checksum = source.getString("sha256");
        if (source.getLong("size") != file.data.length || !checksum.equals(hexDigest(file.data))) {
            throw new HermesText.Failure("native.the_hermes_group_attachment_failed_verification");
        }
        return file;
    }

    private static String hexDigest(byte[] data) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder text = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                text.append(Character.forDigit((value >>> 4) & 15, 16));
                text.append(Character.forDigit(value & 15, 16));
            }
            return text.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static Result imageData(JSONObject result, String key, String name, String expectedMime)
            throws IOException {
        if (result.isNull(key)) {
            return null;
        }
        String data = result.optString(key, "");
        int marker = data.indexOf(";base64,");
        if (!data.startsWith("data:") || marker < 5) {
            throw new HermesText.Failure("native.hermes_returned_an_invalid_image");
        }
        String mime = data.substring(5, marker);
        String extension;
        switch (mime) {
            case "image/png":
                extension = ".png";
                break;
            case "image/jpeg":
                extension = ".jpg";
                break;
            case "image/webp":
                extension = ".webp";
                break;
            default:
                throw new HermesText.Failure("native.hermes_returned_an_invalid_image");
        }
        if (expectedMime != null && !expectedMime.equals(mime)) {
            throw new HermesText.Failure("native.hermes_returned_an_invalid_image");
        }
        return base64File(name + extension, mime, data.substring(marker + 8));
    }

    private static Result base64File(String name, String mime, String encoded) throws IOException {
        validatePathValue(name, false);
        if (encoded.length() > (MAX_BODY_BYTES + 2) / 3 * 4) {
            throw new HermesText.Failure("native.the_hermes_response_exceeds_32_mib");
        }
        byte[] data;
        try {
            data = java.util.Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException error) {
            throw new HermesText.Failure(error, "native.hermes_returned_an_invalid_file");
        }
        if (data.length > MAX_BODY_BYTES) {
            throw new HermesText.Failure("native.the_hermes_response_exceeds_32_mib");
        }
        return new Result(200, null, MediaType.parse(mime) == null ? "application/octet-stream" : mime, name, data);
    }

    private static Object projectRpcResponse(WorkspaceCatalog.Entry entry, Object value) throws JSONException {
        switch (entry.method) {
            case "process.list":
                return projectRows(value, "processes", "session_id", "status", "exit_code", "started_epoch",
                        "exited_at");
            case "process.kill":
                return fieldsOnly(value, "session_id", "status", "exit_code");
            case "mcp.servers.list":
                return projectRows(value, "servers", "name", "enabled", "transport", "auth", "oauth_tokens_present");
            case "mcp.servers.test":
                return fieldsOnly(value, "success", "ok", "status", "tools", "prompts", "resources");
            case "delegation.status":
                return fieldsOnly(value, "paused", "max_spawn_depth", "max_concurrent_children");
            case "free_tier.status":
                return fieldsOnly(value, "has_guest", "enabled", "available", "notice_pending", "model", "label");
            case "setup.status":
                return fieldsOnly(value, "ok", "provider_configured");
            case "setup.runtime_check":
                return fieldsOnly(value, "ok", "provider", "model", "source", "error_code", "free_tier_route",
                        "profile");
            default:
                return value;
        }
    }

    private static JSONObject projectRows(Object value, String key, String... fields) throws JSONException {
        if (!(value instanceof JSONObject)) {
            throw new JSONException("Invalid Hermes list");
        }
        JSONArray source = ((JSONObject) value).getJSONArray(key);
        JSONArray rows = new JSONArray();
        for (int index = 0; index < source.length(); index++) {
            rows.put(fieldsOnly(source.getJSONObject(index), fields));
        }
        return new JSONObject().put(key, rows);
    }

    private static JSONObject fieldsOnly(Object value, String... fields) throws JSONException {
        if (!(value instanceof JSONObject)) {
            throw new JSONException("Invalid Hermes object");
        }
        JSONObject input = (JSONObject) value;
        JSONObject output = new JSONObject();
        for (String field : fields) {
            Object actual = input.opt(field);
            if (actual == JSONObject.NULL || actual instanceof String || actual instanceof Boolean
                    || actual instanceof Number) {
                output.put(field, actual);
            }
        }
        return output;
    }

    private static byte[] readBody(ResponseBody body) throws IOException {
        if (body == null) {
            return new byte[0];
        }
        if (body.contentLength() > MAX_BODY_BYTES) {
            throw new HermesText.Failure("native.the_hermes_response_exceeds_32_mib");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        InputStream input = body.byteStream();
        byte[] chunk = new byte[8192];
        for (int count; (count = input.read(chunk)) != -1;) {
            if (count > MAX_BODY_BYTES - bytes.size()) {
                throw new HermesText.Failure("native.the_hermes_response_exceeds_32_mib");
            }
            bytes.write(chunk, 0, count);
        }
        return bytes.toByteArray();
    }

    private static String fileName(String header) {
        if (header == null) {
            return null;
        }
        Matcher encoded = DISPOSITION_UTF8.matcher(header);
        if (encoded.find()) {
            try {
                String name = URLDecoder.decode(encoded.group(1).trim().replace("+", "%2B"), "UTF-8");
                validatePathValue(name, false);
                return name;
            } catch (IOException | IllegalArgumentException ignored) {
                // Fall back to the ASCII name where the server supplied one.
            }
        }
        Matcher matcher = DISPOSITION_NAME.matcher(header);
        if (!matcher.find()) {
            return null;
        }
        String name = matcher.group(1) == null ? matcher.group(2).trim() : matcher.group(1);
        try {
            validatePathValue(name, false);
            return name;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private interface Operation {
        Result execute() throws IOException, JSONException;
    }

    private Result withMutationGuard(WorkspaceCatalog.Entry entry, boolean mutating, JSONObject path,
            JSONObject query, JSONObject body, List<Attachment> attachments, Operation operation)
            throws IOException, JSONException {
        if (!mutating) {
            return operation.execute();
        }
        String key = mutationKey(entry.id, path, query, body, attachments);
        synchronized (activeMutations) {
            long now = System.nanoTime();
            recentMutations.values().removeIf(expiry -> now - expiry >= 0);
            if (recentMutations.containsKey(key) || !activeMutations.add(key)) {
                throw new IllegalStateException(t("native.this_action_was_already_submitted_wait_or_refresh_the_result"));
            }
        }
        try {
            return operation.execute();
        } finally {
            synchronized (activeMutations) {
                activeMutations.remove(key);
                recentMutations.put(key, System.nanoTime() + DUPLICATE_WINDOW_NANOS);
            }
        }
    }

    private static String mutationKey(String id, JSONObject path, JSONObject query, JSONObject body,
            List<Attachment> attachments) throws JSONException {
        MessageDigest hash;
        try {
            hash = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
        StringBuilder key = new StringBuilder(id).append('\n');
        for (JSONObject value : Arrays.asList(path, query, body)) {
            appendCanonicalJson(key, value);
            key.append('\n');
        }
        hash.update(key.toString().getBytes(StandardCharsets.UTF_8));
        for (Attachment file : attachments) {
            hash.update((JSONObject.quote(file.field) + JSONObject.quote(file.fileName)
                    + JSONObject.quote(file.contentType) + file.data.length + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            hash.update(file.data);
        }
        return java.util.Base64.getEncoder().encodeToString(hash.digest());
    }

    private static void appendCanonicalJson(StringBuilder output, Object value) throws JSONException {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            List<String> keys = keys(object);
            keys.sort(Comparator.naturalOrder());
            output.append('{');
            for (String key : keys) {
                output.append(JSONObject.quote(key)).append(':');
                appendCanonicalJson(output, object.get(key));
                output.append(',');
            }
            output.append('}');
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            output.append('[');
            for (int index = 0; index < array.length(); index++) {
                appendCanonicalJson(output, array.get(index));
                output.append(',');
            }
            output.append(']');
        } else {
            output.append(value instanceof String ? JSONObject.quote((String) value) : value.toString());
        }
    }

    private static List<String> keys(JSONObject object) {
        List<String> names = new ArrayList<>(object.length());
        for (Iterator<String> keys = object.keys(); keys.hasNext();) {
            names.add(keys.next());
        }
        return names;
    }
}
