package cn.xhs.hermespocket;

import static cn.xhs.hermespocket.HermesText.t;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;

import com.facebook.react.bridge.BaseActivityEventListener;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Pattern;

/** Runs catalog operations and handles user-selected documents without passing credentials to JS. */
public final class HermesWorkspaceModule extends ReactContextBaseJavaModule {
    private static final int PICK = 7341;
    private static final int SAVE = 7342;
    private static final int TEXT_LIMIT = 256 * 1024;
    private static final int CATALOG_LIMIT = 8 * 1024 * 1024;
    private static final int JSON_DEPTH_LIMIT = 64;
    private static final String DEFAULT_MIME = "application/octet-stream";
    private static final Pattern SECRET_FIELD = Pattern.compile(
            ".*(password|secret|api_?key|access_?token|refresh_?token|bearer|cookie|credential|"
                    + "authorization|private_?key|socket_?ticket).*");

    private final ExecutorService worker = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ReactApplicationContext context;
    private final ConnectionStore store;
    private final Runnable stopObservingLogin;
    private final Object catalogLock = new Object();

    private WorkspaceCatalog contract;
    private String catalogSource;
    private String catalogJson;
    private String catalogLocale;
    private Session session;
    private volatile boolean closed;
    // Document picker state belongs to the UI thread.
    private DocumentRequest pendingDocument;

    private final BaseActivityEventListener documents = new BaseActivityEventListener() {
        @Override
        public void onActivityResult(Activity activity, int code, int status, Intent intent) {
            onDocumentResult(code, status, intent);
        }
    };

    public HermesWorkspaceModule(ReactApplicationContext context) {
        super(context);
        this.context = context;
        store = new ConnectionStore(context);
        stopObservingLogin = store.observeLoginChanges(() -> main.post(this::discardStaleSession));
        context.addActivityEventListener(documents);
    }

    @Override
    public String getName() {
        return "HermesWorkspace";
    }

    @ReactMethod
    public void catalog(Promise promise) {
        defer(promise, null, () -> {
            synchronized (catalogLock) {
                WorkspaceCatalog parsed = contract();
                String locale = HermesLocaleStore.languageTag(context);
                if (!locale.equals(catalogLocale)) {
                    catalogJson = presentedCatalog(parsed, locale);
                    catalogLocale = locale;
                }
                return catalogJson;
            }
        });
    }

    private WorkspaceCatalog contract() throws IOException, JSONException {
        synchronized (catalogLock) {
            if (contract != null) {
                return contract;
            }
            byte[] data;
            try (InputStream input = context.getAssets().open(WorkspaceCatalog.ASSET_NAME)) {
                data = read(input, CATALOG_LIMIT, t("native.could_not_read_the_workspace_catalog"));
            }
            contract = WorkspaceCatalog.load(new ByteArrayInputStream(data));
            catalogSource = new String(data, StandardCharsets.UTF_8);
            return contract;
        }
    }

