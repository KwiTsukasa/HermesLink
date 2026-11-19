package cn.xhs.hermespocket;

import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;
import okio.ByteString;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class HermesDisplayViewerTest {
    @Test(timeout = 15000)
    public void mintsForTheSelectedProfileAndDisplaysSyntheticPixelsUsingViewingFramesOnly() throws Exception {
        try (MockWebServer server = server()) {
            ViewerOutput output = new ViewerOutput();
            CountDownLatch incremental = new CountDownLatch(1);
            server.enqueue(ticket());
            server.enqueue(gateway("/api/display/ws", "rfb_ticket"));
            server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
                private int step;

                @Override
                public void onOpen(WebSocket socket, Response response) {
                    socket.send(ByteString.encodeUtf8("RFB 003."));
                    socket.send(ByteString.encodeUtf8("008\n"));
                }

                @Override
                public void onMessage(WebSocket socket, ByteString data) {
                    Buffer message = new Buffer().write(data);
                    switch (step++) {
                        case 0:
                            assertEquals("RFB 003.008\n", message.readUtf8());
                            socket.send(new Buffer().writeByte(1).writeByte(1).readByteString());
                            break;
                        case 1:
                            assertEquals("01", data.hex());
                            socket.send(new Buffer().writeInt(0).readByteString());
                            break;
                        case 2:
                            assertEquals("01", data.hex());
                            socket.send(serverInit(2, 1));
                            break;
                        case 3:
                            assertEquals("0000000010100001001f003f001f0b0500000000", data.hex());
                            break;
                        case 4:
                            assertEquals("0200000200000000ffffff21", data.hex());
                            break;
                        case 5:
                            assertEquals("03000000000000020001", data.hex());
                            ByteString pixels = new Buffer().writeByte(3).writeByte(0).writeShort(0).writeInt(4)
                                    .writeUtf8("test").writeByte(0).writeByte(0).writeShort(1)
                                    .writeShort(0).writeShort(0).writeShort(2).writeShort(1).writeInt(0)
                                    .writeShortLe(0xf800).writeShortLe(0x07e0).readByteString();
                            socket.send(pixels.substring(0, pixels.size() - 3));
                            socket.send(pixels.substring(pixels.size() - 3));
                            break;
                        case 6:
                            assertEquals("03010000000000020001", data.hex());
                            incremental.countDown();
                            break;
                        default:
                            throw new AssertionError("Unexpected traffic from read-only viewer");
                    }
                }
            }));

            try (HermesDisplayViewer viewer = new HermesDisplayViewer(client(server), () -> true, "research", output)) {
                viewer.connect();
                assertTrue(output.frame.await(2, TimeUnit.SECONDS));
                assertArrayEquals(new int[]{0xffff0000, 0xff00ff00}, output.colors);
                assertEquals(2, output.width);
                assertEquals(1, output.height);
                assertEquals("viewing", output.state);
                assertTrue(incremental.await(2, TimeUnit.SECONDS));

                RecordedRequest auth = request(server);
                assertEquals("/webapp/api/webapp/socket-ticket?socket=gateway&profile=research", auth.getPath());
                assertEquals("Bearer synthetic_login", auth.getHeader("Authorization"));
                RecordedRequest observe = request(server);
                assertEquals("/webapp/api/ws", observe.getPath());
                assertEquals("hermes-gateway-v1, hermes-gateway-ticket.gateway_ticket",
                        observe.getHeader("Sec-WebSocket-Protocol"));
                RecordedRequest rfb = request(server);
                assertEquals("http://127.0.0.1:" + server.getPort(), rfb.getHeader("Origin"));
                assertEquals("/webapp/api/display/ws?display_ticket=rfb_ticket", rfb.getPath());
                assertNull(rfb.getHeader("Authorization"));
                assertNull(rfb.getHeader("Cookie"));
                assertEquals(3, server.getRequestCount());
            }
        }
    }

    @Test(timeout = 15000)
    public void rejectsAServerSuppliedAlternatePathAndCancelledAdmission() throws Exception {
        try (MockWebServer server = server()) {
            AtomicBoolean currentLogin = new AtomicBoolean(true);
            ViewerOutput output = new ViewerOutput();
            try (HermesDisplayViewer viewer = new HermesDisplayViewer(client(server), currentLogin::get,
                    "research", output)) {
                server.enqueue(ticket());
                server.enqueue(gateway("/api/other", "rfb_ticket"));
                viewer.connect();
                assertEquals("failed", output.state);
                assertEquals(2, server.getRequestCount());
            }
            currentLogin.set(false);
            try (HermesDisplayViewer viewer = new HermesDisplayViewer(client(server), currentLogin::get,
                    "research", output)) {
                viewer.connect();
                assertEquals(2, server.getRequestCount());
            }
            assertThrows(IllegalArgumentException.class,
                    () -> new HermesDisplayViewer(client(server), () -> true, "../outside", output));
        }
    }

    @Test
    public void rejectsOversizedFramebuffersAndRequestsAFullFrameAfterServerResize() throws Exception {
        DecoderOutput rejected = new DecoderOutput();
        RfbScreenDecoder invalid = initialize(rejected);
        assertThrows(IOException.class, () -> invalid.receive(serverInit(4096, 4096)));
        assertEquals(0, rejected.width);

        DecoderOutput output = new DecoderOutput();
        RfbScreenDecoder decoder = initialize(output);
        decoder.receive(serverInit(2, 1));
        decoder.receive(new Buffer().writeInt(1).writeShort(0).writeShort(0)
                .writeShort(3).writeShort(2).writeInt(-223).readByteString());
        assertEquals(3, output.width);
        assertEquals(2, output.height);
        decoder.requestUpdate(true);
        assertEquals("03000000000000030002", output.sent.get(output.sent.size() - 1).hex());
        decoder.requestUpdate(true);
        assertEquals("03010000000000030002", output.sent.get(output.sent.size() - 1).hex());
        assertThrows(IOException.class, () -> decoder.receive(new Buffer().writeInt(1)
                .writeShort(3).writeShort(0).writeShort(1).writeShort(1).writeInt(0).readByteString()));
    }

    private static RfbScreenDecoder initialize(DecoderOutput output) throws IOException {
        RfbScreenDecoder decoder = new RfbScreenDecoder(output);
        decoder.receive(new Buffer().writeUtf8("RFB 003.008\n").writeByte(1).writeByte(1)
                .writeInt(0).readByteString());
        return decoder;
    }

    private static ByteString serverInit(int width, int height) {
        return new Buffer().writeShort(width).writeShort(height).write(new byte[16]).writeInt(7)
                .writeUtf8("fixture").readByteString();
    }

    private static MockResponse ticket() {
        return new MockResponse().setBody("{\"ticket\":\"gateway_ticket\"}");
    }

    private static MockResponse gateway(String path, String viewerTicket) {
        return new MockResponse().setHeader("Sec-WebSocket-Protocol", "hermes-gateway-v1")
                .withWebSocketUpgrade(new WebSocketListener() {
                    @Override
                    public void onOpen(WebSocket socket, Response response) {
                        socket.send("{\"params\":{\"type\":\"gateway.ready\",\"payload\":{\"session_authority\":true}}}");
                    }

                    @Override
                    public void onMessage(WebSocket socket, String message) {
                        JSONObject request = new JSONObject(message);
                        assertEquals("display.observe", request.getString("method"));
                        JSONObject params = request.getJSONObject("params");
                        assertEquals("research", params.getString("profile"));
                        assertEquals(1, params.length());
                        socket.send(new JSONObject().put("jsonrpc", "2.0").put("id", request.getString("id"))
                                .put("result", new JSONObject().put("path", path).put("ticket", viewerTicket)
                                        .put("viewer_id", "viewer_for_research")).toString());
                    }

                    @Override
                    public void onClosing(WebSocket socket, int code, String reason) {
                        socket.close(1000, null);
                    }
                });
    }

    private static MockWebServer server() throws IOException {
        MockWebServer server = new MockWebServer();
        server.start(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 0);
        return server;
    }

    private static HermesClient client(MockWebServer server) {
        return new HermesClient("http://127.0.0.1:" + server.getPort() + "/webapp/", new HermesClient.Auth() {
            @Override
            public String cookieHeader(String requestUrl) {
                return null;
            }

            @Override
            public String accessToken(String requestUrl) {
                return "synthetic_login";
            }
        });
    }

    private static RecordedRequest request(MockWebServer server) throws InterruptedException {
        RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
        assertNotNull(request);
        return request;
    }

    private static class ViewerOutput implements HermesDisplayViewer.Observer {
        final CountDownLatch frame = new CountDownLatch(1);
        int width;
        int height;
        int[] colors;
        volatile String state;

        @Override public void state(String next) { state = next; }
        @Override public void resize(int w, int h) { width = w; height = h; }
        @Override public void row(int x, int y, int[] argb, int length) {
            colors = Arrays.copyOf(argb, length);
        }
        @Override public void frameReady() { frame.countDown(); }
    }

    private static final class DecoderOutput extends ViewerOutput implements RfbScreenDecoder.Output {
        final List<ByteString> sent = new ArrayList<>();

        @Override public void send(ByteString message) { sent.add(message); }
        @Override public void frameComplete() { frameReady(); }
    }
}
