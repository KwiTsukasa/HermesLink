package cn.xhs.hermespocket;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.net.InetAddress;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class KanbanEventStreamTest {
    @Test(timeout = 15000)
    public void subscribesWithNativeAuthTicketAndContinuesFromTheLastBoardCursor() throws Exception {
        try (MockWebServer server = server()) {
            String origin = "http://127.0.0.1:" + server.getPort();
            KanbanEventStream stream = stream(server, new AtomicBoolean(true));
            try {
                server.enqueue(ticket("kanban_ticket_1"));
                server.enqueue(events(frame(1, 82), true));
                start(stream);
                RecordedRequest ticket = request(server);
                assertEquals("POST", ticket.getMethod());
                assertEquals("/hermes/api/auth/ws-ticket", ticket.getPath());
                assertEquals("Bearer test-board-login", ticket.getHeader("Authorization"));
                assertEquals(origin, ticket.getHeader("Origin"));

                RecordedRequest upgrade = request(server);
                assertEquals("/hermes/api/plugins/kanban/events", upgrade.getRequestUrl().encodedPath());
                assertEquals("kanban_ticket_1", upgrade.getRequestUrl().queryParameter("ticket"));
                assertEquals("work", upgrade.getRequestUrl().queryParameter("profile"));
                assertEquals("triage", upgrade.getRequestUrl().queryParameter("board"));
                assertEquals(origin, upgrade.getHeader("Origin"));
                assertNull(upgrade.getHeader("Authorization"));
                assertNull(upgrade.getRequestUrl().queryParameter("since"));
                assertNull(upgrade.getRequestUrl().queryParameter("token"));

                JSONObject first = snapshot(stream, "closed", 80);
                JSONArray firstEvents = first.getJSONArray("events");
                assertEquals("82", firstEvents.getJSONObject(0).getString("id"));
                assertEquals("3", firstEvents.getJSONObject(79).getString("id"));
                assertFalse(first.toString().contains("fixture_secret"));

                server.enqueue(ticket("kanban_ticket_2"));
                server.enqueue(events(frame(82, 84), false));
                start(stream);
                assertEquals("/hermes/api/auth/ws-ticket", request(server).getPath());
                RecordedRequest resumed = request(server);
                assertEquals("kanban_ticket_2", resumed.getRequestUrl().queryParameter("ticket"));
                assertEquals("82", resumed.getRequestUrl().queryParameter("since"));
                JSONArray rows = snapshot(stream, "live", 80).getJSONArray("events");
                assertEquals("84", rows.getJSONObject(0).getString("id"));
                assertEquals("83", rows.getJSONObject(1).getString("id"));
                assertEquals("82", rows.getJSONObject(2).getString("id"));
                assertEquals("81", rows.getJSONObject(3).getString("id"));
            } finally {
                stream.stop();
            }
        }
    }

    @Test(timeout = 15000)
    public void separatesInvalidFramesFromUnavailableBoardsAndClearsOnStop() throws Exception {
        try (MockWebServer server = server()) {
            KanbanEventStream stream = stream(server, new AtomicBoolean(true));
            try {
                server.enqueue(ticket("bad_frame_ticket"));
                String malformed = "{\"cursor\":3,\"events\":[{\"id\":1,\"kind\":\"complete\","
                        + "\"task_id\":\"task_1\",\"created_at\":10}]}";
                server.enqueue(events(malformed, false));
                start(stream);
                snapshot(stream, "invalid_data", 0);
                request(server);
                request(server);

                server.enqueue(ticket("disabled_board_ticket"));
                server.enqueue(new MockResponse().setResponseCode(403));
                start(stream);
                snapshot(stream, "unavailable", 0);
                request(server);
                RecordedRequest retry = request(server);
                assertNull(retry.getRequestUrl().queryParameter("since"));
                stream.stop();
                snapshot(stream, "closed", 0);
                assertEquals(4, server.getRequestCount());
            } finally {
                stream.stop();
            }
        }
    }

    @Test(timeout = 15000)
    public void neverOpensAfterCancellationOrUsesAnUntrustedBoardSelector() throws Exception {
        try (MockWebServer server = server()) {
            AtomicBoolean loginIsCurrent = new AtomicBoolean(true);
            KanbanEventStream stream = stream(server, loginIsCurrent);
            Runnable prepared = stream.prepareStart();
            assertNotNull(prepared);
            stream.stop();
            prepared.run();
            assertEquals(0, server.getRequestCount());

            loginIsCurrent.set(false);
            assertNull(stream.prepareStart());
            assertThrows(IllegalArgumentException.class,
                    () -> new KanbanEventStream(client(server), () -> true, "work", "x&ticket=another"));
            assertThrows(IllegalArgumentException.class,
                    () -> new KanbanEventStream(client(server), () -> true, "../home", "triage"));
        }
    }

    private static void start(KanbanEventStream stream) {
        Runnable start = stream.prepareStart();
        assertNotNull(start);
        start.run();
    }

    private static JSONObject snapshot(KanbanEventStream stream, String state, int count) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        JSONObject result;
        do {
            result = new JSONObject(stream.snapshot());
            if (state.equals(result.getString("state")) && result.getJSONArray("events").length() == count) {
                return result;
            }
            Thread.sleep(10);
        } while (System.nanoTime() < end);
        assertEquals(state, result.getString("state"));
        assertEquals(count, result.getJSONArray("events").length());
        return result;
    }

    private static String frame(int first, int last) {
        JSONArray messages = new JSONArray();
        for (int event = first; event <= last; event++) {
            messages.put(new JSONObject().put("id", event).put("task_id", "task_" + event).put("kind", "complete")
                    .put("created_at", 1000 + event).put("payload", new JSONObject().put("secret", "fixture_secret")));
        }
        return new JSONObject().put("events", messages).put("cursor", last).toString();
    }

    private static MockResponse ticket(String value) {
        return new MockResponse().setBody(new JSONObject().put("ticket", value).toString());
    }

    private static MockResponse events(String frame, boolean closeAfterFrame) {
        return new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
            @Override
            public void onOpen(WebSocket socket, Response response) {
                assertTrue(socket.send(frame));
                if (closeAfterFrame) {
                    socket.close(1000, null);
                }
            }

            @Override
            public void onMessage(WebSocket socket, String message) {
                throw new AssertionError("An event viewer never publishes messages.");
            }
        });
    }

    private static MockWebServer server() throws Exception {
        MockWebServer server = new MockWebServer();
        server.start(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 0);
        return server;
    }

    private static HermesClient client(MockWebServer server) {
        return new HermesClient("http://127.0.0.1:" + server.getPort() + "/hermes/", new HermesClient.Auth() {
            @Override
            public String cookieHeader(String requestUrl) {
                return null;
            }

            @Override
            public String accessToken(String requestUrl) {
                return "test-board-login";
            }
        });
    }

    private static KanbanEventStream stream(MockWebServer server, AtomicBoolean currentLogin) {
        return new KanbanEventStream(client(server), currentLogin::get, "work", "triage");
    }

    private static RecordedRequest request(MockWebServer server) throws Exception {
        RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
        assertNotNull(request);
        return request;
    }
}
