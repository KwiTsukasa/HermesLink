package cn.xhs.hermespocket;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class HermesChatStreamWireTest {
    private static final String SESSION = "active-tip";
    private static final String ROOT = "stored-root";
    private static final String PROFILE = "research";
    private static final String PROTOCOL = "hermes-gateway-v1";
    private static final String TICKET_PREFIX = "hermes-gateway-ticket.";
    private static final String ADMISSION = "own-admission";
    private static final String PROMPT = "请逐段回答";
    private static final String TURN = "own-turn";
    private static final String REPLAY_EPOCH = "stream-epoch";
    private static final int AUTHORITY_EPOCH = 7;
    private static final int GENERATION = 3;
    private static final int TIMEOUT_SECONDS = 5;

    @Test(timeout = 20_000)
    public void deliversOwnTextBeforeCompletionAndReconcilesReplayedAndSealedSegments()
            throws Exception {
        try (StreamGateway gateway = new StreamGateway("started")) {
            HermesClient client = gateway.client();
            HermesClient.Conversation selected = client.listConversations().get(0);
            BlockingQueue<HermesClient.StreamUpdate> updates = new LinkedBlockingQueue<>();
            HermesClient.SendRead send = new HermesClient.SendRead(updates::add);

            try {
                HermesClient.SendReceipt receipt = client.sendMessage(selected, PROMPT, send);
                assertEquals(ADMISSION, receipt.admissionId);
                assertFalse(receipt.isTerminal());

                // The server emitted this before the receipt and still withholds the final frame.
                assertPreview(updates, "甲🙂");
                assertEquals(0, gateway.historyReads.get());
                assertEquals(0, gateway.receiptReads.get());

                gateway.delta(SESSION, "foreign-admission", AUTHORITY_EPOCH, GENERATION,
                        TURN, 0, 3, "别的请求");
                gateway.delta("foreign-session", ADMISSION, AUTHORITY_EPOCH, GENERATION,
                        TURN, 0, 3, "别的会话");
                gateway.delta(SESSION, ADMISSION, AUTHORITY_EPOCH + 1, GENERATION,
                        TURN, 0, 3, "另一个服务实例");
                gateway.delta(SESSION, ADMISSION, AUTHORITY_EPOCH, GENERATION - 1,
                        TURN, 0, 3, "已失效的执行");
                gateway.delta(SESSION, ADMISSION, AUTHORITY_EPOCH, GENERATION,
                        "old-turn", 0, 3, "已失效的回复");
                gateway.event("reasoning.delta", SESSION, ADMISSION, AUTHORITY_EPOCH, GENERATION,
                        new JSONObject().put("text", "内部推理"));
                gateway.event("tool.complete", SESSION, ADMISSION, AUTHORITY_EPOCH, GENERATION,
                        new JSONObject().put("text", "内部工具结果"));
                gateway.event("message.complete", SESSION, "foreign-admission",
                        AUTHORITY_EPOCH, GENERATION, new JSONObject().put("text", "无关请求已完成"));
                gateway.delta(SESSION, ADMISSION, AUTHORITY_EPOCH, GENERATION, TURN, 0, 0, "甲🙂");
                gateway.delta(SESSION, ADMISSION, AUTHORITY_EPOCH, GENERATION, TURN, 0, 1, "🙂乙");
                assertPreview(updates, "甲🙂乙");

                gateway.event("message.delta", SESSION, ADMISSION, AUTHORITY_EPOCH, GENERATION,
                        new JSONObject().put("text", JSONObject.NULL));
                JSONObject seal = new JSONObject().put("turn_id", TURN)
                        .put("segment", 0).put("text", "甲🙂乙。").put("already_streamed", true);
                gateway.event("message.interim", SESSION, ADMISSION, AUTHORITY_EPOCH, GENERATION, seal);
                assertPreview(updates, "甲🙂乙。");

                gateway.event("message.interim", SESSION, ADMISSION, AUTHORITY_EPOCH, GENERATION, seal);
                gateway.delta(SESSION, ADMISSION, AUTHORITY_EPOCH, GENERATION, TURN, 1, 0, "\n\n丙");
                assertPreview(updates, "甲🙂乙。\n\n丙");

                // Managed workers can emit a sequenced chunk without an offset or segment.
                gateway.event("message.delta", SESSION, ADMISSION, AUTHORITY_EPOCH, GENERATION,
                        new JSONObject().put("text", "过渡"));
                assertPreview(updates, "甲🙂乙。\n\n丙过渡");

                gateway.event("message.complete", SESSION, ADMISSION, AUTHORITY_EPOCH, GENERATION,
                        new JSONObject().put("text", "丙丁").put("status", "complete")
                                .put("reasoning", "最终帧中的内部推理")
                                .put("persisted_turn", new JSONObject().put("complete", true)
                                        .put("user_row_id", 21).put("final_assistant_row_id", 22)
                                        .put("submission_id", gateway.submissionId.get())));
                HermesClient.StreamUpdate completed = nextUpdate(updates);
                assertEquals("甲🙂乙。\n\n丙丁", completed.text);
                assertTrue(completed.completed);
                assertFalse(completed.interrupted);
                assertEquals("21", completed.userRowId);
                assertEquals("22", completed.assistantRowId);
                gateway.assertStreamClosed();
                assertEquals(1, gateway.submissions.get());
            } finally {
                send.stopStreaming();
            }
        }
    }

    @Test(timeout = 20_000)
    public void projectsOnlyThisAdmissionsToolActivityAndKeepsTextStreaming() throws Exception {
        try (StreamGateway gateway = new StreamGateway("started")) {
            HermesClient client = gateway.client();
            HermesClient.Conversation selected = client.listConversations().get(0);
            BlockingQueue<HermesClient.StreamUpdate> updates = new LinkedBlockingQueue<>();
            HermesClient.SendRead send = new HermesClient.SendRead(updates::add);
            try {
                client.sendMessage(selected, PROMPT, send);
                assertPreview(updates, "甲🙂");
                JSONObject tool = new JSONObject().put("tool_id", "one").put("name", "read_file");
                gateway.event("tool.start", SESSION, "other-admission", AUTHORITY_EPOCH,
                        GENERATION, new JSONObject().put("tool_id", "other").put("name", "private_tool"));
                gateway.event("tool.start", SESSION, ADMISSION, AUTHORITY_EPOCH, GENERATION, tool);
                HermesClient.StreamUpdate started = nextUpdate(updates);
                assertEquals("甲🙂", started.text);
                assertEquals(1, started.tools.size());
                assertEquals("read_file", started.tools.get(0).name);
                assertFalse(started.tools.get(0).completed);
                gateway.event("tool.progress", SESSION, ADMISSION, AUTHORITY_EPOCH, GENERATION,
                        tool.put("percent", 40).put("message", "正在读取"));
                assertEquals(40, nextUpdate(updates).tools.get(0).percent);
                gateway.event("tool.complete", SESSION, ADMISSION, AUTHORITY_EPOCH, GENERATION,
                        tool.put("duration_s", 1.5).put("summary", "已读取")
                                .put("result_text", "raw tool data"));
                HermesReplyStream.ToolActivity completed = nextUpdate(updates).tools.get(0);
                assertTrue(completed.completed);
                assertEquals(1.5, completed.duration, 0.01);
                assertEquals("已读取", completed.detail);
                gateway.delta(SESSION, ADMISSION, AUTHORITY_EPOCH, GENERATION, TURN, 0, 3, "乙");
                assertPreview(updates, "甲🙂乙");
            } finally {
                send.stopStreaming();
            }
        }
    }

    @Test(timeout = 20_000)
    public void readsTheOriginalReceiptAndStoredReplyAfterTheStreamDisconnects() throws Exception {
        try (StreamGateway gateway = new StreamGateway("queued")) {
            HermesClient client = gateway.client();
            HermesClient.Conversation selected = client.listConversations().get(0);
            BlockingQueue<HermesClient.StreamUpdate> updates = new LinkedBlockingQueue<>();
            HermesClient.SendRead send = new HermesClient.SendRead(updates::add);

            try {
                HermesClient.SendReceipt receipt = client.sendMessage(selected, PROMPT, send);
                assertEquals("queued", receipt.status);
                assertPreview(updates, "甲🙂");
                assertTrue(gateway.stream.get().close(1011, "fixture disconnected"));
                HermesClient.StreamUpdate disconnected = nextUpdate(updates);
                assertTrue(disconnected.interrupted);
                assertFalse(disconnected.completed);

                HermesClient.SendReceipt settled = client.getSendStatus(receipt);
                assertEquals(ADMISSION, settled.admissionId);
                assertEquals("completed", settled.outcome);
                assertTrue(settled.isTerminal());
                List<HermesClient.Message> stored = client.getMessages(selected);
                assertEquals(List.of("20", "21", "22"),
                        stored.stream().map(message -> message.id).toList());
                assertEquals(List.of("已有记录", PROMPT, "完整回复"),
                        stored.stream().map(message -> message.content).toList());
                assertEquals(1, gateway.submissions.get());
                assertEquals(1, gateway.receiptReads.get());
                assertEquals(1, gateway.historyReads.get());
                assertEquals(2, gateway.upgrades.get());
            } finally {
                send.stopStreaming();
            }
        }
    }

    @Test(timeout = 20_000)
    public void stoppingAnAdmittedStreamClosesTheSocketAndLeavesItsReceiptReadable() throws Exception {
        try (StreamGateway gateway = new StreamGateway("started")) {
            HermesClient client = gateway.client();
            HermesClient.Conversation selected = client.listConversations().get(0);
            BlockingQueue<HermesClient.StreamUpdate> updates = new LinkedBlockingQueue<>();
            HermesClient.SendRead send = new HermesClient.SendRead(updates::add);

            try {
                HermesClient.SendReceipt receipt = client.sendMessage(selected, PROMPT, send);
                assertPreview(updates, "甲🙂");
                send.stopStreaming();
                send.stopStreaming();
                gateway.assertStreamClosed();
                assertTrue(client.getSendStatus(receipt).isTerminal());
                assertEquals(1, gateway.submissions.get());
                assertEquals(1, gateway.receiptReads.get());
                assertNull(updates.poll());
            } finally {
                send.stopStreaming();
            }
        }
    }

    @Test(timeout = 20_000)
    public void cancellingBeforeTheReceiptDropsBufferedTextAndNeverResubmits() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (StreamGateway gateway = new StreamGateway("queued", false)) {
            HermesClient client = gateway.client();
            HermesClient.Conversation selected = client.listConversations().get(0);
            BlockingQueue<HermesClient.StreamUpdate> updates = new LinkedBlockingQueue<>();
            HermesClient.SendRead send = new HermesClient.SendRead(updates::add);
            try {
                Future<HermesClient.SendReceipt> pending = executor.submit(
                        () -> client.sendMessage(selected, PROMPT, send));
                assertTrue(gateway.submitted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

                send.cancel();
                gateway.assertStreamClosed();
                ExecutionException failure = assertThrows(ExecutionException.class,
                        () -> pending.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
                assertTrue(failure.getCause() instanceof IOException);
                assertNull(updates.poll());
                assertEquals(1, gateway.submissions.get());
                assertEquals(1, gateway.upgrades.get());
            } finally {
                send.cancel();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static HermesClient.StreamUpdate nextUpdate(
            BlockingQueue<HermesClient.StreamUpdate> updates) throws InterruptedException {
        HermesClient.StreamUpdate update = updates.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertNotNull("Expected a chat stream update", update);
        return update;
    }

    private static void assertPreview(BlockingQueue<HermesClient.StreamUpdate> updates, String text)
            throws InterruptedException {
        HermesClient.StreamUpdate update = nextUpdate(updates);
        assertFalse(update.completed);
        assertFalse(update.interrupted);
        assertEquals(text, update.text);
        assertNull(update.userRowId);
        assertNull(update.assistantRowId);
    }

    private static final class StreamGateway implements AutoCloseable {
        private final MockWebServer server = new MockWebServer();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicReference<WebSocket> stream = new AtomicReference<>();
        private final AtomicReference<String> submissionId = new AtomicReference<>();
        private final AtomicInteger sequence = new AtomicInteger(20);
        private final AtomicInteger submissions = new AtomicInteger();
        private final AtomicInteger receiptReads = new AtomicInteger();
        private final AtomicInteger historyReads = new AtomicInteger();
        private final AtomicInteger upgrades = new AtomicInteger();
        private final CountDownLatch submitted = new CountDownLatch(1);
        private final CountDownLatch streamClosed = new CountDownLatch(1);
        private final BlockingQueue<String> tickets = new LinkedBlockingQueue<>();
        private final String initialReceiptStatus;
        private final boolean acknowledgeSubmit;

        private StreamGateway(String initialReceiptStatus) throws IOException {
            this(initialReceiptStatus, true);
        }

        private StreamGateway(String initialReceiptStatus, boolean acknowledgeSubmit) throws IOException {
            this.initialReceiptStatus = initialReceiptStatus;
            this.acknowledgeSubmit = acknowledgeSubmit;
            server.start(InetAddress.getLoopbackAddress(), 0);
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

        private HermesClient client() {
            return new HermesClient(server.url("/hermes/").toString(), url -> "session=fixture");
        }

        private MockResponse handle(RecordedRequest request) {
            HttpUrl url = request.getRequestUrl();
            assertNotNull(url);
            switch (url.encodedPath()) {
                case "/hermes/api/profiles/sessions":
                    return json(new JSONObject().put("sessions", new JSONArray().put(new JSONObject()
                            .put("id", ROOT).put("profile", PROFILE).put("message_count", 1))));
                case "/hermes/api/webapp/socket-ticket":
                    assertEquals("POST", request.getMethod());
                    assertEquals("session=fixture", request.getHeader("Cookie"));
                    assertEquals("gateway", url.queryParameter("socket"));
                    assertEquals(PROFILE, url.queryParameter("profile"));
                    String ticket = UUID.randomUUID().toString();
                    tickets.add(ticket);
                    return json(new JSONObject().put("ticket", ticket));
                case "/hermes/api/ws":
                    assertNull(request.getHeader("Cookie"));
                    assertNull(url.query());
                    assertEquals(PROTOCOL + ", " + TICKET_PREFIX + tickets.remove(),
                            request.getHeader("Sec-WebSocket-Protocol"));
                    return upgrade(upgrades.incrementAndGet() == 1);
                case "/hermes/api/sessions/" + ROOT + "/messages":
                    assertEquals("GET", request.getMethod());
                    assertEquals(PROFILE, url.queryParameter("profile"));
                    historyReads.incrementAndGet();
                    return json(new JSONObject().put("session_id", SESSION)
                            .put("messages", new JSONArray()
                                    .put(message(20, "assistant", "已有记录"))
                                    .put(message(21, "user", PROMPT))
                                    .put(message(22, "assistant", "完整回复"))));
                default:
                    throw new AssertionError("Unexpected path " + url.encodedPath());
            }
        }

        private MockResponse upgrade(boolean sending) {
            return new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
                @Override
                public void onOpen(WebSocket socket, Response response) {
                    socket.send(frame(new JSONObject().put("type", "gateway.ready")
                            .put("payload", new JSONObject().put("session_authority", true))));
                }

                @Override
                public void onMessage(WebSocket socket, String text) {
                    try {
                        JSONObject request = new JSONObject(text);
                        assertEquals("2.0", request.getString("jsonrpc"));
                        JSONObject params = request.getJSONObject("params");
                        JSONObject result = reply(socket, sending, request.getString("method"), params);
                        if (result != null) {
                            socket.send(new JSONObject().put("jsonrpc", "2.0")
                                    .put("id", request.getString("id")).put("result", result).toString());
                        }
                    } catch (RuntimeException | AssertionError error) {
                        failure.compareAndSet(null, error);
                        socket.close(1011, "Unexpected RPC");
                    }
                }

                @Override
                public void onClosing(WebSocket socket, int code, String reason) {
                    socket.close(code, null);
                }

                @Override
                public void onClosed(WebSocket socket, int code, String reason) {
                    if (sending) {
                        streamClosed.countDown();
                    }
                }

                @Override
                public void onFailure(WebSocket socket, Throwable error, Response response) {
                    if (sending) {
                        streamClosed.countDown();
                    }
                }
            }).setHeader("Sec-WebSocket-Protocol", PROTOCOL);
        }

        private JSONObject reply(WebSocket socket, boolean sending, String method, JSONObject params) {
            switch (method) {
                case "session.resume":
                    assertTrue(sending);
                    assertEquals(ROOT, params.getString("session_id"));
                    assertTrue(params.getBoolean("omit_messages"));
                    return new JSONObject().put("session_id", SESSION).put("stored_session_id", SESSION)
                            .put("subscription_id", "fixture-subscription").put("last_sequence", 20)
                            .put("authority_epoch", AUTHORITY_EPOCH).put("replay_epoch", REPLAY_EPOCH)
                            .put("execution_generation", GENERATION - 1);
                case "prompt.submit":
                    assertTrue("The submit must stay on the original socket", sending);
                    assertEquals(SESSION, params.getString("session_id"));
                    assertEquals(PROMPT, params.getString("text"));
                    assertEquals(1, submissions.incrementAndGet());
                    submissionId.set(params.getString("submission_id"));
                    UUID.fromString(submissionId.get());
                    stream.set(socket);
                    event("message.start", SESSION, ADMISSION, AUTHORITY_EPOCH, GENERATION,
                            new JSONObject().put("turn_id", TURN));
                    delta(SESSION, "old-admission", AUTHORITY_EPOCH, GENERATION - 1,
                            "older-turn", 0, 0, "此前的回复");
                    delta(SESSION, ADMISSION, AUTHORITY_EPOCH, GENERATION, TURN, 0, 0, "甲🙂");
                    submitted.countDown();
                    return acknowledgeSubmit ? receipt(initialReceiptStatus, JSONObject.NULL) : null;
                case "prompt.receipt":
                    assertFalse(sending);
                    assertEquals(SESSION, params.getString("session_id"));
                    assertEquals(ADMISSION, params.getString("admission_id"));
                    receiptReads.incrementAndGet();
                    return receipt("terminal", "completed");
                default:
                    throw new AssertionError("Unexpected RPC " + method);
            }
        }

        private void delta(String session, String admission, int epoch, int generation,
                String turn, int segment, int offset, String text) {
            event("message.delta", session, admission, epoch, generation,
                    new JSONObject().put("turn_id", turn).put("segment", segment)
                            .put("offset", offset).put("text", text));
        }

        private void event(String type, String session, String admission, int epoch, int generation,
                JSONObject payload) {
            assertTrue(stream.get().send(frame(new JSONObject().put("type", type)
                    .put("session_id", session).put("admission_id", admission)
                    .put("authority_epoch", epoch).put("execution_generation", generation)
                    .put("replay_epoch", REPLAY_EPOCH).put("seq", sequence.incrementAndGet())
                    .put("payload", payload))));
        }

        private void assertStreamClosed() throws InterruptedException {
            assertTrue("The gateway still has a live stream",
                    streamClosed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        }

        private static JSONObject receipt(String status, Object outcome) {
            return new JSONObject().put("admission_id", ADMISSION)
                    .put("ref", new JSONObject().put("profile_id", PROFILE).put("session_id", SESSION))
                    .put("sequence", 12).put("status", status).put("outcome", outcome)
                    .put("authority_epoch", AUTHORITY_EPOCH)
                    .put("execution_generation", "queued".equals(status) ? JSONObject.NULL : GENERATION);
        }

        private static JSONObject message(int id, String role, String text) {
            return new JSONObject().put("id", id).put("role", role).put("content", text);
        }

        private static String frame(JSONObject params) {
            return new JSONObject().put("jsonrpc", "2.0").put("method", "event")
                    .put("params", params).toString();
        }

        private static MockResponse json(JSONObject data) {
            return new MockResponse().setHeader("Content-Type", "application/json")
                    .setBody(data.toString());
        }

        @Override
        public void close() throws IOException {
            server.close();
            assertNull("Mock gateway rejected a request", failure.get());
        }
    }
}
