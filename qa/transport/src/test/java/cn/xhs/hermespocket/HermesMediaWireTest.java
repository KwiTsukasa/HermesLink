package cn.xhs.hermespocket;

import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.HttpUrl;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.ByteString;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class HermesMediaWireTest {
    private static final String PROTOCOL = "hermes-gateway-v1";
    private static final byte[] PCM = { 0, 1, 2, 3 };

    @Test(timeout = 20000)
    public void transcribesFileAndProjectsOnlyMinimalStatus() throws Exception {
        try (Gateway gateway = new Gateway()) {
            HermesMediaClient.Status status = gateway.media.status(new HermesMediaClient.Operation());
            assertEquals(Boolean.TRUE, status.sttStreaming);
            assertEquals("direct", status.sttMode);
            assertEquals("relay", status.ttsMode);
            assertEquals("gpt-live", status.liveMode);
            assertEquals(Boolean.FALSE, status.gptLiveAvailable);

            byte[] selected = "native recording".getBytes(StandardCharsets.UTF_8);
            String text = gateway.media.transcribe("audio/wav", () -> new ByteArrayInputStream(selected),
                    new HermesMediaClient.Operation());
            assertEquals("文件转录", text);
            assertEquals(List.of("GET voice-config", "GET voice-live/status", "POST transcribe"),
                    gateway.calls);
            assertThrows(IOException.class, () -> gateway.media.transcribe("audio/wav\"",
                    () -> new ByteArrayInputStream(selected), new HermesMediaClient.Operation()));
            assertEquals(3, gateway.calls.size());
        }
    }

    @Test(timeout = 20000)
    public void sendsPcmAndEosOnPurposeTicketAndReadsPartialAndFinal() throws Exception {
        try (Gateway gateway = new Gateway()) {
            HermesMediaClient.Operation operation = new HermesMediaClient.Operation();
            LinkedBlockingQueue<String> partial = new LinkedBlockingQueue<>();
            try (HermesMediaClient.Transcription session = gateway.media.beginTranscription(
                    operation, partial::offer)) {
                session.push(PCM, PCM.length);
                assertEquals("半句", partial.poll(3, TimeUnit.SECONDS));
                assertEquals("完整转录", session.finish());
            }
            assertEquals(List.of("ticket transcribe-stream", "socket transcribe-stream",
                    "sample_rate", "pcm", "eos"), gateway.calls);
        }
    }

    @Test(timeout = 20000)
    public void cancellationCannotFinishOrRestartAnAudioOperation() throws Exception {
        try (Gateway gateway = new Gateway()) {
            HermesMediaClient.Operation operation = new HermesMediaClient.Operation();
            try (HermesMediaClient.Transcription session =
                         gateway.media.beginTranscription(operation, ignored -> {})) {
                assertTrue(gateway.micReady.await(3, TimeUnit.SECONDS));
                operation.cancel();
                assertThrows(IOException.class, () -> session.push(PCM, PCM.length));
                assertThrows(IOException.class, session::finish);
                assertThrows(IOException.class, () -> gateway.media.beginTranscription(operation, ignored -> {}));
                assertFalse(gateway.calls.contains("eos"));
            }
        }
    }

    @Test(timeout = 20000)
    public void streamsSpeechAndProvidesExplicitPostFallback() throws Exception {
        try (Gateway gateway = new Gateway()) {
            HermesMediaClient.Operation streaming = new HermesMediaClient.Operation();
            try (HermesMediaClient.Speech session = gateway.media.beginSpeech("朗读示例", streaming)) {
                HermesMediaClient.SpeechFrame start = session.next(streaming);
                assertEquals(24000, start.sampleRate);
                assertArrayEquals(PCM, session.next(streaming).audio.toByteArray());
                HermesMediaClient.SpeechFrame end = session.next(streaming);
                assertTrue(end.end);
                assertFalse(end.fallback);
            }
            assertFalse(gateway.calls.contains("POST speak"));
            HermesMediaClient.Operation fallback = new HermesMediaClient.Operation();
            try (HermesMediaClient.Speech session = gateway.media.beginSpeech("回退", fallback)) {
                assertTrue(session.next(fallback).fallback);
            }
            ByteArrayOutputStream audio = new ByteArrayOutputStream();
            gateway.media.synthesize("回退", fallback, audio);
            assertArrayEquals(new byte[] { 1, 2, 3 }, audio.toByteArray());
            assertEquals(List.of("ticket speak-stream", "socket speak-stream", "read text",
                    "ticket speak-stream", "socket speak-stream", "read text", "POST speak"),
                    gateway.calls);
        }
    }

    @Test(timeout = 20000)
    public void rejectsMalformedPcmAndFallbackAfterStreamingBytes() throws Exception {
        try (Gateway gateway = new Gateway()) {
            HermesMediaClient.Operation malformed = new HermesMediaClient.Operation();
            try (HermesMediaClient.Speech session = gateway.media.beginSpeech("畸形帧", malformed)) {
                assertThrows(IOException.class, () -> {
                    while (!session.next(malformed).end) {
                        // An invalid PCM frame fails the stream, even if start was already delivered.
                    }
                });
            }
            HermesMediaClient.Operation mixed = new HermesMediaClient.Operation();
            try (HermesMediaClient.Speech session = gateway.media.beginSpeech("部分输出", mixed)) {
                assertThrows(IOException.class, () -> {
                    while (!session.next(mixed).end) {
                        // A provider cannot request a second synthesis after it has streamed audio.
                    }
                });
            }
            assertFalse(gateway.calls.contains("POST speak"));
        }
    }

    private static final class Gateway implements AutoCloseable {
        private final MockWebServer server = new MockWebServer();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicReference<String> ticket = new AtomicReference<>();
        private final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        private final CountDownLatch micReady = new CountDownLatch(1);
        private final HermesMediaClient media;
        private final String origin;

        Gateway() throws IOException {
            server.start();
            origin = server.url("/").toString().replaceFirst("/$", "");
            media = new HermesMediaClient(new HermesClient(origin + "/edge", new HermesClient.Auth() {
                @Override
                public String cookieHeader(String url) {
                    return null;
                }

                @Override
                public String accessToken(String url) {
                    assertTrue(url.startsWith(origin + "/edge/api/"));
                    return "native-auth";
                }
            }));
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
            assertEquals(origin, request.getHeader("Origin"));
            String path = url.encodedPath();
            if (path.equals("/edge/api/webapp/socket-ticket")) {
                assertEquals("Bearer native-auth", request.getHeader("Authorization"));
                assertEquals("POST", request.getMethod());
                String purpose = url.queryParameter("socket");
                assertTrue("speak-stream".equals(purpose) || "transcribe-stream".equals(purpose));
                calls.add("ticket " + purpose);
                String value = purpose + "-" + UUID.randomUUID();
                ticket.set(value);
                return json(new JSONObject().put("ticket", value));
            }
            if (path.endsWith("-stream")) {
                assertNull(request.getHeader("Authorization"));
                assertNull(request.getHeader("Cookie"));
                assertNull(url.query());
                String purpose = path.substring(path.lastIndexOf('/') + 1);
                String granted = ticket.getAndSet(null);
                assertTrue(granted.startsWith(purpose + "-"));
                assertEquals(PROTOCOL + ", hermes-gateway-ticket." + granted,
                        request.getHeader("Sec-WebSocket-Protocol"));
                calls.add("socket " + purpose);
                return new MockResponse().setHeader("Sec-WebSocket-Protocol", PROTOCOL)
                        .withWebSocketUpgrade(listener("transcribe-stream".equals(purpose)));
            }
            assertEquals("Bearer native-auth", request.getHeader("Authorization"));
            assertNull(url.query());
            calls.add(request.getMethod() + " " + path.substring("/edge/api/audio/".length()));
            switch (path) {
                case "/edge/api/audio/voice-config":
                    return json(new JSONObject().put("ok", true)
                            .put("stt", new JSONObject().put("mode", "direct").put("streaming", true)
                                    .put("api_key", "fixture-secret"))
                            .put("tts", new JSONObject().put("mode", "relay")));
                case "/edge/api/audio/voice-live/status":
                    return json(new JSONObject().put("ok", true).put("mode", "gpt-live")
                            .put("available", false));
                case "/edge/api/audio/transcribe":
                    JSONObject file = new JSONObject(request.getBody().readUtf8());
                    assertEquals("audio/wav", file.getString("mime_type"));
                    assertEquals("data:audio/wav;base64,bmF0aXZlIHJlY29yZGluZw==",
                            file.getString("data_url"));
                    return json(new JSONObject().put("ok", true).put("transcript", "文件转录"));
                case "/edge/api/audio/speak":
                    JSONObject text = new JSONObject(request.getBody().readUtf8());
                    assertEquals("回退", text.getString("text"));
                    return json(new JSONObject().put("ok", true)
                            .put("data_url", "data:audio/wav;base64,AQID"));
                default:
                    throw new AssertionError("Unexpected audio route " + path);
            }
        }

        private WebSocketListener listener(boolean transcription) {
            return new WebSocketListener() {
                @Override
                public void onMessage(WebSocket socket, String raw) {
                    try {
                        JSONObject request = new JSONObject(raw);
                        if (transcription && request.has("sample_rate")) {
                            assertEquals(16000, request.getInt("sample_rate"));
                            calls.add("sample_rate");
                            micReady.countDown();
                        } else if (transcription) {
                            assertTrue(request.getBoolean("eos"));
                            calls.add("eos");
                            socket.send(new JSONObject().put("type", "final")
                                    .put("transcript", "完整转录").toString());
                            socket.close(1000, null);
                        } else {
                            assertTrue(request.getBoolean("done"));
                            calls.add("read text");
                            speak(socket, request.getString("text"));
                        }
                    } catch (RuntimeException | AssertionError error) {
                        failure.compareAndSet(null, error);
                        socket.cancel();
                    }
                }

                @Override
                public void onMessage(WebSocket socket, ByteString bytes) {
                    try {
                        assertTrue(transcription);
                        assertArrayEquals(PCM, bytes.toByteArray());
                        calls.add("pcm");
                        socket.send(new JSONObject().put("type", "partial").put("text", "半句").toString());
                    } catch (RuntimeException | AssertionError error) {
                        failure.compareAndSet(null, error);
                        socket.cancel();
                    }
                }

                @Override
                public void onClosing(WebSocket socket, int code, String reason) {
                    socket.close(code, reason);
                }
            };
        }

        private static void speak(WebSocket socket, String text) {
            if (text.equals("回退")) {
                socket.send("{\"type\":\"fallback\"}");
            } else {
                socket.send("{\"type\":\"start\",\"sample_rate\":24000,\"channels\":1}");
                socket.send(text.equals("畸形帧") ? ByteString.of((byte) 1) : ByteString.of(PCM));
                socket.send(text.equals("部分输出") ? "{\"type\":\"fallback\"}" : "{\"type\":\"end\"}");
            }
            socket.close(1000, null);
        }

        private static MockResponse json(JSONObject object) {
            return new MockResponse().setHeader("Content-Type", "application/json")
                    .setBody(object.toString());
        }

        @Override
        public void close() throws Exception {
            server.close();
            if (failure.get() != null) {
                throw new AssertionError("Unexpected media request", failure.get());
            }
        }
    }
}
