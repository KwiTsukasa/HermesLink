package cn.xhs.hermespocket;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.HttpUrl;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class HermesChatActionsWireTest {
    private static final String PROTOCOL = "hermes-gateway-v1";
    private static final String SESSION = "canonical-chat";
    private static final String PROFILE = "research";
    private static final long EPOCH = 13;

    @Test(timeout = 25000)
    public void sendsOnlySelectedPendingControlsWithTheCurrentSessionAndTurn() throws Exception {
        try (Gateway gateway = new Gateway()) {
            HermesClient.Conversation row = gateway.client.listConversations().get(0);
            HermesClient.ControlRead read = new HermesClient.ControlRead();
            try {
                ChatControls shown = gateway.client.getChatControls(row, read);
                assertEquals(2, shown.prompts.size());
                assertEquals(1, shown.queued.size());
                assertTrue(shown.prompts.get(0).canAnswer("once"));
                assertFalse(shown.prompts.get(0).canAnswer("always"));
                gateway.client.actOnChat(row, read, shown, HermesClient.ControlAction.RESPOND,
                        null, shown.prompts.get(0), "once");
                gateway.client.actOnChat(row, read, shown, HermesClient.ControlAction.RESPOND,
                        null, shown.prompts.get(1), "[\"A\",\"B\"]");
                gateway.client.actOnChat(row, read, shown, HermesClient.ControlAction.STEER_QUEUE,
                        "queued", null, null);
                gateway.client.actOnChat(row, read, shown, HermesClient.ControlAction.CANCEL_QUEUE,
                        "queued", null, null);
                gateway.client.actOnChat(row, read, shown, HermesClient.ControlAction.STOP,
                        null, null, null);
                assertEquals(List.of("approval.respond", "clarify.respond", "prompt.steer_queued",
                        "prompt.cancel", "session.interrupt"), gateway.writes);
                gateway.generation++;
                assertThrows(IOException.class, () -> gateway.client.actOnChat(row, read, shown,
                        HermesClient.ControlAction.RESPOND, null, shown.prompts.get(0), "deny"));
                assertEquals(5, gateway.writes.size());
            } finally {
                read.cancel();
            }
        }
    }

    @Test(timeout = 25000)
    public void attachesRealFileAndImageBytesToTheAdmittedPrompt() throws Exception {
        try (Gateway gateway = new Gateway()) {
            HermesClient.Conversation row = gateway.client.listConversations().get(0);
            HermesClient.SendReceipt receipt = gateway.client.sendMessage(row, "Review these",
                    List.of(attachment("draft.txt", false, "file contents"),
                            attachment("photo.png", true, "image contents")), null, true);
            assertEquals("queued", receipt.status);
            assertEquals(List.of("file.attach", "image.attach_bytes", "prompt.submit"),
                    gateway.writes);
            JSONObject prompt = gateway.sentPrompt.get();
            assertNotNull(prompt);
            assertEquals("Review these\n@file:\"attachments/draft.txt\"", prompt.getString("text"));
            assertTrue(prompt.getBoolean("queued"));
            assertEquals("/srv/hermes/cache/images/image.png",
                    prompt.getJSONArray("attachments").getJSONObject(0).getString("path"));
            assertEquals("image/png",
                    prompt.getJSONArray("attachments").getJSONObject(0).getString("mime"));

            gateway.failFileUpload = true;
            assertEquals(413, assertThrows(HermesClient.HttpException.class,
                    () -> gateway.client.sendMessage(row, "",
                            List.of(attachment("photo.png", true, "image contents"),
                                    attachment("draft.txt", false, "file contents")), null, false))
                    .statusCode);
            assertEquals(List.of("file.attach", "image.attach_bytes", "prompt.submit",
                    "image.attach_bytes"), gateway.writes);
        }
    }

    @Test(timeout = 25000)
    public void searchesTheOwningProfilesAndSendsFencedSidebarMutation() throws Exception {
        try (Gateway gateway = new Gateway()) {
            List<HermesClient.Conversation> found =
                    gateway.client.searchConversations("查找 / +", false, new HermesClient.RequestRead());
            assertEquals(1, found.size());
            assertEquals(PROFILE + ":root", found.get(0).key);
            assertEquals("matched snippet", found.get(0).preview);
            List<HermesClient.Conversation> archived = gateway.client.listConversations(true);
            assertEquals(1, archived.size());
            assertTrue(archived.get(0).archived);
            gateway.client.updateSidebar(found.get(0), HermesClient.SidebarField.TITLE, "Renamed");
            assertEquals(List.of("session.mutate"), gateway.writes);
        }
    }

    @Test(timeout = 25000)
    public void pagesStoredRowsAndRequiresExactOwnerBeforeExport() throws Exception {
        try (Gateway gateway = new Gateway()) {
            HermesClient.RequestRead read = new HermesClient.RequestRead();
            HermesClient.ConversationPage first = gateway.client.listConversationPage(false, 0, read);
            assertEquals(100, first.nextOffset);
            HermesClient.ConversationPage next = gateway.client.listConversationPage(
                    false, first.nextOffset, read);
            assertEquals(0, next.nextOffset);
            assertEquals("old", next.rows.get(0).id);

            HermesClient.Conversation row = gateway.client.getConversation(PROFILE, "tip", read);
            assertEquals(PROFILE, row.profile);
            assertThrows(IOException.class,
                    () -> gateway.client.getConversation("other-profile", "tip", read));
            ByteArrayOutputStream json = new ByteArrayOutputStream();
            gateway.client.exportConversation(row, read, json);
            JSONObject saved = new JSONObject(json.toString(StandardCharsets.UTF_8));
            assertEquals("tip", saved.getString("id"));
            assertTrue(saved.has("messages"));
        }
    }

    private static HermesClient.Attachment attachment(String name, boolean image, String content) {
        return new HermesClient.Attachment(name, image,
                () -> new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
    }

    private static final class Gateway implements AutoCloseable {
        private final MockWebServer server = new MockWebServer();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicReference<JSONObject> sentPrompt = new AtomicReference<>();
        private final List<String> writes = Collections.synchronizedList(new ArrayList<>());
        private final HermesClient client;
        private final JSONObject staged = new JSONObject().put("name", "web-staged")
                .put("profile_home", "/srv/hermes");
        private volatile long generation = 21;
        private volatile boolean failFileUpload;

        Gateway() throws IOException {
            server.start();
            client = new HermesClient(server.url("/webapp").toString(), url -> "auth=example");
            server.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) {
                    try {
                        return rest(request);
                    } catch (RuntimeException | AssertionError error) {
                        failure.compareAndSet(null, error);
                        return new MockResponse().setResponseCode(500);
                    }
                }
            });
        }

        private MockResponse rest(RecordedRequest request) {
            HttpUrl url = request.getRequestUrl();
            assertNotNull(url);
            String path = url.encodedPath();
            if (path.equals("/webapp/api/ws")) {
                assertTrue(request.getHeader("Sec-WebSocket-Protocol").contains("example-ticket"));
                return new MockResponse().setHeader("Sec-WebSocket-Protocol", PROTOCOL)
                        .withWebSocketUpgrade(new WebSocketListener() {
                            @Override
                            public void onOpen(WebSocket socket, Response response) {
                                socket.send(new JSONObject().put("jsonrpc", "2.0").put("method", "event")
                                        .put("params", new JSONObject().put("type", "gateway.ready")
                                                .put("payload", new JSONObject().put("session_authority", true)))
                                        .toString());
                            }

                            @Override
                            public void onMessage(WebSocket socket, String message) {
                                try {
                                    JSONObject frame = new JSONObject(message);
                                    JSONObject result = rpc(frame.getString("method"),
                                            frame.getJSONObject("params"));
                                    socket.send(new JSONObject().put("jsonrpc", "2.0")
                                            .put("id", frame.get("id")).put("result", result).toString());
                                } catch (RuntimeException | AssertionError error) {
                                    failure.compareAndSet(null, error);
                                    socket.cancel();
                                }
                            }

                            @Override
                            public void onClosing(WebSocket socket, int code, String reason) {
                                socket.close(code, reason);
                            }
                        });
            }
            assertEquals("auth=example", request.getHeader("Cookie"));
            switch (path) {
                case "/webapp/api/profiles/sessions":
                    JSONArray sessions = new JSONArray().put(stored("tip", false));
                    if ("only".equals(url.queryParameter("archived"))) {
                        sessions.put(stored("archived", true));
                    } else if ("100".equals(url.queryParameter("offset"))) {
                        sessions = new JSONArray().put(stored("old", false));
                    }
                    return json(new JSONObject().put("sessions", sessions).put("total", 101));
                case "/webapp/api/profiles":
                    return json(new JSONObject().put("profiles", new JSONArray()
                            .put(new JSONObject().put("name", PROFILE))));
                case "/webapp/api/sessions/search":
                    assertEquals("查找 / +", url.queryParameter("q"));
                    assertEquals(PROFILE, url.queryParameter("profile"));
                    JSONObject hit = stored("tip", false).put("lineage_root", "root")
                            .put("snippet", "matched snippet");
                    return json(new JSONObject().put("results", new JSONArray()
                            .put(hit).put(stored("archived", true))));
                case "/webapp/api/sessions/tip":
                    return json(stored("tip", false));
                case "/webapp/api/sessions/tip/export":
                    assertEquals(PROFILE, url.queryParameter("profile"));
                    return json(stored("tip", false).put("messages", new JSONArray()));
                case "/webapp/api/webapp/socket-ticket":
                    assertEquals("POST", request.getMethod());
                    assertEquals(PROFILE, url.queryParameter("profile"));
                    return json(new JSONObject().put("ticket", "example-ticket"));
                case "/webapp/api/chat/file-upload":
                    assertEquals("POST", request.getMethod());
                    assertEquals(PROFILE, url.queryParameter("profile"));
                    String body = request.getBody().readUtf8();
                    assertTrue(body.contains("name=\"file\"; filename=\"draft.txt\""));
                    assertTrue(body.contains("file contents"));
                    return failFileUpload ? new MockResponse().setResponseCode(413)
                            : json(new JSONObject().put("ok", true).put("staged_upload", staged));
                default:
                    throw new AssertionError("Unexpected REST path " + path);
            }
        }

        private JSONObject rpc(String method, JSONObject params) {
            if ("session.resume".equals(method)) {
                assertEquals("tip", params.getString("session_id"));
                assertTrue(params.getBoolean("omit_messages"));
                return snapshot();
            }
            assertEquals(SESSION, params.getString("session_id"));
            writes.add(method);
            switch (method) {
                case "approval.respond":
                    assertEquals(generation, params.getLong("execution_generation"));
                    assertEquals("approval-current", params.getString("prompt_id"));
                    assertEquals("once", params.getString("choice"));
                    return new JSONObject().put("status", "resolved")
                            .put("prompt_id", "approval-current");
                case "clarify.respond":
                    assertEquals(generation, params.getLong("execution_generation"));
                    assertEquals("clarify-current", params.getString("prompt_id"));
                    assertEquals("[\"A\",\"B\"]", params.getString("answer"));
                    return new JSONObject().put("status", "resolved")
                            .put("prompt_id", "clarify-current");
                case "prompt.steer_queued":
                case "prompt.cancel":
                    assertEquals("queued", params.getString("admission_id"));
                    assertEquals(2, params.length());
                    return pending("queued", SESSION);
                case "session.interrupt":
                    assertEquals(generation, params.getLong("execution_generation"));
                    return new JSONObject().put("authority_epoch", EPOCH).put("ref", ref(SESSION));
                case "file.attach":
                    assertEquals("draft.txt", params.getString("name"));
                    assertTrue(staged.similar(params.getJSONObject("staged_upload")));
                    assertFalse(params.has("path"));
                    return new JSONObject().put("attached", true)
                            .put("ref_text", "@file:\"attachments/draft.txt\"");
                case "image.attach_bytes":
                    assertEquals("photo.png", params.getString("filename"));
                    assertEquals("aW1hZ2UgY29udGVudHM=", params.getString("content_base64"));
                    return new JSONObject().put("attached", true)
                            .put("path", "/srv/hermes/cache/images/image.png").put("mime", "image/png");
                case "prompt.submit":
                    assertFalse(params.getString("submission_id").isEmpty());
                    sentPrompt.set(params);
                    return pending("sent", SESSION);
                case "session.mutate":
                    assertEquals("sidebar", params.getString("operation"));
                    assertFalse(params.getString("request_id").isEmpty());
                    assertEquals(31, params.getLong("expected_revision"));
                    assertEquals("Renamed", params.getJSONObject("payload").getString("title"));
                    return new JSONObject().put("session_id", SESSION).put("operation", "sidebar");
                default:
                    throw new AssertionError("Unexpected RPC " + method);
            }
        }

        private JSONObject snapshot() {
            JSONArray prompts = new JSONArray()
                    .put(new JSONObject().put("prompt_id", "approval-current").put("kind", "approval")
                            .put("execution_generation", generation).put("command", "printf example")
                            .put("choices", new JSONArray().put("once").put("deny").put("always")))
                    .put(new JSONObject().put("prompt_id", "clarify-current").put("kind", "clarify")
                            .put("execution_generation", generation).put("question", "Which options?")
                            .put("multi_select", true).put("choices", new JSONArray().put("A").put("B")))
                    .put(new JSONObject().put("prompt_id", "stale").put("kind", "approval")
                            .put("execution_generation", generation - 1));
            return new JSONObject().put("session_id", SESSION).put("subscription_id", "subscription")
                    .put("authority_epoch", EPOCH).put("execution_generation", generation)
                    .put("running", true).put("revision", 31).put("prompts", prompts)
                    .put("pending", new JSONArray().put(pending("queued", SESSION))
                            .put(pending("other-admission", "other-session")));
        }

        private JSONObject pending(String id, String session) {
            return new JSONObject().put("admission_id", id).put("ref", ref(session))
                    .put("status", "queued").put("authority_epoch", EPOCH)
                    .put("controls", new JSONArray().put("steer"));
        }

        private JSONObject stored(String id, boolean archived) {
            return new JSONObject().put("id", id).put("title", "Example").put("message_count", 5)
                    .put("profile", PROFILE).put("archived", archived);
        }

        private JSONObject ref(String id) {
            return new JSONObject().put("session_id", id).put("profile_id", PROFILE);
        }

        private MockResponse json(JSONObject value) {
            return new MockResponse().setHeader("Content-Type", "application/json")
                    .setBody(value.toString());
        }

        @Override
        public void close() throws Exception {
            server.close();
            if (failure.get() != null) {
                throw new AssertionError("Unexpected wire request", failure.get());
            }
        }
    }
}
