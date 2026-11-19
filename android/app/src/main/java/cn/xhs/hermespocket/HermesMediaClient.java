package cn.xhs.hermespocket;

import static cn.xhs.hermespocket.HermesText.t;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import okhttp3.MediaType;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.BufferedSink;
import okio.ByteString;

/** The authenticated Webapp audio relay. Device audio and RN are outside this class. */
final class HermesMediaClient {
    static final int INPUT_RATE = 16000;
    static final int MAX_UPLOAD = 25 * 1024 * 1024;
    static final int MAX_SPEECH_TEXT = 20000;
    private static final int MAX_TEXT_FRAME = 128 * 1024;
    private static final int MAX_GENERATED_AUDIO = 16 * 1024 * 1024;
    private static final int MAX_AUDIO_CHUNK = 4 * 1024 * 1024;
    private static final long MAX_OUTBOUND_QUEUE = 256 * 1024;
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final HermesClient client;

    HermesMediaClient(HermesClient client) {
        this.client = client;
    }

    static final class Operation {
        private final HermesClient.RequestRead read = new HermesClient.RequestRead();
        private Stream stream;
        private volatile boolean cancelled;

        synchronized void cancel() {
            cancelled = true;
            read.cancel();
            if (stream != null) {
                stream.cancel();
                stream = null;
            }
        }

        boolean isCancelled() {
            return cancelled;
        }

        void checkActive() throws InterruptedIOException {
            if (cancelled || Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException(t("native.audio_operation_canceled"));
            }
        }

        private synchronized void attach(Stream stream) throws InterruptedIOException {
            checkActive();
            this.stream = stream;
        }
    }

    static final class Status {
        final Boolean sttStreaming;
        final String sttMode;
        final String ttsMode;
        final String liveMode;
        final Boolean gptLiveAvailable;

        Status(JSONObject config, JSONObject live) {
            JSONObject stt = config.optJSONObject("stt");
            sttStreaming = bool(stt, "streaming");
            sttMode = mode(stt, "relay", "direct");
            ttsMode = mode(config.optJSONObject("tts"), "relay", "direct");
            liveMode = mode(live, "chained", "gpt-live");
            gptLiveAvailable = bool(live, "available");
        }

        private static Boolean bool(JSONObject object, String key) {
            Object value = object == null ? null : object.opt(key);
            return value instanceof Boolean ? (Boolean) value : null;
        }

        private static String mode(JSONObject object, String first, String second) {
            String mode = object == null ? "" : object.optString("mode");
            return first.equals(mode) || second.equals(mode) ? mode : "unknown";
        }
    }

    Status status(Operation operation) throws IOException, JSONException {
        JSONObject config = request(HermesClient.AudioRoute.CONFIG, null, operation, MAX_TEXT_FRAME);
        JSONObject live;
        try {
            live = request(HermesClient.AudioRoute.STATUS, null, operation, MAX_TEXT_FRAME);
        } catch (HermesClient.HttpException error) {
            if (error.statusCode == 401 || error.statusCode == 403) {
                throw error;
            }
            live = new JSONObject();
        }
        return new Status(config, live);
    }

    interface AudioSource {
        InputStream open() throws IOException;
    }

    static boolean supportedMime(String mime) {
        return mime != null && (mime.matches("audio/[a-z0-9][a-z0-9+.-]{0,63}")
                || "video/webm".equals(mime));
    }

