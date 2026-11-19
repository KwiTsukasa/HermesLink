package cn.xhs.hermespocket;

import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import okhttp3.HttpUrl;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.ByteString;

import static cn.xhs.hermespocket.HermesTerminal.End;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class HermesTerminalWireTest {
    private static final String META = "\0HERMES_TERMINAL_META:";
    private static final String SHELL = "s".repeat(43);
    private static final String PRIVATE_TOKEN = "native-access-token";
    private static final String PRIVATE_COOKIE = "hermes=browser-session";
    private static final String PROFILE = "research";
    private static final ByteString INPUT = ByteString.encodeUtf8("文字\u001b[A\r");

    @Test(timeout = 20_000)
    public void usesFreshScopedTicketsForCreateReattachAndCloseWhileDetached() throws Exception {
        try (Wire wire = new Wire(); HermesTerminal terminal = wire.terminal(() -> true)) {
            Probe first = new Probe();
            assertFalse(terminal.start(0, 24, null, null, first));
            assertFalse(terminal.start(80, 24, PROFILE, "x\0y", first));
            assertFalse(terminal.sendInput(INPUT.toByteArray()));
            assertFalse(terminal.resize(80, 24));
            String cwd = "C:\\工作目录\\test #1?name&value";
            Viewer opening = wire.dial('a');
            assertTrue(terminal.start(80, 24, PROFILE, cwd, first));
            assertFalse(terminal.start(80, 24, PROFILE, cwd, first));
            HttpUrl start = wire.requests('a');
            assertEquals(Set.of("ticket", "cols", "rows", "profile", "cwd"), start.queryParameterNames());
            assertEquals(PROFILE, start.queryParameter("profile"));
            assertEquals(cwd, start.queryParameter("cwd"));
            assertEquals("80", start.queryParameter("cols"));
            assertEquals("24", start.queryParameter("rows"));
            WebSocket open = take(opening.sockets);
            assertFalse(terminal.sendInput(INPUT.toByteArray()));
            open.send(ready(false, false));
            assertEquals("ready:false:false", take(first.events));

            ByteString output = ByteString.encodeUtf8("界\u001b[32m✨\u001b[0m");
            ByteString splitPrefix = output.substring(0, 2);
            ByteString splitSuffix = output.substring(2);
            open.send(splitPrefix);
            open.send(ByteString.EMPTY);
            open.send(splitSuffix);
            assertEquals(new Output(splitPrefix, false), take(first.events));
            assertEquals(new Output(splitSuffix, false), take(first.events));
            assertFalse(terminal.sendInput(new byte[HermesTerminal.MAX_INPUT_BYTES + 1]));
            assertFalse(terminal.sendInput(new byte[0]));
            assertFalse(terminal.resize(HermesTerminal.MAX_COLUMNS + 1, 25));
            assertTrue(terminal.sendInput(INPUT.toByteArray()));
            assertTrue(terminal.resize(100, 30));
            assertEquals(INPUT, take(opening.frames));
            assertEquals("\u001b[RESIZE:100;30]", take(opening.frames));

            assertTrue(terminal.detach());
            assertEquals(End.DETACHED, take(first.events));
            assertTrue(opening.disconnected.poll(3, TimeUnit.SECONDS));
            assertTrue(terminal.canReattach());
            assertFalse(terminal.sendInput(INPUT.toByteArray()));
            assertFalse(terminal.start(80, 24, null, null, first));
            assertNull(wire.server.takeRequest(200, TimeUnit.MILLISECONDS));

            Viewer attaching = wire.dial('b');
            Probe second = new Probe();
            assertTrue(terminal.reattach(95, 32, second));
            HttpUrl attach = wire.requests('b');
            assertEquals(Set.of("ticket", "cols", "rows", "profile", "attach"), attach.queryParameterNames());
            assertEquals(PROFILE, attach.queryParameter("profile"));
            assertEquals(SHELL, attach.queryParameter("attach"));
            WebSocket attached = take(attaching.sockets);
            attached.send(ready(true, true));
            attached.send(output);
            assertEquals("ready:true:true", take(second.events));
            assertEquals(new Output(output, true), take(second.events));
            assertEquals("\u001b[RESIZE:95;31]", take(attaching.frames));
            assertEquals("\u001b[RESIZE:95;32]", take(attaching.frames));
            assertNull(attaching.frames.poll(200, TimeUnit.MILLISECONDS));
            assertTrue(terminal.detach());
            assertEquals(End.DETACHED, take(second.events));

            Viewer closing = wire.dial('c');
            Probe closer = new Probe();
            assertTrue(terminal.closeShell(closer));
            HttpUrl close = wire.requests('c');
            assertEquals(Set.of("ticket", "profile", "attach", "action"), close.queryParameterNames());
            assertEquals(PROFILE, close.queryParameter("profile"));
            assertEquals(SHELL, close.queryParameter("attach"));
            assertEquals("close", close.queryParameter("action"));
            WebSocket closeSocket = take(closing.sockets);
            closeSocket.send(closed(SHELL));
            assertNull(closer.events.poll(200, TimeUnit.MILLISECONDS));
            closeSocket.close(1000, null);
            assertEquals(End.SHELL_CLOSED, take(closer.events));
            assertFalse(terminal.canReattach());
            assertEquals(6, wire.server.getRequestCount());
        }
    }

    @Test(timeout = 20_000)
    public void emptySnapshotIsTheOnlyReplayFrameAndDoesNotSwallowTheFirstLiveQuery() throws Exception {
        try (Wire wire = new Wire(); HermesTerminal terminal = wire.terminal(() -> true)) {
            Viewer opening = wire.dial('a');
            Probe first = new Probe();
            assertTrue(terminal.start(80, 24, null, null, first));
            wire.requests('a');
            take(opening.sockets).send(ready(false, false));
            assertEquals("ready:false:false", take(first.events));
            assertTrue(terminal.detach());
            assertEquals(End.DETACHED, take(first.events));

            Viewer attaching = wire.dial('b');
            Probe second = new Probe();
            assertTrue(terminal.reattach(80, 24, second));
            wire.requests('b');
            WebSocket socket = take(attaching.sockets);
            socket.send(ready(true, false));
            socket.send("unexpected text before the binary snapshot");
            socket.send(ByteString.EMPTY);
            socket.send(ByteString.EMPTY);
            ByteString liveQuery = ByteString.encodeUtf8("\u001b[c");
            socket.send(liveQuery);
            assertEquals("ready:true:false", take(second.events));
            assertEquals(new Output(ByteString.EMPTY, true), take(second.events));
            assertEquals(new Output(liveQuery, false), take(second.events));
        }
    }

    @Test(timeout = 20_000)
    public void closesTheOriginalShellWhenMetadataArrivesAfterCloseWasRequested() throws Exception {
        try (Wire wire = new Wire(); HermesTerminal terminal = wire.terminal(() -> true)) {
            Viewer opening = wire.dial('a');
            Probe open = new Probe();
            assertTrue(terminal.start(80, 24, null, null, open));
            assertNull(wire.requests('a').queryParameter("profile"));
            WebSocket socket = take(opening.sockets);
            Viewer closing = wire.dial('b');
            Probe close = new Probe();
            assertTrue(terminal.closeShell(close));
            assertFalse(terminal.sendInput(INPUT.toByteArray()));
            assertFalse(terminal.reattach(80, 24, open));
            socket.send(ready(false, false));
            HttpUrl closeUrl = wire.requests('b');
            assertEquals(SHELL, closeUrl.queryParameter("attach"));
            assertEquals("close", closeUrl.queryParameter("action"));
            WebSocket closeSocket = take(closing.sockets);
            closeSocket.send(closed(SHELL));
            closeSocket.close(1000, null);
            assertEquals(End.SHELL_CLOSED, take(close.events));
            assertNull(open.events.poll(200, TimeUnit.MILLISECONDS));
            assertFalse(terminal.canReattach());
        }
    }

    @Test(timeout = 20_000)
    public void detachesDuringStartupAndCancelsReattachWithoutDiscardingTheOriginalHandle() throws Exception {
        try (Wire wire = new Wire(); HermesTerminal terminal = wire.terminal(() -> true)) {
            Viewer opening = wire.dial('a');
            Probe first = new Probe();
            assertTrue(terminal.start(80, 24, PROFILE, null, first));
            wire.requests('a');
            WebSocket socket = take(opening.sockets);
            assertTrue(terminal.detach());
            socket.send(ready(false, false));
            assertEquals(End.DETACHED, take(first.events));
            assertTrue(terminal.canReattach());

            wire.server.enqueue(ticket('b').setBodyDelay(300, TimeUnit.MILLISECONDS));
            Probe attaching = new Probe();
            assertTrue(terminal.reattach(80, 24, attaching));
            wire.ticketRequest();
            assertTrue(terminal.detach());
            assertEquals(End.DETACHED, take(attaching.events));
            assertTrue(terminal.canReattach());
            assertNull(wire.server.takeRequest(500, TimeUnit.MILLISECONDS));

            Viewer closing = wire.dial('c');
            Probe closer = new Probe();
            assertTrue(terminal.closeShell(closer));
            HttpUrl close = wire.requests('c');
            assertEquals(SHELL, close.queryParameter("attach"));
            assertEquals(PROFILE, close.queryParameter("profile"));
            WebSocket closeSocket = take(closing.sockets);
            closeSocket.send(closed(SHELL));
            closeSocket.close(1000, null);
            assertEquals(End.SHELL_CLOSED, take(closer.events));
        }
    }

    @Test(timeout = 20_000)
    public void retainsHandleAfterUnconfirmedCloseAndReportsServerExpiry() throws Exception {
        try (Wire wire = new Wire(); HermesTerminal terminal = wire.terminal(() -> true)) {
            Viewer opening = wire.dial('a');
            Probe probe = new Probe();
            assertTrue(terminal.start(80, 24, null, null, probe));
            wire.requests('a');
            take(opening.sockets).send(ready(false, false));
            assertEquals("ready:false:false", take(probe.events));

            Viewer closing = wire.dial('b');
            assertTrue(terminal.closeShell(probe));
            assertEquals("close", wire.requests('b').queryParameter("action"));
            take(closing.sockets).close(1000, null);
            assertEquals(End.ERROR, take(probe.events));
            assertTrue(terminal.canReattach());

            Viewer attaching = wire.dial('c');
            assertTrue(terminal.reattach(80, 24, probe));
            assertEquals(SHELL, wire.requests('c').queryParameter("attach"));
            take(attaching.sockets).close(4410, "Terminal or profile expired; open a new terminal");
            assertEquals(End.EXPIRED, take(probe.events));
            assertFalse(terminal.canReattach());
        }
    }

    @Test(timeout = 20_000)
    public void naturalExitClearsHandleAndOversizeOutputEndsViewer() throws Exception {
        try (Wire wire = new Wire(); HermesTerminal terminal = wire.terminal(() -> true)) {
            Viewer exiting = wire.dial('a');
            Probe first = new Probe();
            assertTrue(terminal.start(80, 24, null, null, first));
            wire.requests('a');
            WebSocket socket = take(exiting.sockets);
            socket.send(ready(false, false));
            assertEquals("ready:false:false", take(first.events));
            socket.close(4410, "Process exited");
            assertEquals(End.EXITED, take(first.events));
            assertFalse(terminal.canReattach());
            Probe lateClose = new Probe();
            assertTrue(terminal.closeShell(lateClose));
            assertEquals(End.EXITED, take(lateClose.events));
            assertEquals(2, wire.server.getRequestCount());

            Viewer opening = wire.dial('b');
            Probe second = new Probe();
            assertTrue(terminal.start(80, 24, null, null, second));
            wire.requests('b');
            WebSocket open = take(opening.sockets);
            open.send(ready(false, false));
            assertEquals("ready:false:false", take(second.events));
            ByteString max = ByteString.of(new byte[HermesTerminal.MAX_OUTPUT_BYTES]);
            open.send(max);
            assertEquals(new Output(max, false), take(second.events));
            open.send(ByteString.of(new byte[HermesTerminal.MAX_OUTPUT_BYTES + 1]));
            assertEquals(End.ERROR, take(second.events));
            assertTrue(terminal.canReattach());
        }
    }

    @Test(timeout = 20_000)
    public void staleLoginCannotWriteOrResizeAnAlreadyOpenSocket() throws Exception {
        AtomicBoolean current = new AtomicBoolean(true);
        try (Wire wire = new Wire(); HermesTerminal terminal = wire.terminal(current::get)) {
            Viewer opening = wire.dial('a');
            Probe probe = new Probe();
            assertTrue(terminal.start(80, 24, null, null, probe));
            wire.requests('a');
            take(opening.sockets).send(ready(false, false));
            assertEquals("ready:false:false", take(probe.events));
            assertTrue(terminal.sendInput(INPUT.toByteArray()));
            assertEquals(INPUT, take(opening.frames));

            current.set(false);
            assertFalse(terminal.sendInput(INPUT.toByteArray()));
            assertFalse(terminal.resize(100, 30));
            assertNull(opening.frames.poll(200, TimeUnit.MILLISECONDS));
        }
    }

    @Test(timeout = 20_000)
    public void loginChangesAndCancellationNeverUpgradeTheSocket() throws Exception {
        try (Wire wire = new Wire()) {
            AtomicBoolean current = new AtomicBoolean(true);
            try (HermesTerminal terminal = wire.terminal(current::get)) {
                wire.server.enqueue(ticket('a').setBodyDelay(500, TimeUnit.MILLISECONDS));
                Probe login = new Probe();
                assertTrue(terminal.start(80, 24, null, null, login));
                wire.ticketRequest();
                current.set(false);
                assertEquals(End.SIGNED_OUT, take(login.events));
            }
            try (HermesTerminal terminal = wire.terminal(() -> true)) {
                wire.server.enqueue(ticket('b').setBodyDelay(500, TimeUnit.MILLISECONDS));
                Probe opening = new Probe();
                Probe closing = new Probe();
                assertTrue(terminal.start(80, 24, null, null, opening));
                wire.ticketRequest();
                assertTrue(terminal.closeShell(closing));
                assertEquals(End.SHELL_CLOSED, take(closing.events));
                assertFalse(terminal.canReattach());
                assertNull(opening.events.poll(750, TimeUnit.MILLISECONDS));
                assertEquals(2, wire.server.getRequestCount());
            }
        }
    }

    @Test(timeout = 20_000)
    public void refusesMissingCredentialsInvalidTicketAndCrossOriginRedirects() throws Exception {
        try (Wire wire = new Wire(); MockWebServer external = new MockWebServer()) {
            external.start(InetAddress.getLoopbackAddress(), 0);
            Probe probe = new Probe();
            try (HermesTerminal anonymous = new HermesClient(wire.server.url("/").toString(), url -> null)
                    .newHostTerminal(() -> true)) {
                assertTrue(anonymous.start(80, 24, null, null, probe));
                assertEquals(End.SIGNED_OUT, take(probe.events));
                assertEquals(0, wire.server.getRequestCount());
            }

            try (HermesTerminal terminal = wire.terminal(() -> true)) {
                wire.server.enqueue(json(new JSONObject().put("ticket", "a&attach=other")));
                assertTrue(terminal.start(80, 24, null, null, probe));
                wire.ticketRequest();
                assertEquals(End.ERROR, take(probe.events));

                MockResponse redirect = new MockResponse().setResponseCode(302)
                        .addHeader("Location", external.url("/outside"));
                wire.server.enqueue(redirect);
                assertTrue(terminal.start(80, 24, null, null, probe));
                wire.ticketRequest();
                assertEquals(End.ERROR, take(probe.events));

                wire.server.enqueue(ticket('a'));
                wire.server.enqueue(redirect);
                assertTrue(terminal.start(80, 24, null, null, probe));
                wire.requests('a');
                assertEquals(End.ERROR, take(probe.events));
                assertFalse(terminal.canReattach());
                assertNull(external.takeRequest(200, TimeUnit.MILLISECONDS));
                assertEquals(4, wire.server.getRequestCount());
            }
        }
    }

    private static <T> T take(BlockingQueue<T> queue) throws InterruptedException {
        T item = queue.poll(3, TimeUnit.SECONDS);
        assertNotNull(item);
        return item;
    }

    private static String ready(boolean reconnected, boolean truncated) {
        return META + new JSONObject().put("terminalId", SHELL).put("shell", "pwsh.exe")
                .put("cwd", "C:\\work").put("retentionSeconds", 900)
                .put("reconnected", reconnected).put("truncated", truncated);
    }

    private static String closed(String id) {
        return META + new JSONObject().put("terminalId", id).put("closed", true);
    }

    private static MockResponse json(JSONObject object) {
        return new MockResponse().addHeader("Content-Type", "application/json").setBody(object.toString());
    }

    private static MockResponse ticket(char character) {
        return json(new JSONObject().put("ticket", String.valueOf(character).repeat(43)).put("ttl_seconds", 30));
    }

    private record Output(ByteString data, boolean replay) { }

    private static final class Probe implements HermesTerminal.Listener {
        private final BlockingQueue<Object> events = new LinkedBlockingQueue<>();

        @Override
        public void onReady(boolean reconnected, boolean truncated) {
            events.add("ready:" + reconnected + ":" + truncated);
        }

        @Override
        public void onOutput(byte[] data, boolean replay) {
            events.add(new Output(ByteString.of(data), replay));
        }

        @Override
        public void onClosed(End end) {
            events.add(end);
        }
    }

    private static final class Viewer extends WebSocketListener {
        private final BlockingQueue<WebSocket> sockets = new LinkedBlockingQueue<>();
        private final BlockingQueue<Object> frames = new LinkedBlockingQueue<>();
        private final BlockingQueue<Boolean> disconnected = new LinkedBlockingQueue<>();

        @Override
        public void onOpen(WebSocket socket, Response response) {
            sockets.add(socket);
        }

        @Override
        public void onMessage(WebSocket socket, String frame) {
            frames.add(frame);
        }

        @Override
        public void onMessage(WebSocket socket, ByteString frame) {
            frames.add(frame);
        }

        @Override
        public void onClosing(WebSocket socket, int code, String reason) {
            socket.close(1000, null);
            disconnected.add(true);
        }

        @Override
        public void onFailure(WebSocket socket, Throwable error, Response response) {
            disconnected.add(true);
        }
    }

    private static final class Wire implements AutoCloseable {
        private final MockWebServer server = new MockWebServer();
        private final String origin;

        private Wire() throws IOException {
            server.start(InetAddress.getLoopbackAddress(), 0);
            origin = server.url("/").toString().replaceFirst("/$", "");
        }

        private HermesTerminal terminal(BooleanSupplier current) {
            return new HermesClient(origin + "/private", new HermesClient.Auth() {
                @Override
                public String cookieHeader(String requestUrl) {
                    assertEquals(origin + "/private/api/webapp/socket-ticket?socket=host-terminal", requestUrl);
                    return PRIVATE_COOKIE;
                }

                @Override
                public String accessToken(String requestUrl) {
                    assertEquals(origin + "/private/api/webapp/socket-ticket?socket=host-terminal", requestUrl);
                    return PRIVATE_TOKEN;
                }
            }).newHostTerminal(current);
        }

        private Viewer dial(char value) {
            Viewer viewer = new Viewer();
            server.enqueue(ticket(value));
            server.enqueue(new MockResponse().withWebSocketUpgrade(viewer));
            return viewer;
        }

        private void ticketRequest() throws InterruptedException {
            RecordedRequest request = server.takeRequest(3, TimeUnit.SECONDS);
            assertNotNull(request);
            assertEquals("POST", request.getMethod());
            assertEquals("/private/api/webapp/socket-ticket?socket=host-terminal", request.getPath());
            assertEquals(origin, request.getHeader("Origin"));
            assertEquals("Bearer " + PRIVATE_TOKEN, request.getHeader("Authorization"));
            assertEquals(PRIVATE_COOKIE, request.getHeader("Cookie"));
            assertEquals(0, request.getBodySize());
        }

        private HttpUrl requests(char value) throws InterruptedException {
            ticketRequest();
            RecordedRequest request = server.takeRequest(3, TimeUnit.SECONDS);
            assertNotNull(request);
            assertEquals("GET", request.getMethod());
            assertEquals(origin, request.getHeader("Origin"));
            assertNull(request.getHeader("Authorization"));
            assertNull(request.getHeader("Cookie"));
            assertNull(request.getHeader("Sec-WebSocket-Protocol"));
            HttpUrl url = request.getRequestUrl();
            assertNotNull(url);
            assertEquals("/private/api/host-terminal", url.encodedPath());
            assertEquals(String.valueOf(value).repeat(43), url.queryParameter("ticket"));
            return url;
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }
}