    private String presentedCatalog(WorkspaceCatalog parsed, String locale) throws JSONException {
        JSONArray entries = new JSONObject(catalogSource).getJSONArray("entries");
        for (int index = 0; index < entries.length(); index++) {
            JSONObject row = entries.getJSONObject(index);
            WorkspaceCatalog.Entry entry = parsed.find(row.getString("id"));
            WorkspaceCatalogLabels.present(context, row, locale);
            String reason = WorkspaceClient.unsupportedReason(entry);
            if (reason != null) {
                row.put("unavailable", reason);
            }
            JSONObject constraints = row.getJSONObject("constraints");
            constraints.put("confirmationRequired", WorkspaceClient.requiresConfirmation(entry));
            if (WorkspaceClient.hasPartialInput(entry)) {
                constraints.put("partialInput", true);
            }
            if (WorkspaceClient.CREATE_KANBAN_TASK.equals(entry.id)) {
                row.put("description", t("native.create_a_triage_task_on_mobile_set_triage_true_to"));
                JSONArray fields = row.getJSONObject("params").getJSONArray("body");
                for (int fieldIndex = fields.length() - 1; fieldIndex >= 0; fieldIndex--) {
                    JSONObject field = fields.getJSONObject(fieldIndex);
                    String name = field.getString("name");
                    if ("assignee".equals(name)) {
                        fields.remove(fieldIndex);
                    } else if ("triage".equals(name)) {
                        field.put("required", true).put("default", true);
                    }
                }
            } else if (WorkspaceClient.CREATE_GROUP.equals(entry.id)) {
                row.put("description", t("native.choose_2_to_6_profiles_on_the_same_hermes_gateway"));
            } else if (WorkspaceClient.SEND_GROUP_MESSAGE.equals(entry.id)) {
                row.put("description", t("native.send_a_plain_text_message_event_id_identifies_the_send"));
                JSONArray fields = row.getJSONObject("params").getJSONArray("body");
                for (int fieldIndex = 0; fieldIndex < fields.length(); fieldIndex++) {
                    JSONObject field = fields.getJSONObject(fieldIndex);
                    if ("event_id".equals(field.getString("name"))) {
                        field.put("required", true).put("nullable", false);
                        break;
                    }
                }
            } else if ("rpc:config.set".equals(entry.id)) {
                row.put("description", t("native.change_public_reasoning_appearance_or_voice_settings_if_a_conversation"));
            } else if ("rpc:skills.manage".equals(entry.id)) {
                row.put("description", t("native.list_browse_or_search_skills_using_action_list_browse_or"));
            } else if ("rpc:learning.frames".equals(entry.id)) {
                row.put("description", t("native.for_learning_frames_enter_cols_20_80_rows_10_24"));
            } else if ("rpc:session.export".equals(entry.id)) {
                row.put("description", t("native.set_redact_true_to_export_a_redacted_conversation_as_json"));
            }
            row.remove("source");
        }
        return new JSONObject().put("entries", entries).toString();
    }

    @ReactMethod
    public void execute(String id, String raw, boolean confirmed, Promise promise) {
        ConnectionStore.LoginScope scope = loginFor(promise);
        if (scope != null) {
            defer(promise, scope, () -> execute(scope, id, raw, confirmed));
        }
    }

    @ReactMethod
    public void listManagedFiles(String path, Promise promise) {
        ConnectionStore.LoginScope scope = loginFor(promise);
        if (scope == null) {
            return;
        }
        defer(promise, scope, () -> {
            WorkspaceCatalog directory = contract();
            WorkspaceClient.Result result = connection(scope, directory).execute(WorkspaceClient.MANAGED_FILES,
                    null, new JSONObject().put("path", path), null, null, false);
            requireCurrent(scope);
            requireSuccess(result);
            Object listing = readJson(directory.find(WorkspaceClient.MANAGED_FILES), result);
            if (!(listing instanceof JSONObject)) {
                throw new BridgeFailure("RESPONSE_FORMAT", t("native.hermes_returned_an_invalid_file_listing"));
            }
            Object filtered = WorkspaceClient.visibleManagedListing((JSONObject) listing, path);
            return boundedJson(redact(filtered));
        });
    }

    @ReactMethod
    public void previewManagedFile(String path, Promise promise) {
        ConnectionStore.LoginScope scope = loginFor(promise);
        if (scope != null) {
            defer(promise, scope, () -> {
                WorkspaceClient.Result result = connection(scope, contract()).previewManagedFile(path);
                requireSuccess(result);
                return result.text();
            });
        }
    }

    @ReactMethod
    public void openConversation(String profile, String sessionId, Promise promise) {
        ConnectionStore.LoginScope scope = loginFor(promise);
        if (scope == null) {
            return;
        }
        main.post(() -> {
            try {
                requireCurrent(scope);
                Activity activity = context.getCurrentActivity();
                if (!(activity instanceof WorkspaceActivity) || activity.isFinishing()) {
                    throw new BridgeFailure("NO_ACTIVITY", t("native.return_to_the_workspace"));
                }
                MainActivity.openConversation(activity, scope.url, profile, sessionId);
                promise.resolve(true);
            } catch (BridgeFailure error) {
                promise.reject(error.code, error.getMessage());
            } catch (IllegalArgumentException error) {
                promise.reject("INVALID_SESSION", t("native.this_project_conversation_lacks_a_valid_conversation_or_profile"));
            } catch (RuntimeException error) {
                promise.reject("WORKSPACE", t("native.could_not_open_this_project_conversation"));
            }
        });
    }

