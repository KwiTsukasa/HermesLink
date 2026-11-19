package cn.xhs.hermespocket;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class HermesClientWireTest {
    private static final String PROFILE = "research";
    private static final String PROTOCOL = "hermes-gateway-v1";
    private static final String TICKET_PROTOCOL = "hermes-gateway-ticket.";
    private static final String USER_TEXT = "  在手机上接着聊\n第二行";

    @Test(timeout = 25_000)
    public void sendsToTheSelectedConversationOnTheSameWebapp() throws Exception {
        ProxySelector defaultProxy = ProxySelector.getDefault();
        ProxySelector.setDefault(new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                // The desktop JDK probes for a SOCKS proxy even when OkHttp selects a direct route.
                if ("socket".equals(uri.getScheme())) {
                    return List.of(Proxy.NO_PROXY);
                }
                throw new AssertionError("Plain HTTP must use a direct connection");
            }

            @Override
            public void connectFailed(URI uri, SocketAddress target, IOException error) {
                throw new AssertionError("Plain HTTP must use a direct connection", error);
            }
        });
        try (WireGateway gateway = new WireGateway()) {
            HermesClient signedOut = new HermesClient(gateway.base, url -> null);
            assertEquals(401, assertThrows(HermesClient.HttpException.class,
                    signedOut::listConversations).statusCode);

            HermesClient client = new HermesClient(gateway.base, new HermesClient.Auth() {
                @Override
                public String cookieHeader(String requestUrl) {
                    return null;
                }

                @Override
                public String accessToken(String requestUrl) {
                    assertTrue(requestUrl.startsWith(gateway.base + "/api/"));
                    return gateway.accessToken;
                }
            });
            String requestId = UUID.randomUUID().toString();
            HermesClient.Conversation created = client.createConversation(requestId);
            HermesClient.Conversation retried = client.createConversation(requestId);
            assertEquals(PROFILE, created.profile);
            assertEquals(created.key, retried.key);

            List<HermesClient.Conversation> conversations = client.listConversations();
            assertEquals(2, conversations.size());
            HermesClient.Conversation selected = conversations.get(0);
            assertEquals("selected-tip", selected.id);
            assertEquals(PROFILE + ":root", selected.key);
            assertEquals(List.of("电脑端的原消息"), contents(client.getMessages(selected)));
            assertEquals(List.of("电脑端的原消息", "电脑端刚补的回复"),
                    contents(client.getMessages(selected)));

            HermesClient.SendReceipt receipt = client.sendMessage(selected, USER_TEXT);
            assertEquals("queued", receipt.status);
            HermesClient.SendReceipt settled = client.getSendStatus(receipt);
            assertTrue(settled.isTerminal());
            assertEquals("completed", settled.outcome);
            assertEquals(List.of("电脑端的原消息", "电脑端刚补的回复", USER_TEXT, "手机收到回复"),
                    contents(client.getMessages(selected)));

            IOException missing = assertThrows(IOException.class,
                    () -> client.sendMessage(conversations.get(1), "这条消息不该被投递"));
            assertEquals(HermesText.t("native.hermes_could_not_find_this_conversation_refresh_the_conversation_list"),
                    missing.getMessage());
            assertEquals(List.of("session.create", "session.create", "session.resume",
                    "prompt.submit", "prompt.receipt", "session.resume"), gateway.calls);
            assertEquals(Map.of(requestId, created.id), gateway.creations);
            assertTrue(gateway.tickets.isEmpty());
        } finally {
            ProxySelector.setDefault(defaultProxy);
        }
    }

    private static List<String> contents(List<HermesClient.Message> messages) {
        return messages.stream().map(message -> message.content).toList();
    }

    private static final class WireGateway implements AutoCloseable {
        private final MockWebServer server = new MockWebServer();
        private final String accessToken = UUID.randomUUID().toString();
        private final String admissionId = UUID.randomUUID().toString();
        private final Map<String, String> tickets = new ConcurrentHashMap<>();
        private final Map<String, String> creations = new ConcurrentHashMap<>();
        private final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        private final AtomicInteger historyReads = new AtomicInteger();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final List<JSONObject> history = Collections.synchronizedList(new ArrayList<>(
                List.of(message(1, "user", "电脑端的原消息"))));
        private final String origin;
        private final String base;

        private WireGateway() throws IOException {
            server.start(InetAddress.getLoopbackAddress(), 0);
            origin = server.url("/").toString().replaceFirst("/$", "");
            base = origin + "/hermes";
            server.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) {
                    try {
                        return handle(request);
                    } catch (RuntimeException | AssertionError error) {
                        failure.compareAndSet(null, error);
                        return new MockResponse().setResponseCode(500);
                    }
                }
            });
        }

        private MockResponse handle(RecordedRequest request) {
            HttpUrl url = request.getRequestUrl();
            assertNotNull(url);
            if ("/hermes/api/ws".equals(url.encodedPath())) {
                return upgrade(request, url);
            }
            if (!("Bearer " + accessToken).equals(request.getHeader("Authorization"))) {
                return new MockResponse().setResponseCode(401);
            }
            switch (url.encodedPath()) {
                case "/hermes/api/profiles/sessions":
                    assertEquals("GET", request.getMethod());
                    assertEquals("1", url.queryParameter("min_messages"));
                    return json(new JSONObject().put("sessions", new JSONArray(List.of(
                            row("selected-tip", "root", 1), row("old-tip", "root", 1),
                            row("draft", "", 0), row("lost", "", 1)))));
                case "/hermes/api/sessions/selected-tip/messages":
                    assertEquals("GET", request.getMethod());
                    assertEquals(PROFILE, url.queryParameter("profile"));
                    assertEquals("false", url.queryParameter("inline_images"));
                    JSONArray snapshot;
                    synchronized (history) {
                        snapshot = new JSONArray(history);
                        if (historyReads.getAndIncrement() == 0) {
                            history.add(message(2, "assistant", "电脑端刚补的回复"));
                            history.add(message(3, "user", "内部记录").put("display_kind", "hidden"));
                        }
                    }
                    return json(new JSONObject().put("messages", snapshot));
                case "/hermes/api/webapp/socket-ticket":
                    assertEquals("POST", request.getMethod());
                    assertEquals(origin, request.getHeader("Origin"));
                    assertEquals("gateway", url.queryParameter("socket"));
                    String profile = url.queryParameter("profile");
                    assertTrue(profile == null || PROFILE.equals(profile));
                    String ticket = UUID.randomUUID().toString();
                    tickets.put(ticket, profile == null ? "" : profile);
                    return json(new JSONObject().put("ticket", ticket));
                default:
                    throw new AssertionError("Unexpected request path " + url.encodedPath());
            }
        }

        private MockResponse upgrade(RecordedRequest request, HttpUrl url) {
            assertEquals("GET", request.getMethod());
            assertNull(url.query());
            assertNull(request.getHeader("Authorization"));
            assertEquals(origin, request.getHeader("Origin"));
            String offered = request.getHeader("Sec-WebSocket-Protocol");
            assertNotNull(offered);
            assertTrue(offered.startsWith(PROTOCOL + ", " + TICKET_PROTOCOL));
            String profile = tickets.remove(offered.substring((PROTOCOL + ", " + TICKET_PROTOCOL).length()));
            assertNotNull("Expected an issued single-use ticket", profile);
            return new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
                private String attachedId;

                @Override
                public void onOpen(WebSocket socket, Response response) {
                    socket.send(event("gateway.ready", new JSONObject().put("session_authority", true)));
                }

                @Override
                public void onMessage(WebSocket socket, String text) {
                    try {
                        JSONObject request = new JSONObject(text);
                        assertEquals("2.0", request.getString("jsonrpc"));
                        String method = request.getString("method");
                        JSONObject params = request.getJSONObject("params");
                        calls.add(method);
                        JSONObject response = new JSONObject().put("jsonrpc", "2.0")
                                .put("id", request.getString("id"));
                        if ("session.resume".equals(method) && "lost".equals(params.getString("session_id"))) {
                            response.put("error", new JSONObject().put("code", 4004).put("message", "not_found"));
                        } else {
                            response.put("result", reply(method, params));
                        }
                        socket.send(event("message.delta", new JSONObject().put("text", "流式事件")));
                        socket.send(response.toString());
                    } catch (RuntimeException | AssertionError error) {
                        failure.compareAndSet(null, error);
                        socket.close(1011, null);
                    }
                }

                @Override
                public void onClosing(WebSocket socket, int code, String reason) {
                    socket.close(code, null);
                }

                private JSONObject reply(String method, JSONObject params) {
                    if ("session.create".equals(method)) {
                        assertEquals("", profile);
                        assertEquals("gui", params.getString("source"));
                        assertEquals("hermes-pocket", params.getJSONObject("client").getString("name"));
                        String key = params.getString("request_id");
                        UUID.fromString(key);
                        attachedId = creations.computeIfAbsent(key, unused -> UUID.randomUUID().toString());
                        return snapshot(attachedId).put("info", new JSONObject().put("profile_name", PROFILE));
                    }
                    assertEquals(PROFILE, profile);
                    String requestedId = params.getString("session_id");
                    switch (method) {
                        case "session.resume":
                            assertEquals("selected-tip", requestedId);
                            assertTrue(params.getBoolean("omit_messages"));
                            attachedId = "live-resolved";
                            return snapshot(attachedId);
                        case "prompt.submit":
                            assertEquals("live-resolved", attachedId);
                            assertEquals(attachedId, requestedId);
                            UUID.fromString(params.getString("submission_id"));
                            assertEquals(USER_TEXT, params.getString("text"));
                            history.add(message(4, "user", USER_TEXT));
                            history.add(message(5, "assistant", "手机收到回复"));
                            return receipt(requestedId, "queued", JSONObject.NULL);
                        case "prompt.receipt":
                            assertEquals("live-resolved", requestedId);
                            assertEquals(admissionId, params.getString("admission_id"));
                            return receipt(requestedId, "terminal", "completed");
                        default:
                            throw new AssertionError("Unexpected RPC " + method);
                    }
                }
            }).setHeader("Sec-WebSocket-Protocol", PROTOCOL);
        }

        private static String event(String type, JSONObject payload) {
            return new JSONObject().put("jsonrpc", "2.0").put("method", "event")
                    .put("params", new JSONObject().put("type", type).put("payload", payload))
                    .toString();
        }

        private static JSONObject snapshot(String id) {
            return new JSONObject().put("session_id", id).put("subscription_id", UUID.randomUUID().toString());
        }

        private JSONObject receipt(String id, String status, Object outcome) {
            return new JSONObject().put("admission_id", admissionId)
                    .put("ref", new JSONObject().put("session_id", id))
                    .put("status", status).put("outcome", outcome);
        }

        private static JSONObject row(String id, String root, int messageCount) {
            return new JSONObject().put("id", id).put("_lineage_root_id", root).put("profile", PROFILE)
                    .put("title", id).put("message_count", messageCount);
        }

        private static JSONObject message(int id, String role, String content) {
            return new JSONObject().put("id", id).put("role", role).put("content", content);
        }

        private static MockResponse json(JSONObject response) {
            return new MockResponse().setHeader("Content-Type", "application/json")
                    .setBody(response.toString());
        }

        @Override
        public void close() throws IOException {
            server.close();
            assertNull("Mock gateway rejected a request", failure.get());
        }
    }
}