    String transcribe(String mime, AudioSource source, Operation operation)
            throws IOException, JSONException {
        String normalized = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        if (!supportedMime(normalized)) {
            throw new AudioFailure("native.choose_an_audio_file_supported_by_hermes");
        }
        RequestBody upload = new RequestBody() {
            @Override
            public MediaType contentType() {
                return JSON;
            }

            @Override
            public void writeTo(BufferedSink sink) throws IOException {
                try (InputStream input = source.open()) {
                    if (input == null) {
                        throw new AudioFailure("native.could_not_open_the_selected_audio_file");
                    }
                    sink.writeUtf8("{\"data_url\":\"data:" + normalized + ";base64,");
                    OutputStream output = new FilterOutputStream(sink.outputStream()) {
                        @Override
                        public void write(byte[] bytes, int offset, int length) throws IOException {
                            out.write(bytes, offset, length);
                        }

                        @Override
                        public void close() {
                            // The JSON suffix still belongs to the HTTP request body.
                        }
                    };
                    OutputStream encoded = Base64.getEncoder().wrap(output);
                    byte[] buffer = new byte[8192];
                    int size = 0;
                    int length;
                    while ((length = input.read(buffer)) != -1) {
                        operation.checkActive();
                        size += length;
                        if (size > MAX_UPLOAD) {
                            throw new AudioFailure("native.the_audio_file_is_larger_than_25_mb");
                        }
                        encoded.write(buffer, 0, length);
                    }
                    if (size == 0) {
                        throw new AudioFailure("native.the_selected_audio_file_is_empty");
                    }
                    encoded.close();
                    sink.writeUtf8("\",\"mime_type\":\"" + normalized + "\"}");
                }
            }
        };
        return text(request(HermesClient.AudioRoute.TRANSCRIBE, upload, operation, MAX_TEXT_FRAME),
                "transcript");
    }

    void synthesize(String speech, Operation operation, OutputStream output)
            throws IOException, JSONException {
        RequestBody prompt = RequestBody.create(new JSONObject().put("text", speech).toString(), JSON);
        JSONObject reply = request(HermesClient.AudioRoute.SPEAK, prompt, operation,
                (MAX_GENERATED_AUDIO + 2L) / 3 * 4 + MAX_TEXT_FRAME);
        String data = reply.getString("data_url");
        int header = data.indexOf(',');
        if (header < 0 || header > 64 || !data.substring(0, header)
                .matches("data:audio/(?:mpeg|ogg|wav|flac);base64")) {
            throw new AudioFailure("native.hermes_returned_an_unsupported_read_aloud_format");
        }
        byte[] audio;
        try {
            audio = Base64.getDecoder().decode(data.substring(header + 1));
        } catch (IllegalArgumentException error) {
            throw new AudioFailure("native.hermes_returned_invalid_read_aloud_audio");
        }
        if (audio.length == 0 || audio.length > MAX_GENERATED_AUDIO) {
            throw new AudioFailure("native.hermes_returned_an_invalid_read_aloud_audio_size");
        }
        operation.checkActive();
        output.write(audio);
    }

    private JSONObject request(HermesClient.AudioRoute route, RequestBody body, Operation operation,
                               long limit) throws IOException, JSONException {
        operation.checkActive();
        try (Response response = client.executeAudioRequest(route, body, operation.read)) {
            ResponseBody value = response.body();
            MediaType type = value == null ? null : value.contentType();
            if (type == null || !"application".equals(type.type()) || !"json".equals(type.subtype())
                    || value.source().request(limit + 1)) {
                throw new AudioFailure("native.hermes_returned_an_invalid_audio_response");
            }
            JSONObject result = new JSONObject(value.source().readUtf8());
            if (!result.optBoolean("ok", false)) {
                throw new AudioFailure("native.hermes_could_not_complete_the_audio_request");
            }
            operation.checkActive();
            return result;
        }
    }

    Transcription beginTranscription(Operation operation, Consumer<String> partial)
            throws IOException, JSONException {
        Transcription stream = new Transcription(partial);
        open(HermesClient.AudioSocket.TRANSCRIBE, operation, stream);
        stream.send(new JSONObject().put("sample_rate", INPUT_RATE));
        return stream;
    }

    Speech beginSpeech(String text, Operation operation) throws IOException, JSONException {
        Speech stream = new Speech();
        open(HermesClient.AudioSocket.SPEAK, operation, stream);
        stream.send(new JSONObject().put("text", text).put("done", true));
        return stream;
    }

    private void open(HermesClient.AudioSocket target, Operation operation, Stream stream)
            throws IOException, JSONException {
        operation.checkActive();
        String ticket = client.mintAudioTicket(target, operation.read);
        operation.attach(stream);
        stream.bind(client.openAudioSocket(target, ticket, stream));
        waitFor(stream.opened, 15, "native.timed_out_connecting_to_the_hermes_audio_service");
        operation.checkActive();
        stream.checkHealthy();
    }