    private String execute(ConnectionStore.LoginScope scope, String id, String raw, boolean confirmed)
            throws IOException, JSONException {
        WorkspaceCatalog directory = contract();
        WorkspaceCatalog.Entry entry = directory.find(id);
        String reason = WorkspaceClient.unsupportedReason(entry);
        if (reason != null) {
            throw new BridgeFailure("UNAVAILABLE", reason);
        }
        if (raw == null || raw.length() > TEXT_LIMIT || raw.getBytes(StandardCharsets.UTF_8).length > TEXT_LIMIT) {
            throw new BridgeFailure("INPUT_TOO_LARGE", t("native.the_request_text_is_limited_to_256_kib_attachments_can"));
        }
        checkJsonDepth(raw);
        JSONObject args = new JSONObject(raw);
        List<WorkspaceClient.Attachment> files = attachments(scope, entry, args.optString("attachment", null));
        WorkspaceClient transport = connection(scope, directory);
        WorkspaceClient.Result result = transport.execute(id, object(args, "path"), object(args, "query"),
                object(args, "body"), files, confirmed);
        requireCurrent(scope);
        if (result.statusCode == 404 && WorkspaceClient.isBundledPlugin(entry)) {
            throw new BridgeFailure("NOT_FOUND", t("native.hermes_could_not_find_the_kanban_or_achievement_resource_check"));
        }
        requireSuccess(result);
        return resultJson(scope, entry, result);
    }

    private static void requireSuccess(WorkspaceClient.Result result) throws BridgeFailure {
        if (!result.successful()) {
            throw new BridgeFailure("WORKSPACE", result.rpcCode == null
                    ? t("native.hermes_returned_http", result.statusCode)
                    : t("native.hermes_returned_rpc_error", result.rpcCode));
        }
    }

    private List<WorkspaceClient.Attachment> attachments(ConnectionStore.LoginScope scope,
            WorkspaceCatalog.Entry entry, String handle) throws IOException {
        if (handle == null) {
            return Collections.emptyList();
        }
        Upload upload = upload(scope, handle);
        WorkspaceCatalog.Field field = entry.bodyFields.stream().filter(WorkspaceCatalog.Field::isFile)
                .findFirst().orElse(null);
        if (field == null) {
            throw new BridgeFailure("FILE", t("native.this_action_does_not_accept_attachments"));
        }
        ContentResolver resolver = context.getContentResolver();
        try (InputStream input = resolver.openInputStream(upload.uri)) {
            if (input == null) {
                throw new BridgeFailure("FILE", t("native.could_not_read_the_selected_file"));
            }
            int limit = WorkspaceClient.attachmentLimit(entry);
            byte[] data = read(input, limit, t("native.the_attachment_cannot_exceed", limit / (1024 * 1024)));
            requireCurrent(scope);
            String type = resolver.getType(upload.uri);
            return Collections.singletonList(new WorkspaceClient.Attachment(field.name, upload.name,
                    type == null ? DEFAULT_MIME : type, data));
        }
    }

    private String resultJson(ConnectionStore.LoginScope scope, WorkspaceCatalog.Entry entry,
            WorkspaceClient.Result result) throws IOException, JSONException {
        JSONObject envelope = new JSONObject();
        if ("binary".equals(entry.responseFormat) && (!"rpc".equals(entry.kind) || result.fileName != null)) {
            Download download = new Download(result);
            synchronized (this) {
                requireSession(scope).download = download;
            }
            return envelope.put("fileHandle", download.handle).put("fileName", download.name).toString();
        }
        if (result.data.length == 0) {
            return envelope.put("value", new JSONObject().put("ok", true)).toString();
        }
        Object value = readJson(entry, result);
        if (WorkspaceClient.OAUTH_PROVIDER_INVENTORY.equals(entry.id)) {
            value = oauthInventory(value);
        }
        return boundedJson(envelope.put("value", redact(value)));
    }

    private static JSONObject oauthInventory(Object value) throws JSONException, BridgeFailure {
        if (!(value instanceof JSONObject)) {
            throw new BridgeFailure("RESPONSE_FORMAT", t("native.hermes_returned_an_invalid_provider_list"));
        }
        JSONArray source = ((JSONObject) value).getJSONArray("providers");
        JSONArray providers = new JSONArray();
        for (int index = 0; index < source.length(); index++) {
            JSONObject record = source.optJSONObject(index);
            if (record == null || !(record.opt("id") instanceof String)
                    || !(record.opt("name") instanceof String) || !(record.opt("flow") instanceof String)) {
                continue;
            }
            JSONObject status = record.optJSONObject("status");
            providers.put(new JSONObject()
                    .put("id", record.optString("id"))
                    .put("name", record.optString("name"))
                    .put("flow", record.optString("flow"))
                    .put("disconnectable", record.optBoolean("disconnectable"))
                    .put("logged_in", status != null && status.optBoolean("logged_in")));
        }
        return new JSONObject().put("providers", providers);
    }

