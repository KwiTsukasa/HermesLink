package cn.xhs.hermespocket;

import static cn.xhs.hermespocket.HermesText.t;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.EOFException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import okhttp3.Call;
import okhttp3.Connection;
import okhttp3.ConnectionPool;
import okhttp3.EventListener;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.BufferedSink;

/** Talks to the Hermes Webapp. Call the network methods from a worker thread. */
public final class HermesClient {
    private static final String GATEWAY_PROTOCOL = "hermes-gateway-v1";
    private static final int RPC_TIMEOUT_SECONDS = 30;
    private static final int STREAM_TIMEOUT_SECONDS = 180;
    private static final int CONVERSATION_LIMIT = 100;
    private static final int MESSAGE_LIMIT = 500;
    private static final int FILE_LIMIT = 16 * 1024 * 1024;
    private static final int IMAGE_LIMIT = 5 * 1024 * 1024;
    private static final RequestBody EMPTY_BODY = singleUse(RequestBody.create(new byte[0], null));
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .eventListenerFactory(call -> {
                ReadAttempt attempt = call.request().tag(ReadAttempt.class);
                return attempt == null ? EventListener.NONE : attempt;
            })
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(35, TimeUnit.SECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .build();
    private static final OkHttpClient DIRECT_HTTP = HTTP.newBuilder().proxy(Proxy.NO_PROXY).build();
    private static final OkHttpClient AUDIO_HTTP = HTTP.newBuilder()
            .readTimeout(90, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS).build();
    private static final OkHttpClient DIRECT_AUDIO_HTTP = AUDIO_HTTP.newBuilder()
            .proxy(Proxy.NO_PROXY).build();

    public interface Auth {
        String cookieHeader(String url) throws IOException;

        default String accessToken(String requestUrl) throws IOException {
            return null;
        }

        default void receiveCookies(String url, List<String> headers) {
        }
    }

    public static final class Conversation {
        public final String id;
        public final String key;
        public final String title;
        public final String preview;
        public final String profile;
        public final String source;
        public final long lastActiveMillis;
        public final boolean running;
        public final boolean archived;
        public final boolean pinned;
        private final HttpUrl server;

        private Conversation(HttpUrl server, JSONObject row, String id) {
            this.server = server;
            this.id = id;
            this.profile = text(row, "profile");
            this.source = text(row, "source");
            String root = text(row, "_lineage_root_id");
            if (root.isEmpty()) {
                root = text(row, "lineage_root");
            }
            this.key = profile + ":" + (root.isEmpty() ? id : root);
            String title = text(row, "title").trim();
            String snippet = text(row, "snippet");
            this.preview = (snippet.isEmpty() ? text(row, "preview") : snippet).trim();
            this.title = title;
            this.lastActiveMillis = (long) (row.optDouble("last_active",
                    row.optDouble("started_at", 0)) * 1000);
            this.running = row.optBoolean("running", false);
            this.archived = row.optBoolean("archived", false);
            this.pinned = row.optBoolean("pinned", false);
        }

        String displayTitle() {
            return title.isEmpty() ? t("native.untitled_conversation") : title;
        }
    }

    interface AttachmentSource {
        InputStream open() throws IOException;
    }

    static final class Attachment {
        final String name;
        final boolean image;
        private final AttachmentSource source;

        Attachment(String name, boolean image, AttachmentSource source) {
            String safeName = name == null ? "" : name.replaceAll("[\\p{Cntrl}/\\\\]", "_").trim();
            this.name = safeName.isEmpty() ? "attachment" : safeName;
            this.image = image;
            this.source = source;
        }
    }

    public static final class Message {
        public final String id;
        public final String role;
        public final String content;
        public final long timestampMillis;

        Message(String id, String role, String content, long timestampMillis) {
            this.id = id;
            this.role = role;
            this.content = content;
            this.timestampMillis = timestampMillis;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Message)) {
                return false;
            }
            Message message = (Message) other;
            return id.equals(message.id) && role.equals(message.role)
                    && content.equals(message.content) && timestampMillis == message.timestampMillis;
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, role, content, timestampMillis);
        }
    }

    static final class MessageHistory {
        final List<Message> messages;
        private final String tail;

        private MessageHistory(List<Message> messages, String tail) {
            this.messages = messages;
            this.tail = tail;
        }
    }

    static final class HistoryRead extends RequestRead {
    }

    static class GatewayRead extends RequestRead {
        private RpcSocket socket;

        @Override
        synchronized void cancel() {
            super.cancel();
            closeSocket();
        }

        final synchronized RpcSocket usableSocket() throws InterruptedIOException {
            checkCancelled();
            if (socket != null && socket.failure != null) {
                closeSocket();
            }
            return socket;
        }

        private synchronized void bindSocket(RpcSocket socket) throws InterruptedIOException {
            this.socket = socket;
            try {
                checkCancelled();
            } catch (InterruptedIOException error) {
                closeSocket();
                throw error;
            }
        }

        final synchronized void closeSocket() {
            if (socket != null) {
                socket.abort();
                socket = null;
            }
        }

        private synchronized void releaseSocket(RpcSocket finished) {
            if (socket == finished) {
                socket = null;
            }
        }
    }

    static final class ControlRead extends GatewayRead {
        private final Consumer<ControlRead> changed;
        private String sessionId;

        ControlRead() {
            this(null);
        }

        ControlRead(Consumer<ControlRead> changed) {
            this.changed = changed;
        }

        private synchronized void watch(String attachedId) {
            sessionId = attachedId;
        }

        private synchronized void event(JSONObject params) {
            if (changed == null || sessionId == null || !sessionId.equals(text(params, "session_id"))) {
                return;
            }
            String type = text(params, "type");
            JSONObject payload = params.optJSONObject("payload");
            if (type.equals("approval.request") || type.equals("approval.settled")
                    || type.equals("clarify.request") || type.equals("clarify.settled")
                    || type.equals("message.start") || type.equals("message.complete")
                    || type.equals("session.replay_gap")
                    || type.equals("session.info") && payload != null && payload.has("pending")) {
                changed.accept(this);
            }
        }
    }

    static class RequestRead {
        private Call call;
        private boolean cancelled;

        synchronized void cancel() {
            cancelled = true;
            if (call != null) {
                call.cancel();
            }
        }

        private synchronized Call bind(Call next) throws InterruptedIOException {
            checkCancelled();
            call = next;
            return next;
        }

        final synchronized void checkCancelled() throws InterruptedIOException {
            if (cancelled) {
                throw new InterruptedIOException("Hermes request cancelled");
            }
        }

        private synchronized void clear() {
            call = null;
        }
    }

    static final class StreamUpdate {
        final String text;
        final String userRowId;
        final String assistantRowId;
        final boolean completed;
        final boolean interrupted;
        final List<HermesReplyStream.ToolActivity> tools;

        StreamUpdate(String text, String userRowId, String assistantRowId,
                     boolean completed, boolean interrupted) {
            this(text, userRowId, assistantRowId, completed, interrupted, Collections.emptyList());
        }

        StreamUpdate(String text, String userRowId, String assistantRowId,
                     boolean completed, boolean interrupted, List<HermesReplyStream.ToolActivity> tools) {
            this.text = text;
            this.userRowId = userRowId;
            this.assistantRowId = assistantRowId;
            this.completed = completed;
            this.interrupted = interrupted;
            this.tools = tools;
        }
    }

    /** Owns one send's live preview. Its observer may be called on the send or WebSocket thread. */
    static final class SendRead {
        private static final int MAX_PENDING_FRAMES = 128;
        private static final int MAX_PENDING_CHARACTERS = 256 * 1024;
        private static final ScheduledThreadPoolExecutor DEADLINES = new ScheduledThreadPoolExecutor(
                1, action -> {
                    Thread thread = new Thread(action, "Hermes stream deadline");
                    thread.setDaemon(true);
                    return thread;
                });

        static {
            DEADLINES.setRemoveOnCancelPolicy(true);
        }

        private final Consumer<StreamUpdate> observer;
        private final RequestRead ticketRead = new RequestRead();
        private final List<JSONObject> pending = new ArrayList<>();
        private RpcSocket socket;
        private HermesReplyStream projection;
        private ScheduledFuture<?> deadline;
        private String sessionId;
        private String submissionId;
        private String replayEpoch;
        private long sequence;
        private int pendingCharacters;
        private boolean overflow;
        private boolean admitted;
        private boolean stopped;
        private boolean cancelled;

        SendRead(Consumer<StreamUpdate> observer) {
            this.observer = Objects.requireNonNull(observer);
        }

        synchronized void stopStreaming() {
            stopped = true;
            pending.clear();
            projection = null;
            if (deadline != null) {
                deadline.cancel(false);
                deadline = null;
            }
            // Keep the in-flight write's receipt available if the chat is hidden mid-submit.
            if (admitted && socket != null) {
                RpcSocket active = socket;
                socket = null;
                active.close();
            }
        }

        synchronized void cancel() {
            cancelled = true;
            ticketRead.cancel();
            RpcSocket active = socket;
            socket = null;
            stopStreaming();
            if (active != null) {
                active.abort();
            }
        }

        private synchronized void bind(RpcSocket next) throws InterruptedIOException {
            if (cancelled) {
                throw new InterruptedIOException("Hermes send cancelled");
            }
            socket = next;
        }

        private synchronized void prepare(JSONObject snapshot, String sessionId, String submissionId) {
            this.sessionId = sessionId;
            this.submissionId = submissionId;
            replayEpoch = text(snapshot, "replay_epoch");
            sequence = snapshot.optLong("last_sequence", -1);
        }

        private synchronized boolean acceptReceipt(SendReceipt receipt) {
            admitted = true;
            if (stopped) {
                return false;
            }
            projection = new HermesReplyStream(sessionId, submissionId, receipt.admissionId,
                    receipt.authorityEpoch, receipt.executionGeneration, replayEpoch, sequence);
            List<JSONObject> buffered = new ArrayList<>(pending);
            pending.clear();
            for (JSONObject params : buffered) {
                if (stopped) {
                    break;
                }
                publish(projection.accept(params));
            }
            if (!stopped && (overflow || socket.failure != null)) {
                publish(projection.interrupted());
            }
            if (!stopped && receipt.isTerminal()) {
                stopStreaming();
            }
            if (!stopped) {
                deadline = DEADLINES.schedule(this::disconnected,
                        STREAM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
            return !stopped;
        }

        private synchronized void event(JSONObject params, int frameCharacters) {
            if (stopped || sessionId == null || !HermesReplyStream.isReplyEvent(params, sessionId)) {
                return;
            }
            if (admitted) {
                publish(projection.accept(params));
            } else if (!overflow) {
                // Fast workers can emit part or all of a turn before the admission reply.
                if (pending.size() >= MAX_PENDING_FRAMES
                        || frameCharacters > MAX_PENDING_CHARACTERS - pendingCharacters) {
                    pending.clear();
                    overflow = true;
                } else {
                    pending.add(params);
                    pendingCharacters += frameCharacters;
                }
            }
        }

        private synchronized void disconnected() {
            if (!stopped && admitted) {
                publish(projection.interrupted());
            }
        }

        private void publish(StreamUpdate update) {
            if (update == null) {
                return;
            }
            try {
                observer.accept(update);
            } catch (RuntimeException observerError) {
                // The preview is optional. A consumer error must leave the accepted receipt usable.
                stopStreaming();
            }
            if (update.completed || update.interrupted) {
                stopStreaming();
            }
        }

        private synchronized void release(RpcSocket current) {
            if (socket == current) {
                socket = null;
                stopStreaming();
                current.close();
            }
        }
    }

    public static final class SendReceipt {
        public final String admissionId;
        public final String status;
        public final String outcome;
        final String submittedText;
        private final Conversation conversation;
        private final String sessionId;
        private final long authorityEpoch;
        private final long executionGeneration;

        private SendReceipt(JSONObject result, Conversation conversation, String sessionId)
                throws JSONException {
            this(result, conversation, sessionId, null);
        }

        private SendReceipt(JSONObject result, Conversation conversation, String sessionId,
                            String submittedText) throws JSONException {
            this.conversation = conversation;
            this.sessionId = sessionId;
            this.admissionId = result.getString("admission_id");
            this.status = result.getString("status");
            this.outcome = text(result, "outcome");
            this.submittedText = submittedText;
            this.authorityEpoch = result.optLong("authority_epoch", -1);
            this.executionGeneration = result.optLong("execution_generation", -1);
            if (admissionId.isEmpty() || !sessionId.equals(
                    result.getJSONObject("ref").getString("session_id"))) {
                throw new JSONException("Invalid Hermes admission identity");
            }
        }

        public boolean isTerminal() {
            return status.equals("terminal");
        }
    }

    public static final class HttpException extends IOException {
        public final int statusCode;

        private HttpException(int statusCode) {
            this.statusCode = statusCode;
        }

        @Override
        public String getMessage() {
            return statusCode == 401 ? t("native.your_session_expired_sign_in_to_hermes_again")
                    : t("native.hermes_request_failed_http", statusCode);
        }
    }

    static final class RpcException extends IOException {
        final Integer code;
        private final String fallbackKey;
        private final String method;
        private final String reason;

        private RpcException(String key) {
            this.code = null;
            this.fallbackKey = key;
            this.method = null;
            this.reason = null;
        }

        private RpcException(String method, JSONObject error) {
            this.code = error.optInt("code", 0);
            this.fallbackKey = null;
            this.method = method;
            JSONObject details = error.optJSONObject("data");
            this.reason = details == null ? text(error, "message") : text(details, "reason");
        }

        @Override
        public String getMessage() {
            return fallbackKey == null ? rpcErrorMessage(method, reason, code) : t(fallbackKey);
        }
    }

    private final HttpUrl baseUrl;
    private final String origin;
    private final Auth auth;
    private final OkHttpClient http;

    public HermesClient(String baseUrl, Auth auth) {
        this.baseUrl = parseBaseUrl(baseUrl);
        String root = this.baseUrl.newBuilder().encodedPath("/").build().toString();
        this.origin = root.substring(0, root.length() - 1);
        this.auth = auth;
        // Keep plaintext credentials and chat traffic on the direct private route.
        this.http = this.baseUrl.isHttps() ? HTTP : DIRECT_HTTP;
    }

    public static String normalizeBaseUrl(String baseUrl) {
        return parseBaseUrl(baseUrl).toString();
    }

    HermesTerminal newHostTerminal(BooleanSupplier scopeCurrent) {
        return new HermesTerminal(this, scopeCurrent);
    }

    static boolean isTailscaleAddress(String host) {
        if (host.startsWith("fd7a:115c:a1e0:")) {
            return true;
        }
        if (!host.matches("100\\.[1-9][0-9]{0,2}\\.(?:0|[1-9][0-9]{0,2})\\.(?:0|[1-9][0-9]{0,2})")) {
            return false;
        }
        String[] octets = host.split("\\.");
        int second = Integer.parseInt(octets[1]);
        return second >= 64 && second <= 127 && Integer.parseInt(octets[2]) <= 255
                && Integer.parseInt(octets[3]) <= 255;
    }

    private static HttpUrl parseBaseUrl(String value) {
        HttpUrl parsed = value == null ? null : HttpUrl.parse(value.trim());
        if (parsed == null || !parsed.username().isEmpty() || !parsed.password().isEmpty()
                || parsed.query() != null || parsed.fragment() != null) {
            throw new HermesText.InvalidInput("native.enter_the_http_or_https_address_of_your_hermes_webapp");
        }
        String path = parsed.encodedPath();
        while (path.endsWith("//")) {
            path = path.substring(0, path.length() - 1);
        }
        return parsed.newBuilder().encodedPath(path.endsWith("/") ? path : path + "/").build();
    }

    /** Returns recent stored conversations across the profiles served by this Webapp. */
    public List<Conversation> listConversations() throws IOException {
        return listConversations(false);
    }

    List<Conversation> listConversations(boolean archived) throws IOException {
        return listConversations(archived, null);
    }

    List<Conversation> listConversations(boolean archived, RequestRead read) throws IOException {
        return listConversationPage(archived, 0, read).rows;
    }

    static final class ConversationPage {
        final List<Conversation> rows;
        final int nextOffset;

        ConversationPage(List<Conversation> rows, int nextOffset) {
            this.rows = rows;
            this.nextOffset = nextOffset;
        }
    }

    ConversationPage listConversationPage(boolean archived, int offset, RequestRead read)
            throws IOException {
        HttpUrl.Builder url = endpoint("api/profiles/sessions")
                .addQueryParameter("limit", Integer.toString(CONVERSATION_LIMIT))
                .addQueryParameter("order", "recent")
                .addQueryParameter("min_messages", "1")
                .addQueryParameter("exclude_sources", "cron,api_server");
        if (archived) {
            url.addQueryParameter("archived", "only");
        }
        if (offset > 0) {
            url.addQueryParameter("offset", Integer.toString(offset));
        }
        try {
            JSONObject result = requestJson(url.build(), false, false, read);
            JSONArray rows = result.getJSONArray("sessions");
            JSONObject storage = result.optJSONObject("storage");
            JSONArray errors = result.optJSONArray("errors");
            if (rows.length() == 0 && ((storage != null && storage.length() > 0)
                    || (errors != null && errors.length() > 0))) {
                throw new HermesText.Failure("native.hermes_could_not_load_conversations");
            }
            int next = offset + CONVERSATION_LIMIT;
            int total = result.optInt("total", -1);
            boolean more = total >= 0 ? next < total : rows.length() >= CONVERSATION_LIMIT;
            List<Conversation> page = new ArrayList<>(collectConversations(rows, archived,
                    new LinkedHashMap<>()).values());
            return new ConversationPage(page, more ? next : 0);
        } catch (JSONException error) {
            throw invalidResponse(error);
        }
    }

    List<Conversation> searchConversations(String query, boolean archived, RequestRead read)
            throws IOException {
        try {
            JSONArray profiles = requestJson(endpoint("api/profiles").build(), false, true, read)
                    .getJSONArray("profiles");
            Map<String, Conversation> matches = new LinkedHashMap<>();
            for (int i = 0; i < profiles.length(); i++) {
                String name = text(profiles.getJSONObject(i), "name");
                if (name.isEmpty()) {
                    continue;
                }
                HttpUrl url = endpoint("api/sessions/search")
                        .addQueryParameter("q", query)
                        .addQueryParameter("limit", Integer.toString(CONVERSATION_LIMIT))
                        .addQueryParameter("exclude_sources", "cron,api_server")
                        .addQueryParameter("profile", name).build();
                collectConversations(requestJson(url, false, true, read).getJSONArray("results"),
                        archived, matches);
            }
            List<Conversation> rows = new ArrayList<>(matches.values());
            rows.sort(Comparator.comparingLong((Conversation row) -> row.lastActiveMillis).reversed());
            return rows.subList(0, Math.min(rows.size(), CONVERSATION_LIMIT));
        } catch (JSONException error) {
            throw invalidResponse(error);
        }
    }

    private Map<String, Conversation> collectConversations(JSONArray rows, boolean archived,
                                                            Map<String, Conversation> conversations)
            throws JSONException {
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            String id = text(row, "id");
            if (!id.isEmpty() && row.optInt("message_count", 0) > 0
                    && archived == row.optBoolean("archived", false)) {
                Conversation conversation = new Conversation(baseUrl, row, id);
                conversations.putIfAbsent(conversation.key, conversation);
            }
        }
        return conversations;
    }

    enum SidebarField {
        TITLE("title"), PINNED("pinned"), ARCHIVED("archived");

        final String wireName;

        SidebarField(String wireName) {
            this.wireName = wireName;
        }
    }

    void updateSidebar(Conversation conversation, SidebarField field, Object value) throws IOException {
        requireConversation(conversation);
        if (field == SidebarField.TITLE
                ? !(value instanceof String) || ((String) value).trim().isEmpty()
                : !(value instanceof Boolean)) {
            throw new HermesText.InvalidInput("native.invalid_conversation_settings");
        }
        try (RpcSocket socket = openGateway(conversation.profile, true)) {
            JSONObject snapshot = resume(socket, conversation);
            long revision = snapshot.optLong("revision", -1);
            if (revision < 0) {
                throw new HermesText.Failure("native.hermes_did_not_return_a_conversation_revision");
            }
            String sessionId = attachedSessionId(snapshot);
            JSONObject result = socket.call("session.mutate", new JSONObject()
                    .put("session_id", sessionId)
                    .put("request_id", UUID.randomUUID().toString())
                    .put("expected_revision", revision)
                    .put("operation", "sidebar")
                    .put("payload", new JSONObject().put(field.wireName, value)));
            if (!sessionId.equals(text(result, "session_id"))
                    || !"sidebar".equals(text(result, "operation"))) {
                throw new HermesText.Failure("native.could_not_confirm_the_conversation_settings_refresh_and_try_again");
            }
        } catch (JSONException error) {
            throw invalidResponse(error);
        }
    }

    /** Returns the latest stored messages in display order, including turns from other clients. */
    public List<Message> getMessages(Conversation conversation) throws IOException {
        return getMessageHistory(conversation, null, null).messages;
    }

    Conversation getConversation(String profile, String id, RequestRead read) throws IOException {
        HttpUrl.Builder url = endpoint("api/sessions").addPathSegment(id);
        addProfile(url, profile);
        try {
            JSONObject row = requestJson(url.build(), false, true, read);
            if (!id.equals(text(row, "id")) || !profile.equals(text(row, "profile"))) {
                throw new HermesText.Failure("native.hermes_returned_a_different_profile_or_conversation");
            }
            return new Conversation(baseUrl, row, id);
        } catch (JSONException error) {
            throw invalidResponse(error);
        }
    }

    void exportConversation(Conversation conversation, RequestRead read, OutputStream destination)
            throws IOException {
        requireConversation(conversation);
        HttpUrl.Builder url = endpoint("api/sessions").addPathSegment(conversation.id)
                .addPathSegment("export");
        addProfile(url, conversation.profile);
        Request request = authorizedRequest(url.build(), "application/json", true).get().build();
        try (Response response = execute(request, read)) {
            if (!response.isSuccessful()) {
                throw new HttpException(response.code());
            }
            ResponseBody body = response.body();
            MediaType type = body == null ? null : body.contentType();
            if (body == null || type == null
                    || !type.type().equals("application") || !type.subtype().equals("json")) {
                throw new HermesText.Failure("native.hermes_did_not_return_conversation_json");
            }
            try (InputStream input = body.byteStream()) {
                if (copy(input, destination) == 0) {
                    throw new HermesText.Failure("native.hermes_did_not_return_conversation_content");
                }
            }
        } finally {
            read.clear();
        }
    }

    static long copy(InputStream input, OutputStream destination) throws IOException {
        byte[] buffer = new byte[8192];
        long total = 0;
        int length;
        while ((length = input.read(buffer)) != -1) {
            destination.write(buffer, 0, length);
            total += length;
        }
        return total;
    }

    MessageHistory getMessageHistory(Conversation conversation, MessageHistory previous,
                                     HistoryRead read) throws IOException {
        requireConversation(conversation);
        try {
            if (previous != null && previous.tail != null) {
                JSONObject latest = readMessagePage(conversation, 1, read);
                if (previous.tail.equals(historyTail(latest, latest.getJSONArray("messages")))) {
                    return previous;
                }
            }
            JSONObject snapshot = readMessagePage(conversation, MESSAGE_LIMIT, read);
            JSONArray rows = snapshot.getJSONArray("messages");
            List<Message> messages = new ArrayList<>();
            for (int index = 0; index < rows.length(); index++) {
                JSONObject row = rows.getJSONObject(index);
                String role = text(row, "role");
                String content = visibleMessageContent(row, role);
                if (!content.trim().isEmpty()) {
                    Object rowId = row.opt("id");
                    String id = rowId == null || rowId == JSONObject.NULL
                            ? conversation.id + ":" + index : rowId.toString();
                    messages.add(new Message(id, role, content,
                            (long) (row.optDouble("timestamp", 0) * 1000)));
                }
            }
            return new MessageHistory(messages, historyTail(snapshot, rows));
        } catch (JSONException error) {
            throw invalidResponse(error);
        }
    }

    private JSONObject readMessagePage(Conversation conversation, int limit, HistoryRead read)
            throws IOException, JSONException {
        HttpUrl.Builder url = endpoint("api/sessions").addPathSegment(conversation.id)
                .addPathSegment("messages")
                .addQueryParameter("order", "latest")
                .addQueryParameter("limit", Integer.toString(limit))
                .addQueryParameter("inline_images", "false");
        addProfile(url, conversation.profile);
        return requestJson(url.build(), false, false, read);
    }

    private static String historyTail(JSONObject snapshot, JSONArray rows) throws JSONException {
        // The last stored row may be a tool result. Its ID still detects a batch
        // that also contains user/assistant rows before it, including after compression.
        JSONArray identity = new JSONArray().put(text(snapshot, "session_id"));
        if (rows.length() == 0) {
            return identity.toString();
        }
        JSONObject row = rows.getJSONObject(rows.length() - 1);
        if (row.isNull("id")) {
            return null;
        }
        String role = text(row, "role");
        return identity.put(row.get("id")).put(role).put(row.opt("timestamp"))
                .put(visibleMessageContent(row, role)).toString();
    }

    private static String visibleMessageContent(JSONObject row, String role) {
        if ((!role.equals("user") && !role.equals("assistant"))
                || text(row, "display_kind").equals("hidden")) {
            return "";
        }
        String content = messageContent(row.opt("display_content"));
        return content.isEmpty() ? messageContent(row.opt("content")) : content;
    }

    /** Creates a conversation on the launch profile. Retry a creation with the same request ID. */
    public Conversation createConversation(String requestId) throws IOException {
        if (requestId == null || requestId.trim().isEmpty()) {
            throw new HermesText.InvalidInput("native.the_new_conversation_request_id_is_missing");
        }
        try (RpcSocket socket = openGateway("")) {
            JSONObject snapshot = socket.call("session.create", new JSONObject()
                    .put("request_id", requestId)
                    .put("source", "gui")
                    .put("client", new JSONObject().put("name", "hermes-pocket")));
            String id = attachedSessionId(snapshot);
            JSONObject info = snapshot.getJSONObject("info");
            String profile = text(info, "profile_name");
            if (profile.isEmpty()) {
                throw new JSONException("Missing Hermes session profile");
            }
            info.put("profile", profile).put("running", snapshot.optBoolean("running", false));
            return new Conversation(baseUrl, info, id);
        } catch (JSONException error) {
            throw invalidResponse(error);
        }
    }

    /** Admits a new turn to the selected conversation and returns the server's receipt. */
    public SendReceipt sendMessage(Conversation conversation, String text) throws IOException {
        return sendMessage(conversation, text, null);
    }

    SendReceipt sendMessage(Conversation conversation, String text, SendRead stream) throws IOException {
        return sendMessage(conversation, text, Collections.emptyList(), stream, false);
    }

    SendReceipt sendMessage(Conversation conversation, String text, List<Attachment> attachments,
                            SendRead stream, boolean queued) throws IOException {
        requireConversation(conversation);
        if (text == null || text.trim().isEmpty() && attachments.isEmpty()) {
            throw new HermesText.InvalidInput("native.enter_a_message");
        }
        RpcSocket socket = null;
        boolean retainSocket = false;
        try {
            socket = openGateway(conversation.profile, false, stream);
            JSONObject snapshot = resume(socket, conversation);
            // An older stored row may be a compression segment. Submit to the
            // subscribed session the owner resolved from this exact row.
            String sessionId = attachedSessionId(snapshot);
            String submissionId = UUID.randomUUID().toString();
            JSONObject prompt = new JSONObject()
                    .put("session_id", sessionId)
                    .put("submission_id", submissionId)
                    .put("text", text.trim().isEmpty() ? t("native.please_review_the_attachments") : text);
            if (queued) {
                prompt.put("queued", true);
            }
            if (!attachments.isEmpty()) {
                stageAttachments(socket, conversation, prompt, attachments, stream);
            }
            if (stream != null) {
                stream.prepare(snapshot, sessionId, submissionId);
            }
            try {
                JSONObject result = socket.call("prompt.submit", prompt);
                if (result.optBoolean("voice_stopped", false)) {
                    throw new RpcException("native.hermes_stopped_voice_mode_send_the_message_again");
                }
                SendReceipt receipt = new SendReceipt(result, conversation, sessionId, text(prompt, "text"));
                retainSocket = stream != null && stream.acceptReceipt(receipt);
                return receipt;
            } catch (RpcException refused) {
                throw refused;
            } catch (IOException | JSONException uncertain) {
                throw new HermesText.Failure(uncertain, "native.delivery_could_not_be_confirmed_refresh_the_conversation_before_sending");
            }
        } catch (JSONException error) {
            throw invalidResponse(error);
        } finally {
            if (!retainSocket && socket != null) {
                if (stream == null) {
                    socket.close();
                } else {
                    stream.release(socket);
                }
            }
        }
    }

    private void stageAttachments(RpcSocket socket, Conversation conversation, JSONObject prompt,
                                  List<Attachment> attachments, SendRead stream)
            throws IOException, JSONException {
        String sessionId = text(prompt, "session_id");
        StringBuilder promptText = new StringBuilder(text(prompt, "text"));
        JSONArray images = new JSONArray();
        RequestRead read = stream == null ? null : stream.ticketRead;
        for (Attachment attachment : attachments) {
            if (read != null) {
                read.checkCancelled();
            }
            if (attachment.image) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                copyAttachment(attachment, IMAGE_LIMIT, bytes::write);
                JSONObject image = socket.call("image.attach_bytes", new JSONObject()
                        .put("session_id", sessionId)
                        .put("filename", attachment.name)
                        .put("content_base64", Base64.getEncoder().encodeToString(bytes.toByteArray())));
                String path = text(image, "path");
                String mime = text(image, "mime");
                if (!image.optBoolean("attached") || path.isEmpty()
                        || !mime.matches("image/(?:jpeg|png|gif|webp)")) {
                    throw new HermesText.Failure("native.hermes_could_not_attach_the_image");
                }
                images.put(new JSONObject().put("path", path).put("mime", mime));
            } else {
                JSONObject descriptor = uploadAttachment(conversation.profile, attachment, read);
                JSONObject file = socket.call("file.attach", new JSONObject()
                        .put("session_id", sessionId)
                        .put("name", attachment.name)
                        .put("staged_upload", descriptor));
                String reference = text(file, "ref_text");
                if (!file.optBoolean("attached") || !reference.startsWith("@file:")
                        || reference.length() == 6 || reference.indexOf('\n') >= 0
                        || reference.indexOf('\r') >= 0) {
                    throw new HermesText.Failure("native.hermes_could_not_attach_the_file");
                }
                promptText.append('\n').append(reference);
            }
        }
        prompt.put("text", promptText.toString());
        if (images.length() > 0) {
            prompt.put("attachments", images);
        }
    }

    private JSONObject uploadAttachment(String profile, Attachment attachment, RequestRead read)
            throws IOException, JSONException {
        HttpUrl.Builder endpoint = endpoint("api/chat/file-upload");
        addProfile(endpoint, profile);
        RequestBody contents = new RequestBody() {
            @Override
            public MediaType contentType() {
                return MediaType.get("application/octet-stream");
            }

            @Override
            public void writeTo(BufferedSink sink) throws IOException {
                copyAttachment(attachment, FILE_LIMIT, sink::write);
            }
        };
        RequestBody upload = new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", attachment.name, contents).build();
        Request request = authorizedRequest(endpoint.build(), "application/json", true)
                .post(singleUse(upload)).build();
        try (Response response = execute(request, read)) {
            if (!response.isSuccessful()) {
                throw new HttpException(response.code());
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new HermesText.Failure("native.hermes_did_not_return_a_file_upload_result");
            }
            JSONObject result = new JSONObject(body.source().readUtf8());
            JSONObject descriptor = result.optJSONObject("staged_upload");
            if (!result.optBoolean("ok") || descriptor == null) {
                throw new HermesText.Failure("native.hermes_did_not_return_a_file_attachment_ticket");
            }
            return descriptor;
        } finally {
            if (read != null) {
                read.clear();
            }
        }
    }

    private interface AttachmentSink {
        void write(byte[] bytes, int start, int length) throws IOException;
    }

    private static void copyAttachment(Attachment attachment, int limit, AttachmentSink sink)
            throws IOException {
        try (InputStream input = attachment.source.open()) {
            if (input == null) {
                throw new HermesText.Failure("native.could_not_read_the_selected_attachment");
            }
            byte[] buffer = new byte[8192];
            int total = 0;
            int length;
            while ((length = input.read(buffer)) != -1) {
                total += length;
                if (total > limit) {
                    throw new HermesText.Failure("native.the_attachment_exceeds", limit / (1024 * 1024));
                }
                sink.write(buffer, 0, length);
            }
            if (total == 0) {
                throw new HermesText.Failure("native.the_selected_attachment_is_empty");
            }
        }
    }

    private static JSONObject resume(RpcSocket socket, Conversation conversation)
            throws IOException, JSONException {
        return socket.call("session.resume", new JSONObject()
                .put("session_id", conversation.id)
                .put("omit_messages", true));
    }

    private static String attachedSessionId(JSONObject snapshot) throws IOException {
        String sessionId = text(snapshot, "session_id");
        if (sessionId.isEmpty() || text(snapshot, "subscription_id").isEmpty()) {
            throw new HermesText.Failure("native.hermes_could_not_open_this_conversation");
        }
        return sessionId;
    }

    ChatControls getChatControls(Conversation conversation, ControlRead read) throws IOException {
        requireConversation(conversation);
        try {
            RpcSocket socket = controlSocket(conversation, read);
            JSONObject snapshot = resume(socket, conversation);
            String sessionId = attachedSessionId(snapshot);
            read.watch(sessionId);
            return new ChatControls(snapshot, sessionId);
        } catch (JSONException error) {
            read.closeSocket();
            throw invalidResponse(error);
        } catch (IOException error) {
            read.closeSocket();
            throw error;
        }
    }

    enum ControlAction { STOP, CANCEL_QUEUE, STEER_QUEUE, RESPOND }

    void actOnChat(Conversation conversation, ControlRead read, ChatControls previous,
                   ControlAction action, String admissionId, ChatControls.Prompt prompt, String response)
            throws IOException {
        try {
            ChatControls fresh = getChatControls(conversation, read);
            RpcSocket socket = read.usableSocket();
            if (socket == null) {
                throw staleControl();
            }
            JSONObject params = new JSONObject().put("session_id", fresh.sessionId);
            switch (action) {
                case STOP:
                    if (!fresh.sameTurn(previous)) {
                        throw staleControl();
                    }
                    JSONObject stopped = socket.call("session.interrupt",
                            params.put("execution_generation", fresh.executionGeneration));
                    requireSessionRef(stopped, fresh);
                    break;
                case CANCEL_QUEUE:
                case STEER_QUEUE:
                    ChatControls.Queued queued = fresh.queued(admissionId);
                    boolean steer = action == ControlAction.STEER_QUEUE;
                    if (!fresh.sameAuthority(previous) || previous.queued(admissionId) == null
                            || queued == null || steer && (!fresh.sameTurn(previous)
                            || !previous.queued(admissionId).canSteer || !queued.canSteer)) {
                        throw staleControl();
                    }
                    JSONObject moved = socket.call(steer ? "prompt.steer_queued" : "prompt.cancel",
                            params.put("admission_id", admissionId));
                    requireSessionRef(moved, fresh);
                    if (!admissionId.equals(text(moved, "admission_id"))) {
                        throw new JSONException("Invalid Hermes control receipt");
                    }
                    break;
                case RESPOND:
                    if (!fresh.sameTurn(previous) || prompt == null || previous.prompt(prompt) == null
                            || fresh.prompt(prompt) == null || !prompt.canAnswer(response)) {
                        throw staleControl();
                    }
                    String method = prompt.approval() ? "approval.respond" : "clarify.respond";
                    JSONObject answered = socket.call(method, params
                            .put("execution_generation", fresh.executionGeneration)
                            .put("prompt_id", prompt.id)
                            .put(prompt.approval() ? "choice" : "answer", response));
                    String status = text(answered, "status");
                    if (!prompt.id.equals(text(answered, "prompt_id"))
                            || !(status.equals("resolved") || status.equals("already_resolved"))) {
                        throw new JSONException("Invalid Hermes prompt receipt");
                    }
                    break;
            }
        } catch (JSONException error) {
            read.closeSocket();
            throw invalidResponse(error);
        } catch (IOException error) {
            read.closeSocket();
            throw error;
        }
    }

    private static IOException staleControl() {
        return new HermesText.Failure("native.this_conversation_action_expired_refreshing_its_status");
    }

    private static void requireSessionRef(JSONObject result, ChatControls fresh) throws JSONException {
        JSONObject ref = result.getJSONObject("ref");
        if (!fresh.sessionId.equals(text(ref, "session_id"))
                || fresh.authorityEpoch != result.optLong("authority_epoch", -1)) {
            throw new JSONException("Invalid Hermes session control identity");
        }
    }

    private RpcSocket controlSocket(Conversation conversation, ControlRead read)
            throws IOException, JSONException {
        RpcSocket socket = read.usableSocket();
        if (socket == null) {
            socket = openGateway(conversation.profile, true, null, read);
        }
        return socket;
    }

    public SendReceipt getSendStatus(SendReceipt receipt) throws IOException {
        requireConversation(receipt.conversation);
        if (receipt.isTerminal()) {
            return receipt;
        }
        try (RpcSocket socket = openGateway(receipt.conversation.profile)) {
            JSONObject result = socket.call("prompt.receipt", new JSONObject()
                    .put("session_id", receipt.sessionId)
                    .put("admission_id", receipt.admissionId));
            SendReceipt updated = new SendReceipt(result, receipt.conversation, receipt.sessionId);
            if (!receipt.admissionId.equals(updated.admissionId)) {
                throw new JSONException("Invalid Hermes receipt identity");
            }
            return updated;
        } catch (JSONException error) {
            throw invalidResponse(error);
        }
    }

    private void requireConversation(Conversation conversation) {
        if (conversation == null || !baseUrl.equals(conversation.server)) {
            throw new HermesText.InvalidInput("native.select_a_conversation_on_this_hermes_server_first");
        }
    }

    private HttpUrl.Builder endpoint(String path) {
        return baseUrl.newBuilder().addPathSegments(path);
    }

    private static void addProfile(HttpUrl.Builder url, String profile) {
        if (!profile.isEmpty()) {
            url.addQueryParameter("profile", profile);
        }
    }

    private JSONObject requestJson(HttpUrl url, boolean post) throws IOException, JSONException {
        return requestJson(url, post, false);
    }

    private JSONObject requestJson(HttpUrl url, boolean post, boolean requireAuth)
            throws IOException, JSONException {
        return requestJson(url, post, requireAuth, null);
    }

    private JSONObject requestJson(HttpUrl url, boolean post, boolean requireAuth, RequestRead read)
            throws IOException, JSONException {
        if (read != null) {
            read.checkCancelled();
        }
        Request.Builder builder = authorizedRequest(url, "application/json", requireAuth);
        if (post) {
            builder.header("Origin", origin).post(EMPTY_BODY);
        } else {
            builder.tag(ReadAttempt.class, new ReadAttempt());
        }
        try (Response response = execute(builder.build(), read)) {
            List<String> newCookies = response.headers("Set-Cookie");
            if (!newCookies.isEmpty()) {
                auth.receiveCookies(url.toString(), newCookies);
            }
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) {
                throw new HttpException(response.code());
            }
            return new JSONObject(body.string());
        }
    }

    private Request.Builder authorizedRequest(HttpUrl url, String accept, boolean requireAuth)
            throws IOException {
        Request.Builder builder = new Request.Builder().url(url).header("Accept", accept);
        String cookie = auth.cookieHeader(url.toString());
        boolean hasCookie = cookie != null && !cookie.isEmpty();
        if (hasCookie) {
            builder.header("Cookie", cookie);
        }
        String token = auth.accessToken(url.toString());
        boolean hasToken = token != null && !token.isEmpty();
        if (hasToken) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (requireAuth && !hasToken && !hasCookie) {
            throw new HermesText.Failure("native.sign_in_to_hermes_first");
        }
        return builder;
    }

    String mintHostTerminalTicket(RequestRead read) throws IOException, JSONException {
        HttpUrl url = endpoint("api/webapp/socket-ticket").addQueryParameter("socket", "host-terminal").build();
        read.checkCancelled();
        Request request = authorizedRequest(url, "application/json", false)
                .header("Origin", origin).post(EMPTY_BODY).build();
        if (request.header("Authorization") == null && request.header("Cookie") == null) {
            throw new HttpException(401);
        }
        try (Response response = execute(request, read)) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) {
                throw new HttpException(response.code());
            }
            if (body.source().request(4097)) {
                throw new HermesText.Failure("native.hermes_returned_an_invalid_terminal_ticket");
            }
            String ticket = new JSONObject(body.source().readUtf8()).getString("ticket");
            if (!HermesTerminal.validHandle(ticket)) {
                throw new HermesText.Failure("native.hermes_returned_an_invalid_terminal_ticket");
            }
            return ticket;
        } finally {
            read.clear();
        }
    }

    enum AudioSocket {
        SPEAK("speak-stream"), TRANSCRIBE("transcribe-stream");

        final String name;

        AudioSocket(String name) {
            this.name = name;
        }
    }

    enum AudioRoute {
        CONFIG("voice-config"), STATUS("voice-live/status"),
        TRANSCRIBE("transcribe"), SPEAK("speak");

        final String path;

        AudioRoute(String path) {
            this.path = path;
        }
    }

    String mintAudioTicket(AudioSocket target, RequestRead read) throws IOException, JSONException {
        HttpUrl url = endpoint("api/webapp/socket-ticket").addQueryParameter("socket", target.name).build();
        read.checkCancelled();
        Request request = authorizedRequest(url, "application/json", true)
                .header("Origin", origin).post(EMPTY_BODY).build();
        try (Response response = execute(request, read)) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) {
                throw new HttpException(response.code());
            }
            if (body.source().request(4097)) {
                throw new HermesText.Failure("native.hermes_returned_an_invalid_audio_ticket");
            }
            String ticket = new JSONObject(body.source().readUtf8()).getString("ticket");
            if (!ticket.matches("[A-Za-z0-9_-]{1,512}")) {
                throw new HermesText.Failure("native.hermes_returned_an_invalid_audio_ticket");
            }
            return ticket;
        } finally {
            read.clear();
        }
    }

    WebSocket openAudioSocket(AudioSocket target, String ticket, WebSocketListener listener) {
        Request request = new Request.Builder().url(endpoint("api/audio/" + target.name).build())
                .header("Origin", origin)
                .header("Sec-WebSocket-Protocol", GATEWAY_PROTOCOL + ", hermes-gateway-ticket." + ticket)
                .build();
        return http.newWebSocket(request, listener);
    }

    Response executeAudioRequest(AudioRoute route, RequestBody body, RequestRead read)
            throws IOException {
        read.checkCancelled();
        Request.Builder request = authorizedRequest(endpoint("api/audio/" + route.path).build(),
                "application/json", true).header("Origin", origin);
        if (body != null) {
            request.post(singleUse(body));
        }
        OkHttpClient transport = baseUrl.isHttps() ? AUDIO_HTTP : DIRECT_AUDIO_HTTP;
        Response response = read.bind(transport.newCall(request.build())).execute();
        if (!response.isSuccessful()) {
            int status = response.code();
            response.close();
            throw new HttpException(status);
        }
        return response;
    }

    Response executeOfficialRequest(HttpUrl url, String method, RequestRead read) throws IOException {
        Request request = workspaceRequest(url, "application/json")
                .method(method, "POST".equals(method) ? EMPTY_BODY : null).build();
        Response response = execute(request, read);
        if (!response.isSuccessful()) {
            int status = response.code();
            response.close();
            throw new HttpException(status);
        }
        return response;
    }

    Response executeAppearanceRequest(RequestBody patch, RequestRead read) throws IOException {
        read.checkCancelled();
        Request.Builder request = workspaceRequest(endpoint("api/ui-preferences").build(), "application/json");
        if (patch != null) {
            request.patch(singleUse(patch));
        }
        Response response = execute(request.build(), read);
        if (!response.isSuccessful()) {
            int status = response.code();
            response.close();
            throw new HttpException(status);
        }
        return response;
    }

    Response executeArchiveRequest(HttpUrl url, String method, String accept, RequestBody body,
                                   RequestRead read) throws IOException {
        read.checkCancelled();
        Request request = workspaceRequest(url, accept)
                .method(method, body == null ? null : singleUse(body)).build();
        OkHttpClient transport = baseUrl.isHttps() ? AUDIO_HTTP : DIRECT_AUDIO_HTTP;
        Response response = read.bind(transport.newCall(request)).execute();
        if (!response.isSuccessful()) {
            int status = response.code();
            response.close();
            throw new HttpException(status);
        }
        return response;
    }

    WebSocket openHostTerminalSocket(String ticket, String attach, boolean close, int columns, int rows,
                                     String profile, String cwd, WebSocketListener listener) {
        HttpUrl.Builder url = endpoint("api/host-terminal").addQueryParameter("ticket", ticket);
        addProfile(url, profile);
        if (attach != null) {
            url.addQueryParameter("attach", attach);
        }
        if (close) {
            url.addQueryParameter("action", "close");
        } else {
            url.addQueryParameter("cols", Integer.toString(columns)).addQueryParameter("rows", Integer.toString(rows));
            if (!cwd.isEmpty()) {
                url.addQueryParameter("cwd", cwd);
            }
        }
        return http.newWebSocket(new Request.Builder().url(url.build()).header("Origin", origin).build(), listener);
    }

    String mintKanbanEventTicket(RequestRead read) throws IOException, JSONException {
        HttpUrl url = endpoint("api/auth/ws-ticket").build();
        read.checkCancelled();
        Request request = authorizedRequest(url, "application/json", true).header("Origin", origin)
                .post(singleUse(EMPTY_BODY)).build();
        try (Response response = execute(request, read)) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) {
                throw new HttpException(response.code());
            }
            if (body.source().request(4097)) {
                throw new HermesText.Failure("native.hermes_returned_an_invalid_kanban_event_ticket");
            }
            Object ticket = new JSONObject(body.source().readUtf8()).opt("ticket");
            if (!(ticket instanceof String) || !((String) ticket).matches("[A-Za-z0-9_-]{1,512}")) {
                throw new HermesText.Failure("native.hermes_returned_an_invalid_kanban_event_ticket");
            }
            return (String) ticket;
        } finally {
            read.clear();
        }
    }

    WebSocket openKanbanEvents(String ticket, String profile, String board, Long cursor, WebSocketListener listener) {
        HttpUrl.Builder url = endpoint("api/plugins/kanban/events").addQueryParameter("ticket", ticket)
                .addQueryParameter("board", board);
        addProfile(url, profile);
        if (cursor != null) {
            url.addQueryParameter("since", Long.toString(cursor));
        }
        return http.newWebSocket(new Request.Builder().url(url.build()).header("Origin", origin).build(), listener);
    }

    String mintDisplayTicket(String profile, GatewayRead read) throws IOException, JSONException {
        RpcSocket gateway = openGateway(profile, true, null, read);
        try (RpcSocket connection = gateway) {
            JSONObject params = new JSONObject();
            if (!profile.isEmpty()) {
                params.put("profile", profile);
            }
            JSONObject result = connection.call("display.observe", params);
            String ticket = text(result, "ticket");
            if (!"/api/display/ws".equals(text(result, "path")) || !ticket.matches("[A-Za-z0-9_-]{1,512}")
                    || !text(result, "viewer_id").matches("[A-Za-z0-9_-]{1,128}")) {
                throw new HermesText.Failure("native.hermes_returned_an_invalid_display_ticket");
            }
            return ticket;
        } finally {
            read.releaseSocket(gateway);
        }
    }

    WebSocket openDisplaySocket(String displayTicket, WebSocketListener listener) {
        HttpUrl url = endpoint("api/display/ws").addQueryParameter("display_ticket", displayTicket).build();
        return http.newWebSocket(new Request.Builder().url(url).header("Origin", origin).build(), listener);
    }

    Response executeWorkspaceRequest(HttpUrl url, String method, RequestBody body, String accept,
            boolean recoverStaleConnection)
            throws IOException {
        Request.Builder request = workspaceRequest(url, accept).method(method, body);
        if (recoverStaleConnection && body == null && "GET".equals(method)) {
            return executeWorkspaceRead(request);
        }
        return http.newCall(request.build()).execute();
    }

    Response executeWorkspacePrefixRequest(HttpUrl url, int lastByte) throws IOException {
        return executeWorkspaceRead(workspaceRequest(url, "*/*").header("Range", "bytes=0-" + lastByte).get());
    }

    Response executeMediaRangeRequest(HttpUrl url, String range, String validator, RequestRead read)
            throws IOException {
        if (!endpoint("api/files/stream").build().encodedPath().equals(url.encodedPath())) {
            throw new HermesText.InvalidInput("native.this_route_is_not_for_hermes_media_files");
        }
        Request.Builder request = workspaceRequest(url, "audio/*, video/*, application/octet-stream")
                .header("Accept-Encoding", "identity");
        if (range == null) {
            request.head();
        } else {
            request.header("Range", range).get();
            if (validator != null) {
                request.header("If-Range", validator);
            }
        }
        return execute(request.tag(ReadAttempt.class, new ReadAttempt()).build(), read);
    }

    private Response executeWorkspaceRead(Request.Builder request) throws IOException {
        try {
            return execute(request.tag(ReadAttempt.class, new ReadAttempt()).build(), null);
        } catch (IOException error) {
            throw new WorkspaceReadException(error);
        }
    }

    static final class WorkspaceReadException extends HermesText.Failure {
        private WorkspaceReadException(IOException cause) {
            super(cause, "native.hermes_is_temporarily_unreachable_check_your_network_and_try_again");
        }
    }

    private Request.Builder workspaceRequest(HttpUrl url, String accept) throws IOException {
        if (!baseUrl.scheme().equals(url.scheme()) || !baseUrl.host().equals(url.host())
                || baseUrl.port() != url.port() || !url.encodedPath().startsWith(baseUrl.encodedPath())
                || !url.username().isEmpty() || !url.password().isEmpty() || url.fragment() != null) {
            throw new HermesText.InvalidInput("native.the_request_address_does_not_match_the_sign_in_address");
        }
        return authorizedRequest(url, accept, true).header("Origin", origin);
    }

    static RequestBody singleUse(RequestBody body) {
        return new RequestBody() {
            @Override
            public MediaType contentType() {
                return body.contentType();
            }

            @Override
            public long contentLength() throws IOException {
                return body.contentLength();
            }

            @Override
            public boolean isOneShot() {
                return true;
            }

            @Override
            public void writeTo(BufferedSink sink) throws IOException {
                body.writeTo(sink);
            }
        };
    }

    Object executeWorkspaceRpc(String method, String profile, JSONObject params)
            throws IOException, JSONException {
        try (RpcSocket socket = openGateway(profile, true)) {
            return socket.callValue(method, params);
        }
    }

    Object executeHostedGroupControl(String method, String profile, JSONObject params)
            throws IOException, JSONException {
        try (RpcSocket socket = openGateway(profile, true)) {
            JSONObject scope = new JSONObject();
            if (!profile.isEmpty()) {
                scope.put("profile", profile);
            }
            JSONObject capabilities = socket.call("groups.capabilities", scope);
            JSONArray methods = capabilities.optJSONArray("methods");
            if (!capabilities.optBoolean("persistent_process") || !listed(methods, method)
                    || (!"groups.create".equals(method) && !capabilities.optBoolean("driver"))) {
                throw new HermesText.InvalidInput("native.this_profile_does_not_support_this_group_chat_action");
            }
            if ("groups.create".equals(method)) {
                JSONArray allowed = capabilities.optJSONArray("seatable_profiles");
                JSONArray members = params.getJSONArray("members");
                for (int index = 0; index < members.length(); index++) {
                    String candidate = members.getJSONObject(index).getString("profile");
                    if (!listed(allowed, candidate)) {
                        throw new HermesText.InvalidInput("native.the_server_cannot_add_the_selected_profile_to_this_group");
                    }
                }
            }
            return socket.callValue(method, params);
        }
    }

    private static boolean listed(JSONArray values, String candidate) {
        if (values != null) {
            for (int index = 0; index < values.length(); index++) {
                if (candidate.equals(values.opt(index))) {
                    return true;
                }
            }
        }
        return false;
    }

    Object executeAttachedWorkspaceRpc(String method, String profile, JSONObject params)
            throws IOException, JSONException {
        try (RpcSocket socket = openGateway(profile, true)) {
            JSONObject snapshot = socket.call("session.resume", new JSONObject()
                    .put("session_id", params.getString("session_id"))
                    .put("omit_messages", true));
            JSONObject bound = new JSONObject(params.toString()).put("session_id", attachedSessionId(snapshot));
            return socket.callValue(method, bound);
        }
    }

    private Response execute(Request request, RequestRead read) throws IOException {
        try {
            Call call = http.newCall(request);
            return (read == null ? call : read.bind(call)).execute();
        } catch (IOException error) {
            ReadAttempt attempt = request.tag(ReadAttempt.class);
            if (attempt == null || !attempt.canRetry(error)) {
                throw error;
            }
            // Use a fresh socket once and keep the request's already bound credentials.
            OkHttpClient fresh = http.newBuilder()
                    .connectionPool(new ConnectionPool(0, 1, TimeUnit.SECONDS))
                    .build();
            Call call = fresh.newCall(request.newBuilder().tag(ReadAttempt.class, null).build());
            return (read == null ? call : read.bind(call)).execute();
        }
    }

    private static final class ReadAttempt extends EventListener {
        private boolean startedConnection;
        private boolean reusedConnection;
        private boolean receivedHeaders;

        @Override
        public void connectStart(Call call, InetSocketAddress address, Proxy proxy) {
            startedConnection = true;
        }

        @Override
        public void connectionAcquired(Call call, Connection connection) {
            reusedConnection = !startedConnection;
        }

        @Override
        public void responseHeadersEnd(Call call, Response response) {
            receivedHeaders = true;
        }

        private boolean canRetry(IOException error) {
            if (!reusedConnection || receivedHeaders) {
                return false;
            }
            for (Throwable cause = error; cause != null; cause = cause.getCause()) {
                if (cause instanceof EOFException) {
                    return true;
                }
            }
            return false;
        }
    }

    private RpcSocket openGateway(String profile) throws IOException, JSONException {
        return openGateway(profile, false);
    }

    private RpcSocket openGateway(String profile, boolean requireAuth) throws IOException, JSONException {
        return openGateway(profile, requireAuth, null);
    }

    private RpcSocket openGateway(String profile, boolean requireAuth, SendRead stream)
            throws IOException, JSONException {
        return openGateway(profile, requireAuth, stream, stream == null ? null : stream.ticketRead);
    }

    private RpcSocket openGateway(String profile, boolean requireAuth, SendRead stream,
                                  RequestRead ticketRead) throws IOException, JSONException {
        HttpUrl.Builder ticketUrl = endpoint("api/webapp/socket-ticket")
                .addQueryParameter("socket", "gateway");
        addProfile(ticketUrl, profile);
        String ticket;
        try {
            ticket = requestJson(ticketUrl.build(), true, requireAuth, ticketRead).getString("ticket");
        } finally {
            if (ticketRead != null) {
                ticketRead.clear();
            }
        }
        if (!ticket.matches("[A-Za-z0-9_-]+")) {
            throw new HermesText.Failure("native.hermes_did_not_provide_a_valid_gateway_ticket");
        }
        Request request = new Request.Builder().url(endpoint("api/ws").build())
                .header("Origin", origin)
                .header("Sec-WebSocket-Protocol", GATEWAY_PROTOCOL + ", hermes-gateway-ticket." + ticket)
                .build();
        GatewayRead binding = ticketRead instanceof GatewayRead ? (GatewayRead) ticketRead : null;
        ControlRead controls = ticketRead instanceof ControlRead ? (ControlRead) ticketRead : null;
        RpcSocket socket = new RpcSocket(http, request, stream, controls);
        try {
            if (stream != null) {
                stream.bind(socket);
            } else if (binding != null) {
                binding.bindSocket(socket);
            }
            socket.awaitReady();
            return socket;
        } catch (IOException error) {
            socket.abort();
            if (stream != null) {
                stream.release(socket);
            } else if (binding != null) {
                binding.releaseSocket(socket);
            }
            throw error;
        }
    }

    private static String text(JSONObject object, String key) {
        Object value = object.opt(key);
        return value instanceof String ? (String) value : "";
    }

    private static String messageContent(Object content) {
        if (content instanceof String) {
            return (String) content;
        }
        if (!(content instanceof JSONArray)) {
            return "";
        }
        JSONArray parts = (JSONArray) content;
        List<String> lines = new ArrayList<>();
        for (int index = 0; index < parts.length(); index++) {
            JSONObject part = parts.optJSONObject(index);
            if (part == null) {
                continue;
            }
            String text = text(part, "text");
            if (!text.isEmpty()) {
                lines.add(text);
            } else if (text(part, "type").equals("image_url")) {
                lines.add(t("native.image"));
            }
        }
        return String.join("\n", lines);
    }

    private static IOException invalidResponse(JSONException error) {
        return new HermesText.Failure(error, "native.hermes_returned_data_that_could_not_be_read");
    }

    private static String rpcErrorMessage(String method, String reason, int code) {
        switch (reason) {
            case "not_found":
                return t("native.hermes_could_not_find_this_conversation_refresh_the_conversation_list");
            case "permission_denied":
                return t("native.this_account_cannot_perform_this_conversation_action");
            case "storage_unavailable":
                return t("native.hermes_could_not_load_conversations");
            default:
                return t("native.hermes_could_not_complete_the_request", method, code);
        }
    }

    private static InterruptedIOException interrupted(InterruptedException cause) {
        Thread.currentThread().interrupt();
        InterruptedIOException error = new InterruptedIOException(t("native.the_hermes_request_was_interrupted"));
        error.initCause(cause);
        return error;
    }

    /** Each operation uses its own RPC stream, so only replies to its current request are queued. */
    private static final class RpcSocket extends WebSocketListener implements AutoCloseable {
        private final CountDownLatch ready = new CountDownLatch(1);
        private final BlockingQueue<JSONObject> replies = new LinkedBlockingQueue<>();
        private final WebSocket socket;
        private final SendRead stream;
        private final ControlRead controls;
        private volatile IOException failure;
        private volatile String awaitingId;
        private long sequence;

        private RpcSocket(OkHttpClient http, Request request, SendRead stream, ControlRead controls) {
            this.stream = stream;
            this.controls = controls;
            socket = http.newWebSocket(request, this);
        }

        private void awaitReady() throws IOException {
            try {
                if (!ready.await(RPC_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw new HermesText.Failure("native.timed_out_connecting_to_the_hermes_gateway");
                }
                checkFailure();
            } catch (InterruptedException error) {
                throw interrupted(error);
            }
        }

        @Override
        public void onOpen(WebSocket socket, Response response) {
            if (!GATEWAY_PROTOCOL.equals(response.header("Sec-WebSocket-Protocol"))) {
                fail(new HermesText.Failure("native.hermes_did_not_accept_the_gateway_protocol"));
            }
        }

        @Override
        public void onMessage(WebSocket socket, String message) {
            try {
                JSONObject frame = new JSONObject(message);
                String expected = awaitingId;
                if (expected != null && expected.equals(text(frame, "id"))
                        && (frame.has("result") || frame.has("error"))) {
                    replies.offer(frame);
                }
                JSONObject params = frame.optJSONObject("params");
                if (params == null) {
                    return;
                }
                if (!"gateway.ready".equals(text(params, "type"))) {
                    if ("event".equals(text(frame, "method"))
                            && "2.0".equals(text(frame, "jsonrpc"))) {
                        if (stream != null) {
                            stream.event(params, message.length());
                        }
                        if (controls != null) {
                            controls.event(params);
                        }
                    }
                    return;
                }
                JSONObject payload = params.optJSONObject("payload");
                if (payload == null || !payload.optBoolean("session_authority", false)) {
                    fail(new HermesText.Failure("native.this_hermes_address_does_not_provide_the_shared_conversation_gateway"));
                } else {
                    ready.countDown();
                }
            } catch (JSONException error) {
                fail(invalidResponse(error));
            }
        }

        @Override
        public void onClosing(WebSocket socket, int code, String reason) {
            fail(new HermesText.Failure("native.the_hermes_gateway_connection_closed", code));
            socket.close(1000, null);
        }

        @Override
        public void onFailure(WebSocket socket, Throwable error, Response response) {
            fail(response == null ? new HermesText.Failure(error, "native.could_not_connect_to_the_hermes_gateway")
                    : new HttpException(response.code()));
        }

        private void fail(IOException error) {
            failure = error;
            ready.countDown();
            replies.offer(new JSONObject());
            if (stream != null) {
                stream.disconnected();
            }
        }

        private void abort() {
            fail(new InterruptedIOException(t("native.the_hermes_gateway_connection_was_canceled")));
            socket.cancel();
        }

        private void checkFailure() throws IOException {
            IOException error = failure;
            if (error != null) {
                throw error;
            }
        }

        private JSONObject call(String method, JSONObject params) throws IOException, JSONException {
            Object value = callValue(method, params);
            if (!(value instanceof JSONObject)) {
                throw new HermesText.Failure("native.the_hermes_gateway_did_not_return_a_result");
            }
            return (JSONObject) value;
        }

        private Object callValue(String method, JSONObject params) throws IOException, JSONException {
            checkFailure();
            String id = Long.toString(++sequence);
            JSONObject request = new JSONObject().put("jsonrpc", "2.0")
                    .put("id", id).put("method", method).put("params", params);
            awaitingId = id;
            try {
                if (!socket.send(request.toString())) {
                    throw new HermesText.Failure("native.could_not_send_the_hermes_gateway_request");
                }
                JSONObject answer = replies.poll(RPC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (answer == null) {
                    throw new HermesText.Failure("native.the_hermes_gateway_request_timed_out");
                }
                JSONObject error = answer.optJSONObject("error");
                if (error != null) {
                    throw new RpcException(method, error);
                }
                if (answer.has("result")) {
                    return answer.get("result");
                }
                checkFailure();
                throw new HermesText.Failure("native.the_hermes_gateway_did_not_return_a_result");
            } catch (InterruptedException error) {
                throw interrupted(error);
            } finally {
                awaitingId = null;
            }
        }

        @Override
        public void close() {
            if (failure != null) {
                socket.cancel();
            } else {
                socket.close(1000, null);
            }
        }
    }
}
