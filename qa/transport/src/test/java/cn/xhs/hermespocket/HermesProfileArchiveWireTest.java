package cn.xhs.hermespocket;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

import okhttp3.HttpUrl;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class HermesProfileArchiveWireTest {
    private static final String ROOT = "D:\\Hermes Files";
    private static final String ARCHIVE = "D:\\.hermes\\profile-exports\\research-20261119-090000.tar.gz";
    private static final Pattern PART_PATH = Pattern.compile(
            "name=\"path\"\\r\\n(?:[^\\r\\n]+\\r\\n)*\\r\\n([^\\r]+)");

    @Rule public final TemporaryFolder files = new TemporaryFolder();

    @Test(timeout = 15000)
    public void sendsValidatedPickedBytesThroughExactManagedUploadAndImport() throws Exception {
        File picked = archive("SOUL.md");
        try (Gateway host = new Gateway()) {
            host.client.importArchive("new-profile", picked, new HermesClient.RequestRead());
            assertEquals(List.of("GET /api/files", "POST /api/files/upload-stream",
                    "POST /api/profiles/import", "DELETE /api/files"), host.calls);
            assertTrue(host.staged.get().startsWith(ROOT + "\\hermes-phone-"));
        }
    }

    @Test(timeout = 15000)
    public void refusesCredentialArchiveBeforeAnyRemoteRequest() throws Exception {
        try (Gateway host = new Gateway()) {
            File picked = archive(".env");
            assertThrows(IOException.class,
                    () -> host.client.importArchive("new-profile", picked, new HermesClient.RequestRead()));
            assertTrue(host.calls.isEmpty());
        }
    }

    @Test(timeout = 15000)
    public void exportsOnlyTheGeneratedArchiveAndChecksItsTarContents() throws Exception {
        byte[] bytes = Files.readAllBytes(archive("SOUL.md").toPath());
        try (Gateway host = new Gateway()) {
            host.exported = bytes;
            File saved = files.newFile();
            host.client.exportArchive("research", saved, new HermesClient.RequestRead());
            assertArrayEquals(bytes, Files.readAllBytes(saved.toPath()));
            assertEquals(List.of("POST /api/profiles/research/export", "GET /api/fs/download"),
                    host.calls);
            host.exportedPath = "D:\\outside\\research-20261119-090000.tar.gz";
            assertThrows(IOException.class,
                    () -> host.client.exportArchive("research", saved, new HermesClient.RequestRead()));
            assertEquals(3, host.calls.size());
        }
    }

    private File archive(String leaf) throws IOException {
        File file = files.newFile();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(
                new GZIPOutputStream(new FileOutputStream(file)))) {
            TarArchiveEntry root = new TarArchiveEntry("research/");
            tar.putArchiveEntry(root);
            tar.closeArchiveEntry();
            TarArchiveEntry body = new TarArchiveEntry("research/" + leaf);
            body.setSize(4);
            tar.putArchiveEntry(body);
            tar.write(new byte[] {1, 2, 3, 4});
            tar.closeArchiveEntry();
        }
        return file;
    }

    private static final class Gateway implements AutoCloseable {
        private final MockWebServer server = new MockWebServer();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicReference<String> staged = new AtomicReference<>();
        private final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        private final HermesProfileArchives client;
        private volatile byte[] exported;
        private volatile String exportedPath = ARCHIVE;

        Gateway() throws IOException {
            server.start();
            String origin = server.url("/").toString().replaceFirst("/$", "");
            client = new HermesProfileArchives(origin, new HermesClient.Auth() {
                @Override
                public String cookieHeader(String url) {
                    return null;
                }

                @Override
                public String accessToken(String url) {
                    assertTrue(url.startsWith(origin + "/api/"));
                    return "archive-scope";
                }
            });
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

        private MockResponse route(RecordedRequest request) {
            HttpUrl url = request.getRequestUrl();
            assertNotNull(url);
            assertEquals("Bearer archive-scope", request.getHeader("Authorization"));
            String operation = request.getMethod() + " " + url.encodedPath();
            calls.add(operation);
            switch (operation) {
                case "GET /api/files":
                    return json(new JSONObject().put("path", ROOT));
                case "POST /api/files/upload-stream":
                    String form = request.getBody().readUtf8();
                    var pathMatch = PART_PATH.matcher(form);
                    assertTrue(pathMatch.find());
                    assertTrue(form.contains("name=\"overwrite\"") && form.contains("\r\n\r\nfalse\r\n"));
                    assertTrue(form.contains("name=\"file\"; filename=\"hermes-phone-"));
                    staged.set(pathMatch.group(1));
                    return json(new JSONObject().put("ok", true).put("path", staged.get()));
                case "POST /api/profiles/import":
                    JSONObject body = new JSONObject(request.getBody().readUtf8());
                    assertEquals(staged.get(), body.getString("archive"));
                    assertEquals("new-profile", body.getString("name"));
                    return json(new JSONObject().put("ok", true).put("name", "new-profile"));
                case "DELETE /api/files":
                    assertEquals(staged.get(), url.queryParameter("path"));
                    return json(new JSONObject().put("ok", true));
                case "POST /api/profiles/research/export":
                    assertEquals("{}", request.getBody().readUtf8());
                    return json(new JSONObject().put("ok", true).put("archive", exportedPath));
                case "GET /api/fs/download":
                    assertEquals(ARCHIVE, url.queryParameter("path"));
                    return new MockResponse().setHeader("Content-Type", "application/gzip")
                            .setBody(new Buffer().write(exported));
                default:
                    throw new AssertionError("Unexpected archive route " + operation);
            }
        }

        private static MockResponse json(JSONObject object) {
            return new MockResponse().setHeader("Content-Type", "application/json").setBody(object.toString());
        }

        @Override
        public void close() throws Exception {
            server.close();
            if (failure.get() != null) {
                throw new AssertionError("Unexpected archive request", failure.get());
            }
        }
    }
}