    private static Object readJson(WorkspaceCatalog.Entry entry, WorkspaceClient.Result result)
            throws IOException, JSONException {
        String media = result.contentType == null ? "" : result.contentType.toLowerCase(Locale.ROOT);
        boolean jsonResponse = media.contains("/json") || media.contains("+json")
                || (media.isEmpty() && "json".equals(entry.responseFormat));
        if (!jsonResponse) {
            throw new BridgeFailure("RESPONSE_FORMAT", t("native.this_response_cannot_be_previewed_securely_view_it_in_hermes"));
        }
        if (result.data.length > TEXT_LIMIT) {
            throw resultTooLarge();
        }
        String text = result.text();
        checkJsonDepth(text);
        Object value = new JSONTokener(text).nextValue();
        if (value instanceof String) {
            throw new BridgeFailure("RESPONSE_FORMAT", t("native.this_raw_text_cannot_be_previewed_securely_view_it_in"));
        }
        return value;
    }

    private static String boundedJson(Object value) throws BridgeFailure {
        String summary = value.toString();
        if (summary.length() > TEXT_LIMIT || summary.getBytes(StandardCharsets.UTF_8).length > TEXT_LIMIT) {
            throw resultTooLarge();
        }
        return summary;
    }

    private static BridgeFailure resultTooLarge() {
        return new BridgeFailure("RESULT_TOO_LARGE", t("native.hermes_returned_a_result_larger_than_256_kib_view_it"));
    }

    private static JSONObject object(JSONObject source, String name) {
        JSONObject value = source.optJSONObject(name);
        return value == null ? new JSONObject() : value;
    }

