package cn.xhs.hermespocket;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class HermesChatHistoryWireTest {
    @Test(timeout = 15_000)
    public void readsOnlyTheLatestRowUntilWindowsAppendsMessagesToTheLineage() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start(InetAddress.getLoopbackAddress(), 0);
            HermesClient client = new HermesClient(server.url("/hermes/").toString(), url -> null);
            HermesClient.Conversation selected = selectChat(server, client);
            JSONObject previous = message(1, "assistant", "已有回复");
            JSONObject prompt = message(2, "user", "电脑端追加的问题");
            JSONObject reply = message(3, "assistant", "电脑端追加的回复");
            JSONObject tool = message(4, "tool", "不展示的工具结果");
            server.enqueue(history("root", previous));
            server.enqueue(history("root", previous));
            server.enqueue(history("tip", tool));
            server.enqueue(history("tip", previous, prompt, reply, tool));
            server.enqueue(history("tip", tool));

            HermesClient.MessageHistory initial = client.getMessageHistory(selected, null, null);
            assertEquals(List.of("已有回复"), contents(initial));
            assertSame(initial, client.getMessageHistory(selected, initial, null));
            HermesClient.MessageHistory updated = client.getMessageHistory(selected, initial, null);
            assertEquals(List.of("已有回复", "电脑端追加的问题", "电脑端追加的回复"), contents(updated));
            assertSame(updated, client.getMessageHistory(selected, updated, null));

            for (String limit : List.of("500", "1", "1", "500", "1")) {
                assertHistoryRequest(server, limit);
            }
            assertEquals(6, server.getRequestCount());
        }
    }

    @Test(timeout = 15_000)
    public void cancelsAnInFlightHistoryRead() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (MockWebServer server = new MockWebServer()) {
            server.start(InetAddress.getLoopbackAddress(), 0);
            HermesClient client = new HermesClient(server.url("/hermes/").toString(), url -> null);
            HermesClient.Conversation selected = selectChat(server, client);
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));

            HermesClient.HistoryRead read = new HermesClient.HistoryRead();
            Future<HermesClient.MessageHistory> pending = executor.submit(
                    () -> client.getMessageHistory(selected, null, read));
            assertHistoryRequest(server, "500");
            read.cancel();

            ExecutionException error = assertThrows(ExecutionException.class,
                    () -> pending.get(2, TimeUnit.SECONDS));
            assertTrue(error.getCause() instanceof IOException);
            assertEquals(2, server.getRequestCount());
        } finally {
            executor.shutdownNow();
        }
    }

    private static HermesClient.Conversation selectChat(MockWebServer server, HermesClient client)
            throws Exception {
        server.enqueue(new MockResponse().setBody(
                "{\"sessions\":[{\"id\":\"root\",\"profile\":\"research\",\"message_count\":1}]}"));
        HermesClient.Conversation selected = client.listConversations().get(0);
        assertNotNull(server.takeRequest(1, TimeUnit.SECONDS));
        return selected;
    }

    private static void assertHistoryRequest(MockWebServer server, String limit) throws Exception {
        RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals("GET", request.getMethod());
        HttpUrl url = request.getRequestUrl();
        assertNotNull(url);
        assertEquals("/hermes/api/sessions/root/messages", url.encodedPath());
        assertEquals("research", url.queryParameter("profile"));
        assertEquals("latest", url.queryParameter("order"));
        assertEquals(limit, url.queryParameter("limit"));
        assertEquals("false", url.queryParameter("inline_images"));
    }

    private static MockResponse history(String sessionId, JSONObject... messages) {
        JSONObject data = new JSONObject().put("session_id", sessionId)
                .put("messages", new JSONArray(messages));
        return new MockResponse().setBody(data.toString());
    }

    private static JSONObject message(int id, String role, String content) {
        return new JSONObject().put("id", id).put("role", role).put("content", content);
    }

    private static List<String> contents(HermesClient.MessageHistory history) {
        return history.messages.stream().map(message -> message.content).toList();
    }
}
