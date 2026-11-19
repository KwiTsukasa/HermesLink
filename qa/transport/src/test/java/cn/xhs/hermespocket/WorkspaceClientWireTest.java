package cn.xhs.hermespocket;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import okio.Buffer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class WorkspaceClientWireTest {
    private static final String MESSAGES = "rest:GET:/api/sessions/{session_id}/messages";
    private static final String DOWNLOAD = "rest:GET:/api/fs/download";
    private static final String UPLOAD = "rest:POST:/api/chat/file-upload";
    private static final String WRITE = "rest:POST:/api/fs/write-text";
    private static final String TOKEN = "test-workspace-token";
    private static final String KANBAN_BOARD = "rest:GET:/api/plugins/kanban/board";
    private static final String KANBAN_UPLOAD = "rest:POST:/api/plugins/kanban/tasks/{task_id}/attachments";
    private static final String KANBAN_DOWNLOAD = "rest:GET:/api/plugins/kanban/attachments/{attachment_id}";

    @Test(timeout = 15000)
    public void usesRealCatalogForAuthenticatedRestAndDownloads() throws Exception {
        try (MockWebServer mock = mock()) {
            WorkspaceCatalog catalog = loadBundledCatalog();
            WorkspaceClient client = client(mock, catalog);
            assertEquals("0.23.2", catalog.version);
            assertNull(WorkspaceClient.unsupportedReason(catalog.find(MESSAGES)));
            mock.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("{\"messages\":[]}"));

            JSONObject query = new JSONObject().put("profile", "default&limit=900")
                    .put("limit", 6).put("inline_images", false);
            JSONObject path = new JSONObject().put("session_id", "session one");
            WorkspaceClient.Result result = client.execute(MESSAGES, path, query, null, null, false);
            assertTrue(result.successful());
            assertEquals(0, ((JSONObject) result.json()).getJSONArray("messages").length());
            RecordedRequest read = request(mock);
            assertEquals("/hermes/api/sessions/session%20one/messages", read.getRequestUrl().encodedPath());
            assertEquals("default&limit=900", read.getRequestUrl().queryParameter("profile"));
            assertEquals(1, read.getRequestUrl().queryParameterValues("limit").size());
            assertEquals("6", read.getRequestUrl().queryParameter("limit"));
            assertEquals("false", read.getRequestUrl().queryParameter("inline_images"));
            assertEquals("Bearer " + TOKEN, read.getHeader("Authorization"));
            assertEquals(origin(mock), read.getHeader("Origin"));

            byte[] zip = {0, 1, 2, (byte) 255};
            mock.enqueue(new MockResponse().setHeader("Content-Type", "application/zip")
                    .setHeader("Content-Disposition", "attachment; filename*=utf-8''%E6%B5%8B%E8%AF%95.zip")
                    .setBody(new Buffer().write(zip)));
            WorkspaceClient.Result file = client.execute(DOWNLOAD, null,
                    new JSONObject().put("path", "C:\\Workspace\\archive.zip"), null, null, false);
            assertTrue(file.successful());
            assertEquals("application/zip", file.contentType);
            assertEquals("测试.zip", file.fileName);
            assertArrayEquals(zip, file.data);
            assertEquals("C:\\Workspace\\archive.zip", request(mock).getRequestUrl().queryParameter("path"));
        }
    }

    @Test(timeout = 15000)
    public void listsManagedFilesAtDefaultRootAndFiltersUnusableChildren() throws Exception {
        try (MockWebServer mock = mock()) {
            WorkspaceClient client = client(mock, loadBundledCatalog());
            String root = "C:\\Workspace";
            JSONObject listing = new JSONObject().put("path", root).put("locked_root", root).put("entries",
                    new JSONArray()
                            .put(managedEntry("资料", root + "\\资料", true))
                            .put(managedEntry("note.txt", root + "\\note.txt", false))
                            .put(managedEntry("credentials.json", root + "\\credentials.json", false))
                            .put(managedEntry(".ENV", root + "\\.ENV", false))
                            .put(managedEntry("bad%name.txt", root + "\\bad%name.txt", false))
                            .put(managedEntry("alias.txt", root + "\\note.txt", false))
                            .put(managedEntry("external", "C:\\Outside", true))
                            .put(managedEntry("missing", root + "\\missing", false).put("broken_link", true)));
            mock.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(listing.toString()));
            WorkspaceClient.Result result = client.execute(WorkspaceClient.MANAGED_FILES, null,
                    null, null, null, false);
            assertTrue(result.successful());
            JSONObject filtered = WorkspaceClient.visibleManagedListing((JSONObject) result.json(), null);
            assertEquals(root, filtered.getString("locked_root"));
            JSONArray children = filtered.getJSONArray("entries");
            assertEquals(2, children.length());
            assertEquals("资料", children.getJSONObject(0).getString("name"));
            assertEquals(root + "\\note.txt", children.getJSONObject(1).getString("path"));
            RecordedRequest rootRequest = request(mock);
            assertEquals("/hermes/api/files", rootRequest.getPath());
            assertEquals("Bearer " + TOKEN, rootRequest.getHeader("Authorization"));

            String child = root + "\\资料";
            mock.enqueue(new MockResponse().setBody(new JSONObject().put("path", "C:\\Outside")
                    .put("entries", new JSONArray()).toString()));
            WorkspaceClient.Result redirected = client.execute(WorkspaceClient.MANAGED_FILES, null,
                    new JSONObject().put("path", child), null, null, false);
            assertEquals(child, request(mock).getRequestUrl().queryParameter("path"));
            assertThrows(IllegalArgumentException.class,
                    () -> WorkspaceClient.visibleManagedListing((JSONObject) redirected.json(), child));
        }
    }

    @Test(timeout = 15000)
    public void previewsManagedUtf8TextAndRejectsUnsafePathsWithoutARequest() throws Exception {
        try (MockWebServer mock = mock()) {
            WorkspaceClient client = client(mock, loadBundledCatalog());
            for (String invalid : List.of("", "/workspace/.ssh/config", "/workspace/../secret.txt")) {
                assertThrows(IllegalArgumentException.class, () -> client.previewManagedFile(invalid));
            }
            assertEquals(0, mock.getRequestCount());

            String path = "C:\\Workspace\\资料 & notes.md";
            String text = "待办 \"会议\"\n张三\t星期五";
            String body = "\uFEFF" + text;
            int size = body.getBytes(StandardCharsets.UTF_8).length;
            mock.enqueue(new MockResponse().setResponseCode(206)
                    .setHeader("Content-Range", "bytes 0-" + (size - 1) + "/" + size)
                    .setHeader("Content-Type", "application/octet-stream").setBody(body));
            JSONObject preview = (JSONObject) client.previewManagedFile(path).json();
            assertEquals(text, preview.getString("text"));
            assertFalse(preview.getBoolean("binary"));
            assertFalse(preview.getBoolean("truncated"));
            RecordedRequest previewRequest = request(mock);
            assertEquals("/hermes/api/files/download", previewRequest.getRequestUrl().encodedPath());
            assertEquals(path, previewRequest.getRequestUrl().queryParameter("path"));
            assertEquals("Bearer " + TOKEN, previewRequest.getHeader("Authorization"));
            assertEquals(origin(mock), previewRequest.getHeader("Origin"));
            assertEquals("bytes=0-" + WorkspaceClient.FILE_PREVIEW_BYTES, previewRequest.getHeader("Range"));

            mock.enqueue(new MockResponse().setResponseCode(403).setBody("forbidden"));
            WorkspaceClient.Result denied = client.previewManagedFile(path);
            assertFalse(denied.successful());
            assertEquals(403, denied.statusCode);
            assertEquals(0, denied.data.length);

            mock.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", "https://example.invalid"));
            assertEquals(302, client.previewManagedFile(path).statusCode);
            assertEquals(3, mock.getRequestCount());
        }
    }

    @Test(timeout = 15000)
    public void previewsEmptyRangeAndRejectsInconsistentPartialResponses() throws Exception {
        try (MockWebServer mock = mock()) {
            WorkspaceClient client = client(mock, loadBundledCatalog());
            String path = "/workspace/empty.txt";
            for (String emptyRange : List.of("bytes */0", "*/0")) {
                mock.enqueue(new MockResponse().setResponseCode(416).setHeader("Content-Range", emptyRange));
                WorkspaceClient.Result empty = client.previewManagedFile(path);
                assertTrue(empty.successful());
                JSONObject preview = (JSONObject) empty.json();
                assertEquals("", preview.getString("text"));
                assertFalse(preview.getBoolean("binary"));
                assertFalse(preview.getBoolean("truncated"));
            }

            mock.enqueue(new MockResponse().setResponseCode(416).setHeader("Content-Range", "bytes */2"));
            assertEquals(416, client.previewManagedFile(path).statusCode);

            mock.enqueue(new MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 2-4/5")
                    .setBody("cde"));
            assertThrows(IOException.class, () -> client.previewManagedFile(path));
            mock.enqueue(new MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-4/5")
                    .setBody("abc"));
            assertThrows(IOException.class, () -> client.previewManagedFile(path));
        }
    }

    @Test(timeout = 15000)
    public void capsPreviewAtUtf8BoundaryAndNeverReturnsBinaryBytesAsText() throws Exception {
        try (MockWebServer mock = mock()) {
            WorkspaceClient client = client(mock, loadBundledCatalog());
            String path = "/workspace/notes";
            String prefix = "a".repeat(WorkspaceClient.FILE_PREVIEW_BYTES - 1);
            mock.enqueue(new MockResponse().setChunkedBody(prefix + "三 text beyond the preview", 4096));
            JSONObject truncated = (JSONObject) client.previewManagedFile(path).json();
            assertTrue(truncated.getBoolean("truncated"));
            assertFalse(truncated.getBoolean("binary"));
            assertEquals(prefix, truncated.getString("text"));

            Buffer range = new Buffer().writeUtf8(prefix).writeUtf8("三");
            long total = range.size();
            Buffer rangedBody = new Buffer();
            rangedBody.write(range, WorkspaceClient.FILE_PREVIEW_BYTES + 1);
            mock.enqueue(new MockResponse().setResponseCode(206)
                    .setHeader("Content-Range", "bytes 0-" + WorkspaceClient.FILE_PREVIEW_BYTES + "/" + total)
                    .setBody(rangedBody));
            JSONObject partial = (JSONObject) client.previewManagedFile(path).json();
            assertTrue(partial.getBoolean("truncated"));
            assertFalse(partial.getBoolean("binary"));
            assertEquals(prefix, partial.getString("text"));

            mock.enqueue(new MockResponse().setBody(prefix + "b"));
            JSONObject exact = (JSONObject) client.previewManagedFile(path).json();
            assertFalse(exact.getBoolean("truncated"));
            assertEquals(prefix + "b", exact.getString("text"));

            for (byte[] bytes : List.of(new byte[]{'a', 0, 'b'}, new byte[]{'a', (byte) 0xe4})) {
                mock.enqueue(new MockResponse().setBody(new Buffer().write(bytes)));
                JSONObject binary = (JSONObject) client.previewManagedFile(path).json();
                assertTrue(binary.getBoolean("binary"));
                assertEquals("", binary.getString("text"));
            }
        }
    }

    @Test(timeout = 15000)
    public void downloadsEmptyManagedFileAndCapsOtherDownloadsAt32MiB() throws Exception {
        try (MockWebServer mock = mock()) {
            WorkspaceClient client = client(mock, loadBundledCatalog());
            JSONObject query = new JSONObject().put("path", "/workspace/empty.txt");
            mock.enqueue(new MockResponse().setHeader("Content-Type", "text/plain")
                    .setHeader("Content-Disposition", "attachment; filename=\"empty.txt\""));
            WorkspaceClient.Result empty = client.execute(WorkspaceClient.MANAGED_DOWNLOAD, null,
                    query, null, null, false);
            assertTrue(empty.successful());
            assertEquals("empty.txt", empty.fileName);
            assertEquals(0, empty.data.length);
            RecordedRequest download = request(mock);
            assertEquals("/workspace/empty.txt", download.getRequestUrl().queryParameter("path"));
            assertNull(download.getHeader("Range"));

            mock.enqueue(new MockResponse().setHeader("Content-Length", 32 * 1024 * 1024 + 1));
            assertThrows(IOException.class, () -> client.execute(WorkspaceClient.MANAGED_DOWNLOAD, null,
                    query, null, null, false));
        }
    }

    @Test(timeout = 15000)
    public void retriesScopedWorkspaceReadsOnceOnAFreshConnection() throws Exception {
        WorkspaceCatalog catalog = loadBundledCatalog();
        for (String path : List.of("/api/model/info", "/api/analytics/models", "/api/files", "/api/files/download")) {
            try (MockWebServer mock = mock()) {
                WorkspaceClient client = client(mock, catalog);
                warmWorkspaceSocket(mock, client);
                mock.enqueue(disconnectBeforeHeaders());
                mock.enqueue(new MockResponse().setBody("recovered"));

                JSONObject query = path.endsWith("/download")
                        ? new JSONObject().put("path", "/workspace/note.txt") : null;
                assertEquals("recovered", client.execute("rest:GET:" + path, null, query,
                        null, null, false).text());
                assertFreshRetry(mock, "/hermes" + path, null);
            }
        }
    }

    @Test(timeout = 15000)
    public void retriesManagedPreviewWithTheSameRangeAndBoundCredentials() throws Exception {
        try (MockWebServer mock = mock()) {
            WorkspaceClient client = client(mock, loadBundledCatalog());
            warmWorkspaceSocket(mock, client);
            mock.enqueue(disconnectBeforeHeaders());
            mock.enqueue(new MockResponse().setResponseCode(206)
                    .setHeader("Content-Range", "bytes 0-4/5").setBody("hello"));

            WorkspaceClient.Result preview = client.previewManagedFile("/workspace/note.txt");
            assertEquals("hello", ((JSONObject) preview.json()).getString("text"));
            assertFreshRetry(mock, "/hermes/api/files/download", "bytes=0-" + WorkspaceClient.FILE_PREVIEW_BYTES);
        }
    }

    @Test(timeout = 15000)
    public void doesNotReplayOtherWorkspaceReadsOrConfirmedWrites() throws Exception {
        try (MockWebServer mock = mock()) {
            WorkspaceClient client = client(mock, loadBundledCatalog());
            warmWorkspaceSocket(mock, client);
            mock.enqueue(disconnectBeforeHeaders());
            assertThrows(IOException.class, () -> client.execute("rest:GET:/api/profiles", null,
                    null, null, null, false));
            assertEquals(1, request(mock).getSequenceNumber());
            assertEquals(2, mock.getRequestCount());

            warmWorkspaceSocket(mock, client);
            mock.enqueue(disconnectBeforeHeaders());
            JSONObject body = new JSONObject().put("path", "/workspace/note.txt").put("content", "once");
            assertThrows(IOException.class, () -> client.execute(WRITE, null, null, body, null, true));
            RecordedRequest write = request(mock);
            assertEquals("POST", write.getMethod());
            assertEquals(1, write.getSequenceNumber());
            assertEquals(4, mock.getRequestCount());
        }
    }

    @Test(timeout = 15000)
    public void rejectsUnlistedTargetsAndDoesNotFollowRedirects() throws Exception {
        try (MockWebServer mock = mock()) {
            WorkspaceCatalog catalog = loadBundledCatalog();
            WorkspaceClient client = client(mock, catalog);
            assertThrows(IllegalArgumentException.class, () -> new WorkspaceClient(
                    origin(mock), auth(mock), catalog));
            assertThrows(IllegalArgumentException.class, () -> new WorkspaceClient(
                    "https://key@hermes.example", auth(mock), catalog));
            assertThrows(IllegalArgumentException.class, () -> new WorkspaceClient(
                    "https://hermes.example/outer/%2e%2e/", auth(mock), catalog));

            for (String path : List.of("..", "dev/other", "%2f", "test?profile=wrong", "two\\three")) {
                assertThrows(IllegalArgumentException.class, () -> client.execute(MESSAGES,
                        new JSONObject().put("session_id", path), null, null, null, false));
            }
            assertThrows(IllegalArgumentException.class, () -> client.execute(MESSAGES,
                    new JSONObject().put("session_id", "one"),
                    new JSONObject().put("ticket", "extra"), null, null, false));
            assertThrows(IllegalArgumentException.class, () -> client.execute(DOWNLOAD, null,
                    new JSONObject().put("path", "C:\\Workspace\\..\\.env"), null, null, false));
            assertThrows(IllegalArgumentException.class, () -> client.execute(DOWNLOAD, null,
                    new JSONObject().put("path", "/workspace/.env"), null, null, false));
            assertThrows(IllegalArgumentException.class, () -> client.execute("rest:POST:/api/env/reveal", null,
                    null, new JSONObject().put("key", "A"), null, true));
            assertThrows(IllegalArgumentException.class, () -> client.execute("rest:GET:/api/config", null,
                    null, null, null, false));
            JSONObject unsafeConfig = new JSONObject().put("config", new JSONObject().put("model",
                    new JSONObject().put("base_url", "https://other.invalid/v1")));
            assertThrows(IllegalArgumentException.class, () -> client.execute("rest:PUT:/api/config", null,
                    null, unsafeConfig, null, true));
            assertThrows(IllegalArgumentException.class, () -> client.execute("rest:GET:/anything", null,
                    null, null, null, false));
            assertEquals(0, mock.getRequestCount());

            mock.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", "https://example.invalid"));
            WorkspaceClient.Result redirect = client.execute("rest:GET:/api/profiles", null,
                    null, null, null, false);
            assertEquals(302, redirect.statusCode);
            assertFalse(redirect.successful());
            assertEquals(1, mock.getRequestCount());
        }
    }

    @Test(timeout = 15000)
    public void sendsMultipartAndJsonOnceAndRequiresConfirmation() throws Exception {
        try (MockWebServer mock = mock()) {
            WorkspaceCatalog catalog = loadBundledCatalog();
            WorkspaceClient client = client(mock, catalog);
            WorkspaceClient.Attachment file = new WorkspaceClient.Attachment("file", "note.txt",
                    "text/plain", "example attachment".getBytes(StandardCharsets.UTF_8));
            assertTrue(WorkspaceClient.requiresConfirmation(catalog.find(UPLOAD)));
            assertThrows(IllegalArgumentException.class,
                    () -> client.execute(UPLOAD, null, null, null, List.of(file), false));
            assertEquals(0, mock.getRequestCount());

            // OkHttp permits a follow-up for Retry-After: 0 even with connection retries disabled.
            mock.enqueue(new MockResponse().setResponseCode(503).setHeader("Retry-After", "0"));
            WorkspaceClient.Result unavailable = client.execute(UPLOAD, null, null, null, List.of(file), true);
            assertEquals(503, unavailable.statusCode);
            RecordedRequest uploaded = request(mock);
            assertEquals("/hermes/api/chat/file-upload", uploaded.getPath());
            assertTrue(uploaded.getHeader("Content-Type").startsWith("multipart/form-data;"));
            String contents = uploaded.getBody().readUtf8();
            assertTrue(contents.contains("name=\"file\"; filename=\"note.txt\""));
            assertTrue(contents.contains("example attachment"));
            assertThrows(IllegalStateException.class,
                    () -> client.execute(UPLOAD, null, null, null, List.of(file), true));
            assertEquals(1, mock.getRequestCount());

            JSONObject payload = new JSONObject().put("path", "/workspace/note.txt")
                    .put("content", "a document");
            mock.enqueue(new MockResponse().setResponseCode(403).setBody("{\"detail\":\"forbidden\"}"));
            WorkspaceClient.Result forbidden = client.execute(WRITE, null, null, payload, null, true);
            assertEquals(403, forbidden.statusCode);
            assertFalse(forbidden.successful());
            RecordedRequest written = request(mock);
            assertEquals("POST", written.getMethod());
            assertEquals("a document", new JSONObject(written.getBody().readUtf8()).getString("content"));
            assertEquals(2, mock.getRequestCount());
        }
    }

    @Test(timeout = 15000)
    public void sendsOneTicketedRpcAndReturnsItsActualResult() throws Exception {
        try (MockWebServer mock = mock()) {
            WorkspaceClient client = client(mock, loadBundledCatalog());
            mock.enqueue(new MockResponse().setBody("{\"ticket\":\"approved_test_ticket\"}"));
            mock.enqueue(new MockResponse().setHeader("Sec-WebSocket-Protocol", "hermes-gateway-v1")
                    .withWebSocketUpgrade(new WebSocketListener() {
                        @Override
                        public void onOpen(WebSocket socket, Response response) {
                            socket.send("{\"params\":{\"type\":\"gateway.ready\",\"payload\":"
                                    + "{\"session_authority\":true}}}");
                        }

                        @Override
                        public void onMessage(WebSocket socket, String message) {
                            JSONObject submitted = new JSONObject(message);
                            assertEquals("profiles.list", submitted.getString("method"));
                            socket.send(new JSONObject().put("jsonrpc", "2.0")
                                    .put("id", submitted.getString("id")).put("result", new JSONArray().put("main"))
                                    .toString());
                        }

                        @Override
                        public void onClosing(WebSocket socket, int code, String reason) {
                            socket.close(1000, null);
                        }
                    }));

            WorkspaceClient.Result result = client.execute("rpc:profiles.list", null, null, null, null, false);
            assertTrue(result.successful());
            assertEquals("main", ((JSONArray) result.json()).getString(0));
            RecordedRequest ticket = request(mock);
            assertEquals("/hermes/api/webapp/socket-ticket?socket=gateway", ticket.getPath());
            assertEquals("Bearer " + TOKEN, ticket.getHeader("Authorization"));
            RecordedRequest upgrade = request(mock);
            assertEquals("/hermes/api/ws", upgrade.getPath());
            assertEquals("hermes-gateway-v1, hermes-gateway-ticket.approved_test_ticket",
                    upgrade.getHeader("Sec-WebSocket-Protocol"));
            assertNull(upgrade.getHeader("Authorization"));
        }
    }

    @Test(timeout = 15000)
    public void usesFixedConditionalPluginRoutesAndPreservesServerUnavailability() throws Exception {
        try (MockWebServer mock = mock()) {
            WorkspaceCatalog catalog = loadBundledCatalog();
            WorkspaceClient client = client(mock, catalog);
            JSONObject query = new JSONObject().put("board", "alpha").put("profile", "work")
                    .put("include_archived", false);
            mock.enqueue(new MockResponse().setBody("{\"tasks\":[]}"));
            assertEquals(0, ((JSONObject) client.execute(KANBAN_BOARD, null, query, null, null, false)
                    .json()).getJSONArray("tasks").length());
            RecordedRequest listing = request(mock);
            assertEquals("/hermes/api/plugins/kanban/board", listing.getRequestUrl().encodedPath());
            assertEquals("alpha", listing.getRequestUrl().queryParameter("board"));
            assertEquals("work", listing.getRequestUrl().queryParameter("profile"));
            assertEquals("false", listing.getRequestUrl().queryParameter("include_archived"));
            assertEquals("Bearer " + TOKEN, listing.getHeader("Authorization"));
            assertEquals(origin(mock), listing.getHeader("Origin"));

            mock.enqueue(new MockResponse().setResponseCode(404));
            assertEquals(404, client.execute(KANBAN_BOARD, null, null, null, null, false).statusCode);
            assertEquals(2, mock.getRequestCount());

            for (String id : List.of("rest:GET:/api/plugins/{name}/{path:path}",
                    "rest:GET:/api/relay/{connection_id}/{path:path}", "rest:GET:/api/config/raw",
                    "rest:POST:/api/plugins/kanban/dispatch", "rest:POST:/api/plugins/kanban/boards/import",
                    "rest:GET:/api/plugins/kanban/runs/{run_id}/inspect", "rpc:session.automation",
                    "rest:POST:/api/providers/oauth/{provider_id}/start")) {
                assertNotNull(catalog.find(id));
                assertThrows(IllegalArgumentException.class, () -> client.execute(id, null, null, null, null, true));
            }
            assertEquals(2, mock.getRequestCount());
        }
    }

    @Test(timeout = 15000)
    public void sendsConfirmedPluginTriageAndMultipartAndReturnsAttachmentBytes() throws Exception {
        try (MockWebServer mock = mock()) {
            WorkspaceCatalog catalog = loadBundledCatalog();
            WorkspaceClient client = client(mock, catalog);
            JSONObject query = new JSONObject().put("board", "alpha");
            JSONObject triage = new JSONObject().put("title", "Review notes").put("triage", true);
            assertThrows(IllegalArgumentException.class, () -> client.execute(WorkspaceClient.CREATE_KANBAN_TASK,
                    null, query, triage, null, false));
            for (JSONObject unsafe : List.of(new JSONObject(triage.toString()).put("assignee", "worker"),
                    new JSONObject(triage.toString()).put("triage", false),
                    new JSONObject().put("title", "Missing explicit triage"))) {
                assertThrows(IllegalArgumentException.class, () -> client.execute(WorkspaceClient.CREATE_KANBAN_TASK,
                        null, query, unsafe, null, true));
            }
            assertEquals(0, mock.getRequestCount());

            mock.enqueue(new MockResponse().setBody("{\"task\":{\"id\":\"t42\"}}"));
            assertTrue(client.execute(WorkspaceClient.CREATE_KANBAN_TASK, null, query, triage, null, true)
                    .successful());
            RecordedRequest created = request(mock);
            JSONObject task = new JSONObject(created.getBody().readUtf8());
            assertEquals("alpha", created.getRequestUrl().queryParameter("board"));
            assertEquals("Review notes", task.getString("title"));
            assertTrue(task.getBoolean("triage"));
            assertFalse(task.has("assignee"));
            assertTrue(triage.similar(task));

            JSONObject taskPath = new JSONObject().put("task_id", "t42");
            byte[] contents = "note 补充".getBytes(StandardCharsets.UTF_8);
            WorkspaceClient.Attachment file = new WorkspaceClient.Attachment("file", "note.txt", "text/plain",
                    contents);
            assertEquals(25 * 1024 * 1024, WorkspaceClient.attachmentLimit(catalog.find(KANBAN_UPLOAD)));
            assertThrows(IllegalArgumentException.class, () -> client.execute(KANBAN_UPLOAD, taskPath, query,
                    null, List.of(file), false));
            assertEquals(1, mock.getRequestCount());
            mock.enqueue(new MockResponse().setBody("{\"attachment\":{\"id\":9}}"));
            assertTrue(client.execute(KANBAN_UPLOAD, taskPath, query, new JSONObject().put("uploaded_by", "me"),
                    List.of(file), true).successful());
            RecordedRequest uploaded = request(mock);
            assertEquals("/hermes/api/plugins/kanban/tasks/t42/attachments", uploaded.getRequestUrl().encodedPath());
            assertTrue(uploaded.getHeader("Content-Type").startsWith("multipart/form-data; boundary="));
            String multipart = uploaded.getBody().readUtf8();
            assertTrue(multipart.contains("name=\"uploaded_by\""));
            assertTrue(multipart.contains("name=\"file\"; filename=\"note.txt\""));
            assertTrue(multipart.contains("note 补充"));

            mock.enqueue(new MockResponse().setHeader("Content-Type", "text/plain")
                    .setHeader("Content-Disposition", "attachment; filename=\"note.txt\"")
                    .setBody(new Buffer().write(contents)));
            WorkspaceClient.Result downloaded = client.execute(KANBAN_DOWNLOAD,
                    new JSONObject().put("attachment_id", 9), query, null, null, false);
            assertEquals("/hermes/api/plugins/kanban/attachments/9", request(mock).getRequestUrl().encodedPath());
            assertEquals("note.txt", downloaded.fileName);
            assertArrayEquals(contents, downloaded.data);
        }
    }

    @Test(timeout = 15000)
    public void projectWritesUseStandaloneTicketAndPreserveTheSelectedProfileAndFolders() throws Exception {
        try (MockWebServer mock = mock()) {
            WorkspaceCatalog catalog = loadBundledCatalog();
            WorkspaceClient client = client(mock, catalog);
            JSONObject create = new JSONObject().put("profile", "research")
                    .put("name", "Fixture").put("folders", new JSONArray().put("C:\\Users\\local\\study"))
                    .put("use", false);
            assertThrows(IllegalArgumentException.class,
                    () -> client.execute("rpc:projects.create", null, null, create, null, false));
            assertThrows(IllegalArgumentException.class, () -> client.execute("rpc:projects.create", null, null,
                    new JSONObject(create.toString()).put("folders", new JSONArray().put("/workspace/.env")),
                    null, true));
            assertEquals(0, mock.getRequestCount());

            for (String method : List.of("projects.create", "projects.update", "projects.add_folder",
                    "projects.remove_folder", "projects.set_primary", "projects.archive", "projects.delete",
                    "projects.set_active", "projects.project_sessions")) {
                JSONObject params = method.equals("projects.create") ? create
                        : new JSONObject().put("profile", "research");
                if (method.equals("projects.project_sessions")) {
                    params.put("project_id", "p_one").put("session_limit", 8);
                } else if (!method.equals("projects.create")) {
                    params.put("id", "p_one");
                    if (List.of("projects.add_folder", "projects.remove_folder", "projects.set_primary")
                            .contains(method)) {
                        params.put("path", "/workspace/current");
                    }
                }
                boolean confirmed = !method.equals("projects.project_sessions");
                assertEquals(confirmed, WorkspaceClient.requiresConfirmation(catalog.find("rpc:" + method)));
                List<JSONObject> frames = new CopyOnWriteArrayList<>();
                enqueueAttachedSocket(mock, frames, null, null);
                WorkspaceClient.Result result = client.execute("rpc:" + method, null, null, params, null, confirmed);
                assertTrue(result.successful());
                assertEquals("/hermes/api/webapp/socket-ticket?socket=gateway&profile=research", request(mock).getPath());
                assertEquals("/hermes/api/ws", request(mock).getPath());
                assertEquals(1, frames.size());
                assertEquals(method, frames.get(0).getString("method"));
                assertTrue(params.similar(frames.get(0).getJSONObject("params")));
            }
        }
    }

    @Test(timeout = 15000)
    public void sendsConfirmedModelKeyOnlyInTheScopedGatewayFrame() throws Exception {
        try (MockWebServer mock = mock()) {
            WorkspaceClient client = client(mock, loadBundledCatalog());
            String id = "rpc:model.save_key";
            JSONObject query = new JSONObject().put("profile", "research");
            JSONObject params = new JSONObject().put("slug", "example-provider").put("api_key", "test_key_value");
            assertThrows(IllegalArgumentException.class, () -> client.execute(id, null, query, params, null, false));
            assertEquals(0, mock.getRequestCount());
            List<JSONObject> frames = new CopyOnWriteArrayList<>();
            enqueueAttachedSocket(mock, frames, null, null);
            assertTrue(client.execute(id, null, query, params, null, true).successful());
            RecordedRequest ticket = request(mock);
            assertEquals("/hermes/api/webapp/socket-ticket?socket=gateway&profile=research", ticket.getPath());
            assertFalse(ticket.getBody().readUtf8().contains("test_key_value"));
            RecordedRequest upgrade = request(mock);
            assertEquals("/hermes/api/ws", upgrade.getPath());
            assertNull(upgrade.getHeader("Authorization"));
            assertEquals(1, frames.size());
            assertEquals("model.save_key", frames.get(0).getString("method"));
            assertTrue(params.similar(frames.get(0).getJSONObject("params")));
        }
    }

    @Test(timeout = 15000)
    public void readsProfileConfigDirectlyAndSessionConfigAfterOpeningTheSession() throws Exception {
        try (MockWebServer mock = mock()) {
            WorkspaceClient client = client(mock, loadBundledCatalog());
            List<JSONObject> frames = new CopyOnWriteArrayList<>();
            enqueueAttachedSocket(mock, frames, null, null);
            JSONObject profileRead = new JSONObject().put("key", "mtime").put("profile", "work");
            assertTrue(client.execute("rpc:config.get", null, null, profileRead, null, false).successful());
            assertEquals(1, frames.size());
            assertEquals("config.get", frames.get(0).getString("method"));
            assertTrue(profileRead.similar(frames.get(0).getJSONObject("params")));

            frames.clear();
            enqueueAttachedSocket(mock, frames,
                    new JSONObject().put("session_id", "current").put("subscription_id", "viewer"), null);
            JSONObject sessionRead = new JSONObject().put("key", "reasoning").put("session_id", "stored");
            assertTrue(client.execute("rpc:config.get", null, null, sessionRead, null, false).successful());
            assertEquals(2, frames.size());
            assertEquals("session.resume", frames.get(0).getString("method"));
            assertEquals("config.get", frames.get(1).getString("method"));
            JSONObject bound = frames.get(1).getJSONObject("params");
            assertEquals("current", bound.getString("session_id"));
            assertEquals("reasoning", bound.getString("key"));
        }
    }

    @Test(timeout = 15000)
    public void authorizesOAuthInventoryAndRequiresConfirmationToDisconnect() throws Exception {
        try (MockWebServer mock = mock()) {
            WorkspaceClient client = client(mock, loadBundledCatalog());
            JSONObject profile = new JSONObject().put("profile", "research");
            mock.enqueue(new MockResponse().setBody("{\"providers\":[]}"));
            assertTrue(client.execute(WorkspaceClient.OAUTH_PROVIDER_INVENTORY, null, profile, null, null, false)
                    .successful());
            RecordedRequest inventory = request(mock);
            assertEquals("/hermes/api/providers/oauth", inventory.getRequestUrl().encodedPath());
            assertEquals("research", inventory.getRequestUrl().queryParameter("profile"));
            assertEquals("Bearer " + TOKEN, inventory.getHeader("Authorization"));

            String disconnect = "rest:DELETE:/api/providers/oauth/{provider_id}";
            JSONObject provider = new JSONObject().put("provider_id", "nous");
            assertThrows(IllegalArgumentException.class,
                    () -> client.execute(disconnect, provider, profile, null, null, false));
            assertEquals(1, mock.getRequestCount());
            mock.enqueue(new MockResponse().setResponseCode(409));
            assertEquals(409, client.execute(disconnect, provider, profile, null, null, true).statusCode);
            RecordedRequest removed = request(mock);
            assertEquals("DELETE", removed.getMethod());
            assertEquals("/hermes/api/providers/oauth/nous", removed.getRequestUrl().encodedPath());
            assertEquals("research", removed.getRequestUrl().queryParameter("profile"));
        }
    }

    @Test(timeout = 15000)
    public void checksConfirmedGroupCreationAgainstTheSameGatewaysSeatableProfiles() throws Exception {
        try (MockWebServer mock = mock()) {
            WorkspaceClient client = client(mock, loadBundledCatalog());
            JSONObject first = new JSONObject().put("member_id", "writer").put("profile", "writer")
                    .put("handle", "writer");
            JSONObject second = new JSONObject().put("member_id", "reviewer").put("profile", "reviewer")
                    .put("handle", "reviewer");
            JSONObject create = new JSONObject().put("profile", "work").put("room_id", "room_fixture")
                    .put("name", "Workspace fixture").put("members", new JSONArray().put(first).put(second));
            assertThrows(IllegalArgumentException.class,
                    () -> client.execute(WorkspaceClient.CREATE_GROUP, null, null, create, null, false));
            JSONObject remote = new JSONObject(second.toString()).put("target",
                    new JSONObject().put("kind", "peer").put("profile", "reviewer"));
            JSONObject remoteGroup = new JSONObject(create.toString()).put("members", new JSONArray().put(first)
                    .put(remote));
            assertThrows(IllegalArgumentException.class,
                    () -> client.execute(WorkspaceClient.CREATE_GROUP, null, null, remoteGroup, null, true));
            assertEquals(0, mock.getRequestCount());

            List<JSONObject> frames = new CopyOnWriteArrayList<>();
            enqueueGroupSocket(mock, frames, true, List.of("groups.create"), List.of("writer"));
            assertThrows(IllegalArgumentException.class,
                    () -> client.execute(WorkspaceClient.CREATE_GROUP, null, null, create, null, true));
            assertEquals(1, frames.size());
            assertEquals("groups.capabilities", frames.get(0).getString("method"));
            request(mock);
            request(mock);

            frames.clear();
            JSONObject next = new JSONObject(create.toString()).put("name", "A second fixture");
            enqueueGroupSocket(mock, frames, false, List.of("groups.create"), List.of("writer", "reviewer"));
            assertTrue(client.execute(WorkspaceClient.CREATE_GROUP, null, null, next, null, true).successful());
            assertEquals("/hermes/api/webapp/socket-ticket?socket=gateway&profile=work", request(mock).getPath());
            assertEquals("/hermes/api/ws", request(mock).getPath());
            assertEquals(2, frames.size());
            assertEquals("groups.create", frames.get(1).getString("method"));
            assertTrue(next.similar(frames.get(1).getJSONObject("params")));
        }
    }

    @Test(timeout = 15000)
    public void postsOnlyConfirmedPlainTextWithStableIdentityOnAnAdvertisedGroupDriver() throws Exception {
        try (MockWebServer mock = mock()) {
            WorkspaceClient client = client(mock, loadBundledCatalog());
            JSONObject post = new JSONObject().put("room_id", "room_fixture").put("profile", "work")
                    .put("event_id", "event_1").put("payload",
                            new JSONObject().put("thread_id", "event_1").put("text", "Please review the outline"));
            assertThrows(IllegalArgumentException.class,
                    () -> client.execute(WorkspaceClient.SEND_GROUP_MESSAGE, null, null, post, null, false));
            JSONObject missingIdentity = new JSONObject(post.toString());
            missingIdentity.remove("event_id");
            assertThrows(IllegalArgumentException.class,
                    () -> client.execute(WorkspaceClient.SEND_GROUP_MESSAGE, null, null, missingIdentity, null, true));
            assertEquals(0, mock.getRequestCount());

            List<JSONObject> frames = new CopyOnWriteArrayList<>();
            enqueueGroupSocket(mock, frames, false, List.of("groups.send"), List.of());
            assertThrows(IllegalArgumentException.class,
                    () -> client.execute(WorkspaceClient.SEND_GROUP_MESSAGE, null, null, post, null, true));
            assertEquals(1, frames.size());
            assertEquals("groups.capabilities", frames.get(0).getString("method"));
            request(mock);
            request(mock);

            frames.clear();
            JSONObject next = new JSONObject(post.toString()).put("event_id", "event_2");
            enqueueGroupSocket(mock, frames, true, List.of("groups.send"), List.of());
            assertTrue(client.execute(WorkspaceClient.SEND_GROUP_MESSAGE, null, null, next, null, true).successful());
            assertEquals(2, frames.size());
            assertEquals("groups.send", frames.get(1).getString("method"));
            assertTrue(next.similar(frames.get(1).getJSONObject("params")));
        }
    }

    private static void enqueueGroupSocket(MockWebServer mock, List<JSONObject> frames, boolean hasDriver,
            List<String> methods, List<String> seatableProfiles) {
        mock.enqueue(new MockResponse().setBody("{\"ticket\":\"approved_test_ticket\"}"));
        mock.enqueue(new MockResponse().setHeader("Sec-WebSocket-Protocol", "hermes-gateway-v1")
                .withWebSocketUpgrade(new WebSocketListener() {
                    @Override
                    public void onOpen(WebSocket socket, Response response) {
                        socket.send("{\"params\":{\"type\":\"gateway.ready\",\"payload\":"
                                + "{\"session_authority\":true}}}");
                    }

                    @Override
                    public void onMessage(WebSocket socket, String message) {
                        JSONObject submitted = new JSONObject(message);
                        frames.add(submitted);
                        String method = submitted.getString("method");
                        JSONObject result = "groups.capabilities".equals(method)
                                ? new JSONObject().put("persistent_process", true).put("driver", hasDriver)
                                    .put("methods", new JSONArray(methods))
                                    .put("seatable_profiles", new JSONArray(seatableProfiles))
                                : new JSONObject().put("accepted", true);
                        socket.send(new JSONObject().put("jsonrpc", "2.0").put("id", submitted.getString("id"))
                                .put("result", result).toString());
                    }

                    @Override
                    public void onClosing(WebSocket socket, int code, String reason) {
                        socket.close(1000, null);
                    }
                }));
    }

    @Test(timeout = 15000)
    public void resumesOnTicketedSocketAndUsesAttachedSessionForAllWorkspaceReads() throws Exception {
        WorkspaceCatalog catalog = loadBundledCatalog();
        for (String method : List.of("session.context_breakdown", "session.control.read", "session.events.since",
                "session.history", "subagent.list", "tools.show")) {
            try (MockWebServer mock = mock()) {
                WorkspaceClient client = client(mock, catalog);
                List<JSONObject> frames = new CopyOnWriteArrayList<>();
                enqueueAttachedSocket(mock, frames, new JSONObject().put("session_id", "active-tip")
                        .put("subscription_id", "viewer-1"), null);
                JSONObject body = new JSONObject().put("session_id", "archived-segment");
                JSONObject query = new JSONObject();
                boolean ticketProfileOnly = "tools.show".equals(method);
                (ticketProfileOnly ? query : body).put("profile", "research");
                if ("session.events.since".equals(method)) {
                    body.put("last_sequence", 42);
                }
                WorkspaceClient.Result result = client.execute("rpc:" + method, null, query, body, null, false);
                assertTrue(result.successful());
                assertEquals(method, ((JSONObject) result.json()).getString("method"));
                assertEquals("/hermes/api/webapp/socket-ticket?socket=gateway&profile=research", request(mock).getPath());
                RecordedRequest upgrade = request(mock);
                assertEquals("/hermes/api/ws", upgrade.getPath());
                assertNull(upgrade.getHeader("Authorization"));
                assertEquals(2, frames.size());
                assertEquals("session.resume", frames.get(0).getString("method"));
                JSONObject resumed = frames.get(0).getJSONObject("params");
                assertEquals("archived-segment", resumed.getString("session_id"));
                assertTrue(resumed.getBoolean("omit_messages"));
                JSONObject sent = frames.get(1).getJSONObject("params");
                assertEquals("active-tip", sent.getString("session_id"));
                assertEquals(ticketProfileOnly ? "" : "research", sent.optString("profile", ""));
                if ("session.events.since".equals(method)) {
                    assertEquals(42, sent.getInt("last_sequence"));
                }
                assertEquals("archived-segment", body.getString("session_id"));
            }
        }
    }

    @Test(timeout = 15000)
    public void doesNotRunWorkspaceReadIfSessionResumeIsMissingOrDenied() throws Exception {
        try (MockWebServer mock = mock()) {
            WorkspaceClient client = client(mock, loadBundledCatalog());
            String id = "rpc:tools.show";
            assertThrows(IllegalArgumentException.class, () -> client.execute(id, null, null, null, null, false));
            assertEquals(0, mock.getRequestCount());

            JSONObject body = new JSONObject().put("session_id", "stored");
            List<JSONObject> frames = new CopyOnWriteArrayList<>();
            enqueueAttachedSocket(mock, frames, new JSONObject().put("session_id", "active-tip"), null);
            assertThrows(IOException.class, () -> client.execute(id, null, null, body, null, false));
            assertEquals(1, frames.size());

            frames.clear();
            JSONObject denied = new JSONObject().put("code", -32003).put("message", "permission_denied");
            enqueueAttachedSocket(mock, frames, null, denied);
            WorkspaceClient.Result result = client.execute(id, null, null, body, null, false);
            assertFalse(result.successful());
            assertEquals(Integer.valueOf(-32003), result.rpcCode);
            assertEquals(1, frames.size());
            assertEquals("session.resume", frames.get(0).getString("method"));
        }
    }

    @Test(timeout = 15000)
    public void projectsOnlyProcessStatusAndMcpRegistrationMetadata() throws Exception {
        try (MockWebServer mock = mock()) {
            WorkspaceClient client = client(mock, loadBundledCatalog());
            List<JSONObject> frames = new CopyOnWriteArrayList<>();
            JSONObject process = new JSONObject().put("session_id", "task-id").put("status", "running")
                    .put("command", "fixture secret command").put("output_tail", "fixture secret output");
            enqueueAttachedSocket(mock, frames,
                    new JSONObject().put("session_id", "current").put("subscription_id", "viewer"), null,
                    new JSONObject().put("processes", new JSONArray().put(process)));
            String processWire = client.execute("rpc:process.list", null, null, new JSONObject()
                    .put("session_id", "stored"), null, false).text();
            assertFalse(processWire.contains("fixture secret"));
            assertEquals("running", new JSONObject(processWire).getJSONArray("processes")
                    .getJSONObject(0).getString("status"));
            assertEquals("session.resume", frames.get(0).getString("method"));
            assertEquals("process.list", frames.get(1).getString("method"));
            assertEquals("current", frames.get(1).getJSONObject("params").getString("session_id"));

            frames.clear();
            JSONObject service = new JSONObject().put("name", "notes").put("transport", "stdio")
                    .put("enabled", true).put("env", new JSONObject().put("SECRET", "fixture secret"));
            enqueueAttachedSocket(mock, frames, null, null,
                    new JSONObject().put("servers", new JSONArray().put(service)));
            String serviceWire = client.execute("rpc:mcp.servers.list", null, null, null, null, false).text();
            assertFalse(serviceWire.contains("fixture secret"));
            assertTrue(new JSONObject(serviceWire).getJSONArray("servers").getJSONObject(0).getBoolean("enabled"));
            assertEquals(1, frames.size());
        }
    }

    @Test(timeout = 15000)
    public void supportsOnlyRedactedSessionFilesAndScopedToolActions() throws Exception {
        try (MockWebServer mock = mock()) {
            WorkspaceClient client = client(mock, loadBundledCatalog());
            String export = "rpc:session.export";
            JSONObject body = new JSONObject().put("session_id", "stored").put("format", "md");
            assertThrows(IllegalArgumentException.class, () -> client.execute(export, null, null, body, null, false));
            assertThrows(IllegalArgumentException.class, () -> client.execute("rpc:config.set", null, null,
                    new JSONObject().put("key", "yolo").put("value", true), null, true));
            assertThrows(IllegalArgumentException.class, () -> client.execute("rpc:skills.manage", null, null,
                    new JSONObject().put("action", "install").put("query", "fixture"), null, true));
            assertThrows(IllegalArgumentException.class, () -> client.execute("rpc:learning.frames", null, null,
                    new JSONObject().put("cols", 800).put("rows", 40).put("frames", 200), null, true));
            assertEquals(0, mock.getRequestCount());

            List<JSONObject> frames = new CopyOnWriteArrayList<>();
            enqueueAttachedSocket(mock, frames,
                    new JSONObject().put("session_id", "current").put("subscription_id", "viewer"), null,
                    new JSONObject().put("format", "md").put("filename", "export.md")
                        .put("content", "A redacted fixture note"));
            WorkspaceClient.Result saved = client.execute(export, null, null, body.put("redact", true), null, false);
            assertEquals("export.md", saved.fileName);
            assertEquals("A redacted fixture note", saved.text());
            assertEquals("session.resume", frames.get(0).getString("method"));
            assertEquals("session.export", frames.get(1).getString("method"));
            assertTrue(frames.get(1).getJSONObject("params").getBoolean("redact"));
        }
    }

    @Test(timeout = 15000)
    public void checksRoomFileIntegrityAndKeepsAbsentScreenImagesAsStatus() throws Exception {
        try (MockWebServer mock = mock()) {
            WorkspaceClient client = client(mock, loadBundledCatalog());
            List<JSONObject> frames = new CopyOnWriteArrayList<>();
            JSONObject request = new JSONObject().put("room_id", "room").put("event_id", "msg")
                    .put("attachment_id", "att");
            String download = "rpc:groups.attachment.download";
            JSONObject file = new JSONObject().put("name", "note.txt").put("mime", "text/plain")
                    .put("data_base64", "aGVsbG8=").put("size", 5)
                    .put("sha256", "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
            enqueueAttachedSocket(mock, frames, null, null, file);
            WorkspaceClient.Result result = client.execute(download, null, null, request, null, false);
            assertEquals("note.txt", result.fileName);
            assertEquals("hello", result.text());

            enqueueAttachedSocket(mock, frames, null, null, new JSONObject(file.toString()).put("size", 6));
            assertThrows(IOException.class, () -> client.execute(download, null, null, request, null, false));

            enqueueAttachedSocket(mock, frames, null, null, new JSONObject().put("data_url", JSONObject.NULL)
                    .put("suppressed", "human_has_control"));
            WorkspaceClient.Result hidden = client.execute("rpc:display.thumbnail", null, null, null, null, false);
            assertNull(hidden.fileName);
            assertEquals("human_has_control", ((JSONObject) hidden.json()).getString("suppressed"));
        }
    }

    private static void enqueueAttachedSocket(MockWebServer mock, List<JSONObject> frames, JSONObject snapshot,
            JSONObject resumeError) {
        enqueueAttachedSocket(mock, frames, snapshot, resumeError, null);
    }

    private static void enqueueAttachedSocket(MockWebServer mock, List<JSONObject> frames, JSONObject snapshot,
            JSONObject resumeError, JSONObject operationResponse) {
        mock.enqueue(new MockResponse().setBody("{\"ticket\":\"approved_test_ticket\"}"));
        mock.enqueue(new MockResponse().setHeader("Sec-WebSocket-Protocol", "hermes-gateway-v1")
                .withWebSocketUpgrade(new WebSocketListener() {
                    @Override
                    public void onOpen(WebSocket socket, Response response) {
                        socket.send("{\"params\":{\"type\":\"gateway.ready\",\"payload\":"
                                + "{\"session_authority\":true}}}");
                    }

                    @Override
                    public void onMessage(WebSocket socket, String message) {
                        JSONObject submitted = new JSONObject(message);
                        frames.add(submitted);
                        String method = submitted.getString("method");
                        JSONObject reply = new JSONObject().put("jsonrpc", "2.0").put("id", submitted.getString("id"));
                        if ("session.resume".equals(method)) {
                            reply.put(resumeError == null ? "result" : "error",
                                    resumeError == null ? snapshot : resumeError);
                        } else {
                            reply.put("result", operationResponse == null ? new JSONObject().put("method", method)
                                    : operationResponse);
                        }
                        socket.send(reply.toString());
                    }

                    @Override
                    public void onClosing(WebSocket socket, int code, String reason) {
                        socket.close(1000, null);
                    }
                }));
    }

    private static WorkspaceCatalog loadBundledCatalog() throws IOException {
        try (InputStream stream = WorkspaceClientWireTest.class.getResourceAsStream("/" + WorkspaceCatalog.ASSET_NAME)) {
            assertNotNull(stream);
            return WorkspaceCatalog.load(stream);
        }
    }

    private static JSONObject managedEntry(String name, String path, boolean directory) {
        return new JSONObject().put("name", name).put("path", path).put("is_directory", directory);
    }

    private static void warmWorkspaceSocket(MockWebServer mock, WorkspaceClient client) throws Exception {
        mock.enqueue(new MockResponse().setBody("{\"entries\":[]}"));
        assertTrue(client.execute(WorkspaceClient.MANAGED_FILES, null, null, null, null, false).successful());
        assertEquals(0, request(mock).getSequenceNumber());
    }

    private static MockResponse disconnectBeforeHeaders() {
        return new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST);
    }

    private static void assertFreshRetry(MockWebServer mock, String expectedPath, String expectedRange)
            throws InterruptedException {
        assertEquals(3, mock.getRequestCount());
        RecordedRequest stale = request(mock);
        RecordedRequest fresh = request(mock);
        assertEquals("GET", stale.getMethod());
        assertEquals("GET", fresh.getMethod());
        assertEquals(expectedPath, stale.getRequestUrl().encodedPath());
        assertEquals(stale.getPath(), fresh.getPath());
        assertEquals("Bearer " + TOKEN, stale.getHeader("Authorization"));
        assertEquals(stale.getHeader("Authorization"), fresh.getHeader("Authorization"));
        assertEquals(origin(mock), fresh.getHeader("Origin"));
        assertEquals(expectedRange, stale.getHeader("Range"));
        assertEquals(expectedRange, fresh.getHeader("Range"));
        assertEquals(1, stale.getSequenceNumber());
        assertEquals(0, fresh.getSequenceNumber());
    }

    private static MockWebServer mock() throws IOException {
        MockWebServer mock = new MockWebServer();
        mock.start(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 0);
        return mock;
    }

    private static WorkspaceClient client(MockWebServer mock, WorkspaceCatalog catalog) {
        return new WorkspaceClient(origin(mock) + "/hermes/", auth(mock), catalog, true);
    }

    private static HermesClient.Auth auth(MockWebServer mock) {
        return new HermesClient.Auth() {
            @Override
            public String cookieHeader(String requestUrl) {
                return null;
            }

            @Override
            public String accessToken(String requestUrl) {
                assertTrue(requestUrl.startsWith(origin(mock) + "/hermes/api/"));
                return TOKEN;
            }
        };
    }

    private static String origin(MockWebServer mock) {
        return "http://127.0.0.1:" + mock.getPort();
    }

    private static RecordedRequest request(MockWebServer mock) throws InterruptedException {
        RecordedRequest request = mock.takeRequest(2, TimeUnit.SECONDS);
        assertNotNull(request);
        return request;
    }
}