    /** Limits nested containers before passing text to Android's recursive JSON parser. */
    private static void checkJsonDepth(String text) throws BridgeFailure {
        int depth = 0;
        char quote = 0;
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (quote != 0) {
                if (character == '\\') {
                    index++;
                } else if (character == quote) {
                    quote = 0;
                }
            } else if (character == '"' || character == '\'') {
                quote = character;
            } else if (character == '[' || character == '{') {
                if (++depth > JSON_DEPTH_LIMIT) {
                    throw new BridgeFailure("JSON", t("native.the_hermes_response_json_is_nested_too_deeply"));
                }
            } else if (character == ']' || character == '}') {
                depth = Math.max(0, depth - 1);
            }
        }
    }

    private static Object redact(Object value) throws JSONException {
        if (value instanceof JSONObject) {
            JSONObject input = (JSONObject) value;
            JSONObject output = new JSONObject();
            Iterator<String> keys = input.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                String normalized = key.toLowerCase(Locale.ROOT).replace('-', '_');
                boolean secret = "token".equals(normalized) || "ticket".equals(normalized)
                        || SECRET_FIELD.matcher(normalized).matches();
                output.put(key, secret ? "•••" : redact(input.get(key)));
            }
            return output;
        }
        if (value instanceof JSONArray) {
            JSONArray input = (JSONArray) value;
            JSONArray output = new JSONArray();
            for (int index = 0; index < input.length(); index++) {
                output.put(redact(input.get(index)));
            }
            return output;
        }
        return value;
    }

    @ReactMethod
    public void pickAttachment(Promise promise) {
        openDocument(null, promise);
    }

    @ReactMethod
    public void saveFile(String handle, Promise promise) {
        if (handle == null || handle.isEmpty()) {
            promise.reject("MISSING", t("native.download_the_file_again"));
            return;
        }
        openDocument(handle, promise);
    }

    private void openDocument(String handle, Promise promise) {
        ConnectionStore.LoginScope scope = loginFor(promise);
        if (scope == null) {
            return;
        }
        main.post(() -> {
            if (pendingDocument != null) {
                promise.reject("BUSY", t("native.finish_choosing_the_current_file"));
                return;
            }
            Activity activity = context.getCurrentActivity();
            if (!(activity instanceof WorkspaceActivity) || activity.isFinishing()) {
                promise.reject("NO_ACTIVITY", t("native.return_to_the_workspace"));
                return;
            }
            try {
                requireCurrent(scope);
                Download download = handle == null ? null : download(scope, handle);
                DocumentRequest request = new DocumentRequest(scope, download, promise);
                Intent intent = new Intent(download == null ? Intent.ACTION_OPEN_DOCUMENT
                        : Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType(download == null ? "*/*" : DEFAULT_MIME);
                if (download != null) {
                    intent.putExtra(Intent.EXTRA_TITLE, download.name);
                }
                pendingDocument = request;
                activity.startActivityForResult(intent, request.code());
            } catch (BridgeFailure error) {
                promise.reject(error.code, error.getMessage());
            } catch (RuntimeException error) {
                pendingDocument = null;
                promise.reject("FILE", t("native.could_not_open_the_file_picker"));
            }
        });
    }

    private void onDocumentResult(int code, int status, Intent intent) {
        DocumentRequest request = pendingDocument;
        if (request == null || code != request.code()) {
            return;
        }
        pendingDocument = null;
        if (status != Activity.RESULT_OK || intent == null || intent.getData() == null) {
            request.promise.reject("CANCELLED", t("native.selection_canceled"));
            return;
        }
        Uri uri = intent.getData();
        defer(request.promise, request.scope, () -> {
            if (!ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
                throw new BridgeFailure("FILE", t("native.choose_a_system_document"));
            }
            if (request.download == null) {
                return rememberUpload(request.scope, uri);
            }
            saveDownload(request.scope, request.download, uri);
            return true;
        });
    }

    private String rememberUpload(ConnectionStore.LoginScope scope, Uri uri) throws IOException, JSONException {
        String name = "attachment";
        try (Cursor cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                String selectedName = cursor.getString(0);
                if (selectedName != null && !selectedName.isEmpty() && selectedName.length() <= 255) {
                    name = selectedName;
                }
            }
        }
        Upload upload = new Upload(uri, name);
        synchronized (this) {
            requireSession(scope).upload = upload;
        }
        return new JSONObject().put("id", upload.handle).put("name", upload.name).toString();
    }

    private void saveDownload(ConnectionStore.LoginScope scope, Download download, Uri destination)
            throws IOException {
        requireCurrent(scope);
        try (OutputStream output = context.getContentResolver().openOutputStream(destination, "wt")) {
            if (output == null) {
                throw new BridgeFailure("FILE", t("native.could_not_save_the_file"));
            }
            byte[] bytes = download.result.data;
            for (int offset = 0; offset < bytes.length;) {
                requireCurrent(scope);
                int length = Math.min(bytes.length - offset, 64 * 1024);
                output.write(bytes, offset, length);
                offset += length;
            }
        }
    }

    private synchronized Upload upload(ConnectionStore.LoginScope scope, String handle) throws BridgeFailure {
        Upload value = requireSession(scope).upload;
        if (value == null || !handle.equals(value.handle)) {
            throw new BridgeFailure("MISSING", t("native.choose_the_attachment_again"));
        }
        return value;
    }

    private synchronized Download download(ConnectionStore.LoginScope scope, String handle) throws BridgeFailure {
        Download value = requireSession(scope).download;
        if (value == null || !handle.equals(value.handle)) {
            throw new BridgeFailure("MISSING", t("native.download_the_file_again"));
        }
        return value;
    }

    private synchronized WorkspaceClient connection(ConnectionStore.LoginScope scope, WorkspaceCatalog directory)
            throws BridgeFailure {
        Session current = requireSession(scope);
        if (current.client == null) {
            current.client = new WorkspaceClient(scope.url, store.authFor(scope), directory);
        }
        return current.client;
    }

    private ConnectionStore.LoginScope loginFor(Promise promise) {
        synchronized (this) {
            ConnectionStore.LoginScope active = discardStaleSession();
            if (active != null && !closed) {
                return active;
            }
        }
        promise.reject("SIGN_IN", t("native.sign_in_from_connection_settings_first"));
        return null;
    }

    private synchronized ConnectionStore.LoginScope discardStaleSession() {
        ConnectionStore.LoginScope active = store.activeLogin();
        if (session != null && (closed || !session.scope.equals(active))) {
            session = null;
        }
        return active;
    }

    private synchronized Session requireSession(ConnectionStore.LoginScope scope) throws BridgeFailure {
        requireCurrent(scope);
        if (session == null) {
            session = new Session(scope);
        }
        return session;
    }

    private void requireCurrent(ConnectionStore.LoginScope scope) throws BridgeFailure {
        if (closed || !scope.equals(discardStaleSession())) {
            throw new BridgeFailure("STALE_LOGIN", t("native.the_hermes_login_changed_reopen_the_workspace"));
        }
    }

    @ReactMethod
    public void closeWorkspace() {
        Activity activity = context.getCurrentActivity();
        if (activity instanceof WorkspaceActivity) {
            activity.runOnUiThread(activity::finish);
        }
    }

    private interface Work {
        Object run() throws IOException, JSONException;
    }

    private void defer(Promise promise, ConnectionStore.LoginScope scope, Work work) {
        try {
            worker.execute(() -> {
                try {
                    if (scope != null) {
                        requireCurrent(scope);
                    }
                    Object result = work.run();
                    if (scope != null) {
                        requireCurrent(scope);
                    }
                    promise.resolve(result);
                } catch (BridgeFailure error) {
                    promise.reject(error.code, error.getMessage());
                } catch (ConnectionStore.SignInRequiredException error) {
                    promise.reject("SIGN_IN", t("native.your_hermes_session_expired_sign_in_again"));
                } catch (HermesClient.WorkspaceReadException error) {
                    promise.reject("NETWORK", error.getMessage());
                } catch (JSONException error) {
                    promise.reject("JSON", t("native.invalid_hermes_action_parameters_or_response_format"));
                } catch (IllegalArgumentException | IllegalStateException error) {
                    String detail = error.getMessage();
                    if (!(error instanceof HermesText.MessageError) || detail == null) {
                        detail = t("native.the_action_failed_check_the_parameters");
                    }
                    promise.reject("WORKSPACE", detail);
                } catch (IOException | RuntimeException error) {
                    promise.reject("WORKSPACE", t("native.the_action_failed_check_the_connection_or_file_permissions"));
                }
            });
        } catch (RejectedExecutionException error) {
            promise.reject("WORKSPACE", t("native.the_workspace_closed_open_it_again"));
        }
    }

    private static byte[] read(InputStream input, int limit, String message) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int length; (length = input.read(buffer)) != -1;) {
            if (length > limit - result.size()) {
                throw new BridgeFailure("SIZE_LIMIT", message);
            }
            result.write(buffer, 0, length);
        }
        return result.toByteArray();
    }

    @Override
    public void invalidate() {
        closed = true;
        stopObservingLogin.run();
        context.removeActivityEventListener(documents);
        main.post(() -> {
            if (pendingDocument != null) {
                pendingDocument.promise.reject("WORKSPACE", t("native.the_workspace_closed_open_it_again"));
                pendingDocument = null;
            }
        });
        discardStaleSession();
        worker.shutdownNow();
        super.invalidate();
    }

    private static final class Session {
        final ConnectionStore.LoginScope scope;
        WorkspaceClient client;
        Upload upload;
        Download download;

        Session(ConnectionStore.LoginScope scope) {
            this.scope = scope;
        }
    }

    private static final class Upload {
        final String handle = UUID.randomUUID().toString();
        final Uri uri;
        final String name;

        Upload(Uri uri, String name) {
            this.uri = uri;
            this.name = name;
        }
    }

    private static final class Download {
        final String handle = UUID.randomUUID().toString();
        final WorkspaceClient.Result result;
        final String name;

        Download(WorkspaceClient.Result result) {
            this.result = result;
            String fileName = result.fileName;
            name = fileName == null || fileName.isEmpty() || fileName.length() > 255
                    ? "hermes-download" : fileName;
        }
    }

    private static final class DocumentRequest {
        final ConnectionStore.LoginScope scope;
        final Download download;
        final Promise promise;

        DocumentRequest(ConnectionStore.LoginScope scope, Download download, Promise promise) {
            this.scope = scope;
            this.download = download;
            this.promise = promise;
        }

        int code() {
            return download == null ? PICK : SAVE;
        }
    }

    private static final class BridgeFailure extends IOException {
        final String code;

        BridgeFailure(String code, String message) {
            super(message);
            this.code = code;
        }
    }
}
