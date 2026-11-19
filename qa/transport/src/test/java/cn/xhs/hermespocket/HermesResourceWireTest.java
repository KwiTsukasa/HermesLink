package cn.xhs.hermespocket;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.HttpUrl;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class HermesResourceWireTest {
    private static final int BLOCK = HermesResourceClient.BLOCK_BYTES;
    private static final int SIZE = BLOCK * 2 + 2;
    private static final String PATH = "D:\\Media Folder\\test.wav";
    private static final String REVISION = "\"v1\"";

    @Test(timeout = 15000)
    public void fetchesBoundedAuthenticatedRangesAndSeeksWithinSameSession() throws Exception {
        try (Gateway host = new Gateway(); HermesResourceClient client = host.client()) {
            assertEquals(SIZE, client.size());
            byte[] bytes = new byte[4];
            assertEquals(4, client.readAt(7, bytes, 0, bytes.length));
            assertArrayEquals(new byte[] { 7, 8, 9, 10 }, bytes);
            assertEquals(2, client.readAt(11, bytes, 1, 2));
            assertArrayEquals(new byte[] {7, 11, 12, 10}, bytes);
            assertEquals(2, client.readAt(SIZE - 2L, bytes, 0, bytes.length));
            assertArrayEquals(new byte[] {0, 1, 12, 10}, bytes);
            assertEquals(-1, client.readAt(SIZE, bytes, 0, bytes.length));
            assertEquals(List.of("HEAD", "bytes=0-" + (BLOCK - 1),
                    "bytes=" + (BLOCK * 2) + "-" + (SIZE - 1)), host.calls);
            client.close();
            assertThrows(IOException.class, () -> client.readAt(7, bytes, 0, bytes.length));
        }
    }

    @Test(timeout = 15000)
    public void rejectsRangedResponsesThatDoNotNameTheSameRepresentation() throws Exception {
        try (Gateway host = new Gateway(); HermesResourceClient client = host.client()) {
            byte[] output = new byte[2];
            client.size();
            host.shiftRange = true;
            assertThrows(IOException.class, () -> client.readAt(0, output, 0, output.length));
            host.shiftRange = false;
            host.replaceRepresentation = true;
            assertThrows(IOException.class, () -> client.readAt(0, output, 0, output.length));
            assertArrayEquals(new byte[2], output);
        }
    }

    @Test(timeout = 15000)
    public void refusesForeignTargetsAndNeverFollowsMediaRedirects() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> new HermesResourceClient.Target("https://other.test/file.mp3", null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new HermesResourceClient.Target("D:\\.ssh\\voice.mp3", null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new HermesResourceClient.Target(PATH, null, "other-session"));
        try (Gateway gateway = new Gateway(); MockWebServer other = new MockWebServer();
             HermesResourceClient resource = gateway.client()) {
            other.start();
            gateway.redirect = other.url("/other").toString();
            assertThrows(IOException.class, resource::size);
            assertEquals(0, other.getRequestCount());
        }
    }

    private static final class Gateway implements AutoCloseable {
        private final MockWebServer server = new MockWebServer();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        private final String origin;
        private volatile boolean shiftRange;
        private volatile boolean replaceRepresentation;
        private volatile String redirect;

        Gateway() throws IOException {
            server.start();
            origin = server.url("/").toString().replaceFirst("/$", "");
            server.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) {
                    try {
                        return route(request);
                    } catch (RuntimeException | AssertionError error) {
                        failure.compareAndSet(null, error);
                        return new MockResponse().setResponseCode(500);
                    }
                }
            });
        }

        HermesResourceClient client() {
            return new HermesResourceClient(origin + "/hermes", new HermesClient.Auth() {
                @Override
                public String cookieHeader(String url) {
                    return null;
                }

                @Override
                public String accessToken(String url) {
                    assertTrue(url.startsWith(origin + "/hermes/api/files/stream?"));
                    return "media-fixture";
                }
            }, new HermesResourceClient.Target(PATH, "research", "session-owner"));
        }

        private MockResponse route(RecordedRequest request) {
            HttpUrl url = request.getRequestUrl();
            assertNotNull(url);
            assertEquals("/hermes/api/files/stream", url.encodedPath());
            assertEquals(PATH, url.queryParameter("path"));
            assertEquals("research", url.queryParameter("profile"));
            assertEquals("session-owner", url.queryParameter("session_id"));
            assertEquals(3, url.querySize());
            assertEquals("Bearer media-fixture", request.getHeader("Authorization"));
            assertEquals(origin, request.getHeader("Origin"));
            assertEquals("identity", request.getHeader("Accept-Encoding"));
            if ("HEAD".equals(request.getMethod())) {
                calls.add("HEAD");
                assertNull(request.getHeader("Range"));
                if (redirect != null) {
                    return new MockResponse().setResponseCode(302).setHeader("Location", redirect);
                }
                return new MockResponse().setHeader("Content-Type", "audio/wav")
                        .setHeader("Content-Length", SIZE).setHeader("ETag", REVISION);
            }
            assertEquals("GET", request.getMethod());
            assertEquals(REVISION, request.getHeader("If-Range"));
            String header = request.getHeader("Range");
            assertNotNull(header);
            calls.add(header);
            String[] parts = header.substring("bytes=".length()).split("-");
            int start = Integer.parseInt(parts[0]);
            int end = Integer.parseInt(parts[1]);
            Buffer body = new Buffer();
            for (int i = start; i <= end; i++) {
                body.writeByte(i);
            }
            return new MockResponse().setResponseCode(206).setBody(body)
                    .setHeader("Content-Range", "bytes " + (shiftRange ? start + 1 : start) + "-" + end + "/" + SIZE)
                    .setHeader("Content-Type", "audio/wav")
                    .setHeader("ETag", replaceRepresentation ? "\"v2\"" : REVISION);
        }

        @Override
        public void close() throws Exception {
            server.close();
            if (failure.get() != null) {
                throw new AssertionError("Unexpected resource request", failure.get());
            }
        }
    }
}