    private static void waitFor(CountDownLatch latch, long seconds, String messageKey)
            throws IOException {
        try {
            if (!latch.await(seconds, TimeUnit.SECONDS)) {
                throw new AudioFailure(messageKey);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException(t("native.audio_operation_canceled"));
        }
    }

    static final class TransportError extends IOException {
        final int status;

        TransportError(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    static final class AudioFailure extends HermesText.Failure {
        AudioFailure(String key, Object... values) {
            super(key, values);
        }
    }

    private abstract static class Stream extends WebSocketListener implements AutoCloseable {
        private final CountDownLatch opened = new CountDownLatch(1);
        private final CountDownLatch settled = new CountDownLatch(1);
        private volatile WebSocket socket;
        private volatile IOException failure;
        private volatile boolean cancelled;

        synchronized void bind(WebSocket openedSocket) {
            socket = openedSocket;
            if (cancelled) {
                openedSocket.cancel();
            }
        }

        @Override
        public void onOpen(WebSocket socket, Response response) {
            if (!"hermes-gateway-v1".equals(response.header("Sec-WebSocket-Protocol"))) {
                fail(new AudioFailure("native.hermes_did_not_accept_the_audio_connection_protocol"));
            }
            opened.countDown();
        }

        @Override
        public final void onMessage(WebSocket socket, String content) {
            if (settled()) {
                return;
            }
            try {
                if (content.length() > MAX_TEXT_FRAME) {
                    throw invalidFrame();
                }
                accept(new JSONObject(content));
            } catch (IOException | JSONException error) {
                fail(invalidFrame());
            }
        }

        abstract void accept(JSONObject frame) throws IOException, JSONException;

        @Override
        public void onMessage(WebSocket socket, ByteString content) {
            fail(invalidFrame());
        }

        @Override
        public void onClosing(WebSocket socket, int code, String reason) {
            if (!settled()) {
                fail(new TransportError(code, t("native.the_hermes_audio_connection_closed_early")));
            }
            socket.close(1000, null);
        }

        @Override
        public void onFailure(WebSocket socket, Throwable error, Response response) {
            fail(new TransportError(response == null ? 0 : response.code(), t("native.could_not_connect_to_the_hermes_audio_service")));
        }

        synchronized void fail(IOException error) {
            if (!settled()) {
                failure = error;
                settle();
                WebSocket current = socket;
                if (current != null) {
                    current.cancel();
                }
            }
        }

        final void settle() {
            settled.countDown();
            opened.countDown();
        }

        final boolean settled() {
            return settled.getCount() == 0;
        }

        final void awaitResult() throws IOException {
            waitFor(settled, 60, "native.timed_out_waiting_for_the_hermes_transcription");
            checkHealthy();
        }

        final void checkHealthy() throws IOException {
            IOException error = failure;
            if (error != null) {
                throw error;
            }
        }

        final void send(JSONObject frame) throws IOException {
            checkHealthy();
            if (settled() || !socket.send(frame.toString())) {
                throw new AudioFailure("native.could_not_send_the_hermes_audio_request");
            }
        }

        final void sendAudio(ByteString audio) throws IOException {
            checkHealthy();
            if (settled() || socket.queueSize() > MAX_OUTBOUND_QUEUE || !socket.send(audio)) {
                throw new AudioFailure("native.audio_upload_could_not_keep_up_with_the_microphone_check");
            }
        }

        synchronized void cancel() {
            cancelled = true;
            fail(new InterruptedIOException(t("native.audio_operation_canceled")));
            if (socket != null) {
                socket.cancel();
            }
        }

        @Override
        public synchronized void close() {
            if (socket != null && settled()) {
                socket.close(1000, null);
            } else {
                cancel();
            }
        }
    }

    static final class Transcription extends Stream {
        private final Consumer<String> partial;
        private volatile boolean ending;
        private String transcript;

        Transcription(Consumer<String> partial) {
            this.partial = partial;
        }

        void push(byte[] audio, int count) throws IOException {
            if (ending || count <= 0 || (count & 1) != 0 || count > audio.length) {
                throw invalidFrame();
            }
            sendAudio(ByteString.of(audio, 0, count));
        }

        String finish() throws IOException, JSONException {
            ending = true;
            send(new JSONObject().put("eos", true));
            awaitResult();
            return transcript;
        }

        @Override
        void accept(JSONObject frame) throws IOException, JSONException {
            String kind = frame.getString("type");
            switch (kind) {
                case "partial":
                    partial.accept(text(frame, "text"));
                    break;
                case "final":
                    if (!ending) {
                        throw invalidFrame();
                    }
                    transcript = text(frame, "transcript");
                    settle();
                    break;
                case "error":
                    boolean unavailable = "no live STT for the configured provider"
                            .equals(frame.optString("message"));
                    fail(new AudioFailure(unavailable ? "native.this_hermes_transcription_provider_does_not_support_live_recording_you"
                            : "native.hermes_could_not_finish_live_transcription"));
                    break;
                default:
                    throw invalidFrame();
            }
        }
    }

    static final class SpeechFrame {
        final int sampleRate;
        final ByteString audio;
        final boolean end;
        final boolean fallback;

        private SpeechFrame(int rate, ByteString audio, boolean end, boolean fallback) {
            sampleRate = rate;
            this.audio = audio;
            this.end = end;
            this.fallback = fallback;
        }
    }

    static final class Speech extends Stream {
        private final LinkedBlockingQueue<SpeechFrame> queue = new LinkedBlockingQueue<>(256);
        private final AtomicInteger buffered = new AtomicInteger();
        private int sampleRate;
        private long total;

        SpeechFrame next(Operation operation) throws IOException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (System.nanoTime() < deadline) {
                operation.checkActive();
                checkHealthy();
                try {
                    SpeechFrame frame = queue.poll(100, TimeUnit.MILLISECONDS);
                    if (frame != null) {
                        if (frame.audio != null) {
                            buffered.addAndGet(-frame.audio.size());
                        }
                        return frame;
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException(t("native.read_aloud_canceled"));
                }
            }
            throw new AudioFailure("native.timed_out_waiting_for_hermes_read_aloud_audio");
        }

        @Override
        void accept(JSONObject frame) throws IOException, JSONException {
            String kind = frame.getString("type");
            switch (kind) {
                case "start":
                    long rate = number(frame, "sample_rate");
                    if (sampleRate != 0 || number(frame, "channels") != 1 || rate < 8000 || rate > 96000) {
                        throw invalidFrame();
                    }
                    sampleRate = (int) rate;
                    enqueue(new SpeechFrame(sampleRate, null, false, false));
                    break;
                case "end":
                case "fallback":
                    boolean fallback = "fallback".equals(kind);
                    if (fallback ? total != 0 : sampleRate == 0) {
                        throw invalidFrame();
                    }
                    enqueue(new SpeechFrame(0, null, true, fallback));
                    settle();
                    break;
                default:
                    throw invalidFrame();
            }
        }

        @Override
        public void onMessage(WebSocket socket, ByteString content) {
            int size = content.size();
            if (settled()) {
                return;
            }
            if (sampleRate == 0 || size == 0 || (size & 1) != 0 || size > MAX_AUDIO_CHUNK
                    || (total += size) > MAX_GENERATED_AUDIO
                    || buffered.addAndGet(size) > MAX_GENERATED_AUDIO) {
                fail(invalidFrame());
                return;
            }
            try {
                enqueue(new SpeechFrame(0, content, false, false));
            } catch (IOException error) {
                fail(error);
            }
        }

        private void enqueue(SpeechFrame frame) throws IOException {
            if (!queue.offer(frame)) {
                throw new AudioFailure("native.this_device_could_not_play_the_hermes_audio_in_time");
            }
        }
    }

    private static String text(JSONObject frame, String key) throws JSONException {
        Object value = frame.get(key);
        if (!(value instanceof String) || ((String) value).length() > MAX_TEXT_FRAME) {
            throw new JSONException("Invalid audio text result");
        }
        return (String) value;
    }

    private static long number(JSONObject frame, String key) throws JSONException {
        Object value = frame.get(key);
        if (!(value instanceof Integer || value instanceof Long)) {
            throw new JSONException("Invalid audio numeric field");
        }
        return ((Number) value).longValue();
    }

    private static IOException invalidFrame() {
        return new AudioFailure("native.hermes_returned_invalid_audio_data");
    }
}
