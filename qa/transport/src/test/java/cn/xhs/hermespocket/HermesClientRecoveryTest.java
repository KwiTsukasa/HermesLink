package cn.xhs.hermespocket;

import org.junit.Test;

import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class HermesClientRecoveryTest {
    @Test(timeout = 15_000)
    public void recoversHistoryOnAFreshSocketAfterAnEofOnAReusedConnection() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start(InetAddress.getLoopbackAddress(), 0);
            server.enqueue(sessions());
            server.enqueue(disconnectBeforeHeaders());
            server.enqueue(new MockResponse().setBody(
                    "{\"messages\":[{\"id\":1,\"role\":\"assistant\",\"content\":\"Recovered\"}]}"));
            AtomicInteger authLookups = new AtomicInteger();
            HermesClient client = new HermesClient(server.url("/").toString(), url -> {
                authLookups.incrementAndGet();
                return null;
            });

            HermesClient.Conversation selected = client.listConversations().get(0);
            assertEquals("Recovered", client.getMessages(selected).get(0).content);

            assertEquals(2, authLookups.get());
            assertEquals(3, server.getRequestCount());
            assertEquals(0, nextRequest(server).getSequenceNumber());
            RecordedRequest stale = nextRequest(server);
            RecordedRequest fresh = nextRequest(server);
            assertEquals("GET", stale.getMethod());
            assertTrue(stale.getPath().startsWith("/api/sessions/fixture-chat/messages?"));
            assertEquals(stale.getPath(), fresh.getPath());
            assertEquals(1, stale.getSequenceNumber());
            assertEquals(0, fresh.getSequenceNumber());
        }
    }

    @Test(timeout = 15_000)
    public void surfacesEofOnAFreshSocketAndMakesAtMostOneRecoveryAttempt() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start(InetAddress.getLoopbackAddress(), 0);
            server.enqueue(disconnectBeforeHeaders());
            server.enqueue(sessions());
            server.enqueue(disconnectBeforeHeaders());
            server.enqueue(disconnectBeforeHeaders());
            HermesClient client = new HermesClient(server.url("/").toString(), url -> null);

            IOException initial = assertThrows(IOException.class, client::listConversations);
            assertTrue(initial.getCause() instanceof EOFException);
            assertEquals(1, server.getRequestCount());
            assertEquals(1, client.listConversations().size());
            IOException recovery = assertThrows(IOException.class, client::listConversations);
            assertTrue(recovery.getCause() instanceof EOFException);
            assertEquals(4, server.getRequestCount());
            assertEquals(0, nextRequest(server).getSequenceNumber());
            assertEquals(0, nextRequest(server).getSequenceNumber());
            assertEquals(1, nextRequest(server).getSequenceNumber());
            assertEquals(0, nextRequest(server).getSequenceNumber());
        }
    }

    @Test(timeout = 15_000)
    public void neverReplaysTheWebappTicketPost() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start(InetAddress.getLoopbackAddress(), 0);
            server.enqueue(sessions());
            server.enqueue(disconnectBeforeHeaders());
            HermesClient client = new HermesClient(server.url("/").toString(), url -> null);

            assertEquals(1, client.listConversations().size());
            IOException failure = assertThrows(IOException.class,
                    () -> client.createConversation(UUID.randomUUID().toString()));
            assertTrue(failure.getCause() instanceof EOFException);
            assertEquals(2, server.getRequestCount());
            nextRequest(server);
            RecordedRequest ticket = nextRequest(server);
            assertEquals("POST", ticket.getMethod());
            assertEquals("/api/webapp/socket-ticket?socket=gateway", ticket.getPath());
            assertEquals(1, ticket.getSequenceNumber());
        }
    }

    private static RecordedRequest nextRequest(MockWebServer server) throws InterruptedException {
        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull("Expected a request to the mock gateway", request);
        return request;
    }

    private static MockResponse sessions() {
        return new MockResponse().setBody(
                "{\"sessions\":[{\"id\":\"fixture-chat\",\"profile\":\"test\",\"message_count\":1}]}");
    }

    private static MockResponse disconnectBeforeHeaders() {
        return new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST);
    }
}
