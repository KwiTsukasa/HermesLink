package cn.xhs.hermespocket;

import static cn.xhs.hermespocket.HermesText.t;

import android.os.SystemClock;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;

/** A short-lived RFC 8252 redirect receiver bound only to IPv4 loopback. */
final class LoopbackCallback implements AutoCloseable {
    private static final String HOST = "127.0.0.1";
    private static final String PATH = "/hermes-pocket/callback";
    private static final long TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(10);
    private static final int REQUEST_TIMEOUT_MILLIS = 3000;
    private static final int MAX_HEADER_BYTES = 8192;

    private final ServerSocket server;
    private final String authority;
    private final String state;
    private volatile Socket currentSocket;

    LoopbackCallback(String state) throws IOException {
        server = new ServerSocket();
        try {
            server.bind(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 0), 4);
        } catch (IOException error) {
            close();
            throw new HermesText.Failure(error, "native.could_not_start_the_local_browser_callback_try_again");
        }
        authority = HOST + ":" + server.getLocalPort();
        this.state = state;
    }

    String redirectUri() {
        return "http://" + authority + PATH;
    }

    String awaitCode() throws IOException {
        long deadline = SystemClock.elapsedRealtime() + TIMEOUT_MILLIS;
        while (!server.isClosed() && SystemClock.elapsedRealtime() < deadline) {
            try {
                server.setSoTimeout((int) Math.max(1, deadline - SystemClock.elapsedRealtime()));
                try (Socket socket = server.accept()) {
                    currentSocket = socket;
                    if (server.isClosed()) {
                        throw new HermesText.Failure("native.sign_in_canceled");
                    }
                    socket.setSoTimeout(REQUEST_TIMEOUT_MILLIS);
                    String code = readCode(socket);
                    writeReply(socket, code != null);
                    if (code != null) {
                        return code;
                    }
                } finally {
                    currentSocket = null;
                }
            } catch (SocketTimeoutException timeout) {
                // A stalled request must release the loopback listener for a real callback.
            } catch (IOException socketError) {
                if (server.isClosed()) {
                    throw new HermesText.Failure(socketError, "native.sign_in_canceled");
                }
            }
        }
        throw new HermesText.Failure("native.sign_in_timed_out_return_to_the_app_and_sign");
    }

    private String readCode(Socket socket) throws IOException {
        BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        long deadline = SystemClock.elapsedRealtime() + REQUEST_TIMEOUT_MILLIS;
        int last = 0;
        while (bytes.size() < MAX_HEADER_BYTES && SystemClock.elapsedRealtime() < deadline) {
            int next = input.read();
            if (next == -1) {
                return null;
            }
            bytes.write(next);
            last = (last << 8) | next;
            if (last == 0x0d0a0d0a) {
                return codeFromHeaders(bytes.toString(StandardCharsets.US_ASCII.name()));
            }
        }
        return null;
    }

    private String codeFromHeaders(String headers) {
        String[] lines = headers.split("\r\n");
        String[] request = lines[0].split(" ");
        if (request.length != 3 || !"GET".equals(request[0]) || !request[1].startsWith(PATH + "?")
                || !("HTTP/1.1".equals(request[2]) || "HTTP/1.0".equals(request[2]))) {
            return null;
        }
        String host = null;
        for (int index = 1; index < lines.length; index++) {
            String line = lines[index];
            if (line.regionMatches(true, 0, "Host:", 0, 5)) {
                if (host != null) {
                    return null;
                }
                host = line.substring(5).trim();
            }
        }
        if (!authority.equals(host)) {
            return null;
        }
        HttpUrl callback = HttpUrl.parse("http://" + host + request[1]);
        if (callback == null || !PATH.equals(callback.encodedPath()) || callback.fragment() != null) {
            return null;
        }
        List<String> states = callback.queryParameterValues("state");
        List<String> codes = callback.queryParameterValues("code");
        if (states.size() != 1 || states.get(0) == null || codes.size() != 1 || codes.get(0) == null
                || !MessageDigest.isEqual(state.getBytes(StandardCharsets.US_ASCII),
                        states.get(0).getBytes(StandardCharsets.UTF_8))) {
            return null;
        }
        String code = codes.get(0);
        return code.matches("[A-Za-z0-9_-]{16,256}") ? code : null;
    }

    private static void writeReply(Socket socket, boolean accepted) {
        String language = HermesText.languageTag().replaceAll("[^A-Za-z0-9-]", "");
        String page = accepted
                ? t("native.login_callback_complete_html", language)
                : t("native.login_callback_invalid_html", language);
        byte[] payload = page.getBytes(StandardCharsets.UTF_8);
        String headers = (accepted ? "HTTP/1.1 200 OK" : "HTTP/1.1 400 Bad Request")
                + "\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + payload.length
                + "\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer"
                + "\r\nContent-Security-Policy: default-src 'none'; frame-ancestors 'none'"
                + "\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n";
        try {
            OutputStream output = socket.getOutputStream();
            output.write(headers.getBytes(StandardCharsets.US_ASCII));
            output.write(payload);
            output.flush();
        } catch (IOException disconnected) {
            // The browser can close its connection after the app has received a valid code.
        }
    }

    @Override
    public void close() {
        try {
            server.close();
        } catch (IOException ignored) {
        }
        Socket socket = currentSocket;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }
}
