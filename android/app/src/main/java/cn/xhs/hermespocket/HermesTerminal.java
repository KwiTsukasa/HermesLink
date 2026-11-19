package cn.xhs.hermespocket;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/** One foreground viewer and, when detached, its server-owned shell handle. */
final class HermesTerminal implements AutoCloseable {
    static final int MAX_COLUMNS = 2000;
    static final int MAX_ROWS = 1000;
    static final int MAX_INPUT_BYTES = 64 * 1024;
    static final int MAX_OUTPUT_BYTES = 1024 * 1024;
    private static final int MAX_QUEUED_BYTES = 128 * 1024;
    private static final int MAX_METADATA_LENGTH = 8192;
    private static final int HANDSHAKE_SECONDS = 10;
    private static final String META_PREFIX = "\0HERMES_TERMINAL_META:";
    private static final Pattern HANDLE = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final ScheduledThreadPoolExecutor DEADLINES = new ScheduledThreadPoolExecutor(
            1, action -> daemon(action, "Hermes terminal deadline"));

    static {
        DEADLINES.setRemoveOnCancelPolicy(true);
    }

    enum End {
        DETACHED, SHELL_CLOSED, EXITED, EXPIRED, SUPERSEDED, DENIED, SIGNED_OUT, CAPACITY, DISCONNECTED, ERROR
    }

    private enum Completion { CANCEL, PEER_CLOSED, HANDSHAKE_TIMEOUT }

    /** Callbacks arrive on the socket or ticket thread, or on the caller's thread for local actions. */
    interface Listener {
        void onReady(boolean reconnected, boolean truncated);
        void onOutput(byte[] data, boolean replay);
        void onClosed(End end);
    }

    private HermesClient client;
    private BooleanSupplier scopeCurrent;
    private Dial active;
    private String terminalId;
    private String profile;
    private End lastEnd;

    HermesTerminal(HermesClient client, BooleanSupplier scopeCurrent) {
        this.client = Objects.requireNonNull(client);
        this.scopeCurrent = Objects.requireNonNull(scopeCurrent);
    }

    static boolean validHandle(String value) {
        return value != null && HANDLE.matcher(value).matches();
    }

    private static boolean validDimensions(int columns, int rows) {
        return columns >= 1 && columns <= MAX_COLUMNS && rows >= 1 && rows <= MAX_ROWS;
    }

    synchronized boolean start(int columns, int rows, String requestedProfile, String requestedCwd, Listener listener) {
        String selectedProfile = requestedProfile == null ? "" : requestedProfile;
        String cwd = requestedCwd == null ? "" : requestedCwd;
        if (client == null || active != null || terminalId != null || !validDimensions(columns, rows)
                || !validParameter(selectedProfile, 128) || !validParameter(cwd, 4096)) {
            return false;
        }
        lastEnd = null;
        profile = selectedProfile.trim();
        launch(new Dial(null, false, columns, rows, cwd.trim(), listener));
        return true;
    }

    private static boolean validParameter(String value, int limit) {
        return value.length() <= limit && value.chars().noneMatch(Character::isISOControl);
    }

    synchronized boolean canReattach() {
        return client != null && active == null && terminalId != null;
    }

    synchronized boolean reattach(int columns, int rows, Listener listener) {
        if (!canReattach() || !validDimensions(columns, rows)) {
            return false;
        }
        launch(new Dial(terminalId, false, columns, rows, "", listener));
        return true;
    }

    synchronized boolean sendInput(byte[] data) {
        return active != null && active.ready && data != null && data.length > 0 && data.length <= MAX_INPUT_BYTES
                && scopeCurrent.getAsBoolean()
                && active.socket.queueSize() + data.length <= MAX_QUEUED_BYTES
                && active.socket.send(ByteString.of(data));
    }

    synchronized boolean resize(int columns, int rows) {
        return active != null && active.ready && validDimensions(columns, rows)
                && scopeCurrent.getAsBoolean()
                && sendResize(active.socket, columns, rows);
    }

    private static boolean sendResize(WebSocket socket, int columns, int rows) {
        String frame = "\u001b[RESIZE:" + columns + ";" + rows + "]";
        return socket.queueSize() + frame.length() <= MAX_QUEUED_BYTES && socket.send(frame);
    }

    boolean detach() {
        Dial dial;
        synchronized (this) {
            if (active == null || active.closing || active.closeWhenReady != null || active.detachWhenReady) {
                return false;
            }
            dial = active;
            if (terminalId == null && dial.socket != null) {
                dial.detachWhenReady = true;
                return true;
            }
            active = null;
            dial.stop();
        }
        dial.listener.onClosed(End.DETACHED);
        return true;
    }

    boolean closeShell(Listener listener) {
        Objects.requireNonNull(listener);
        End settled;
        synchronized (this) {
            if (client == null || (active == null && terminalId == null && lastEnd == null)
                    || (active != null && (active.closing || active.closeWhenReady != null))) {
                return false;
            }
            if (terminalId == null && active != null && active.socket != null) {
                // The server may already be starting a shell. Learn its handle to close that shell.
                active.closeWhenReady = listener;
                return true;
            }
            if (active != null) {
                active.stop();
                active = null;
            }
            if (terminalId != null) {
                launch(new Dial(terminalId, true, 0, 0, "", listener));
                return true;
            }
            settled = lastEnd;
        }
        // A peer exit can beat the close request. A fresh cancelled creation has no remote shell.
        listener.onClosed(settled == null ? End.SHELL_CLOSED : settled);
        return true;
    }

    /** Releases the local viewer and login scope immediately, including an in-flight ticket request. */
    @Override
    public synchronized void close() {
        client = null;
        scopeCurrent = null;
        terminalId = null;
        profile = null;
        lastEnd = null;
        if (active != null) {
            active.stop();
            active = null;
        }
    }

    private void launch(Dial dial) {
        active = dial;
        HermesClient server = client;
        BooleanSupplier current = scopeCurrent;
        daemon(() -> connect(server, current, dial), "Hermes terminal connection").start();
    }

    private void connect(HermesClient server, BooleanSupplier current, Dial dial) {
        try {
            if (!current.getAsBoolean()) {
                finish(dial, End.SIGNED_OUT, Completion.CANCEL);
                return;
            }
            String ticket = server.mintHostTerminalTicket(dial.ticketRead);
            if (!current.getAsBoolean()) {
                finish(dial, End.SIGNED_OUT, Completion.CANCEL);
                return;
            }
            synchronized (this) {
                if (active != dial) {
                    return;
                }
                dial.socket = server.openHostTerminalSocket(ticket, dial.attach, dial.closing, dial.columns,
                        dial.rows, profile, dial.cwd, dial);
                dial.deadline = DEADLINES.schedule(() -> finish(dial, End.ERROR, Completion.HANDSHAKE_TIMEOUT),
                        HANDSHAKE_SECONDS, TimeUnit.SECONDS);
            }
        } catch (HermesClient.HttpException error) {
            finish(dial, httpEnd(error.statusCode), Completion.CANCEL);
        } catch (IOException | JSONException | IllegalArgumentException error) {
            finish(dial, current.getAsBoolean() ? End.ERROR : End.SIGNED_OUT, Completion.CANCEL);
        }
    }

    private void finish(Dial dial, End end, Completion completion) {
        Listener listener;
        synchronized (this) {
            if (active != dial || (completion == Completion.HANDSHAKE_TIMEOUT && dial.deadline == null)) {
                return;
            }
            active = null;
            lastEnd = end;
            if (completion == Completion.PEER_CLOSED) {
                dial.cancelDeadline();
                dial.socket.close(1000, null);
            } else {
                dial.stop();
            }
            switch (end) {
                case SHELL_CLOSED, EXITED, EXPIRED, SIGNED_OUT, DENIED, SUPERSEDED -> terminalId = null;
                default -> { }
            }
            listener = dial.closeWhenReady == null ? dial.listener : dial.closeWhenReady;
        }
        listener.onClosed(end);
    }

    private void metadata(Dial dial, String frame) throws JSONException {
        if (frame.length() > MAX_METADATA_LENGTH) {
            finish(dial, End.ERROR, Completion.CANCEL);
            return;
        }
        JSONObject data = new JSONObject(frame.substring(META_PREFIX.length()));
        String id = data.getString("terminalId");
        if (!validHandle(id) || (dial.attach != null && !dial.attach.equals(id))) {
            finish(dial, End.ERROR, Completion.CANCEL);
            return;
        }
        if (dial.closing) {
            if (Boolean.TRUE.equals(data.opt("closed"))) {
                dial.closeAcknowledged = true;
            } else {
                finish(dial, End.ERROR, Completion.CANCEL);
            }
            return;
        }

        boolean reconnected = dial.attach != null;
        Object truncated = data.opt("truncated");
        if (!Boolean.valueOf(reconnected).equals(data.opt("reconnected")) || !(truncated instanceof Boolean)
                || !(data.opt("shell") instanceof String shell) || shell.trim().isEmpty()) {
            finish(dial, End.ERROR, Completion.CANCEL);
            return;
        }
        boolean detaching;
        synchronized (this) {
            if (active != dial || dial.ready) {
                return;
            }
            terminalId = id;
            if (dial.closeWhenReady != null) {
                dial.stop();
                launch(new Dial(id, true, 0, 0, "", dial.closeWhenReady));
                return;
            }
            detaching = dial.detachWhenReady;
            if (detaching) {
                active = null;
                dial.stop();
            } else {
                dial.cancelDeadline();
                dial.ready = true;
                dial.awaitingReplay = reconnected;
            }
            if (reconnected && !detaching) {
                if ((Boolean) truncated) {
                    // Match the Webapp's redraw for a partial replay that may omit viewport setup.
                    sendResize(dial.socket, dial.columns, dial.rows > 1 ? dial.rows - 1 : dial.rows + 1);
                }
                sendResize(dial.socket, dial.columns, dial.rows);
            }
        }
        if (detaching) {
            dial.listener.onClosed(End.DETACHED);
        } else {
            dial.listener.onReady(reconnected, (Boolean) truncated);
        }
    }

    private synchronized boolean receives(Dial dial) {
        return active == dial && dial.ready;
    }

    private static End httpEnd(int code) {
        return switch (code) {
            case 401 -> End.SIGNED_OUT;
            case 403, 404 -> End.DENIED;
            case 429 -> End.CAPACITY;
            default -> End.ERROR;
        };
    }

    private static End socketEnd(int code, String reason) {
        return switch (code) {
            case 4401 -> End.SIGNED_OUT;
            case 4403, 4404, 4408 -> End.DENIED;
            case 4409 -> End.SUPERSEDED;
            case 4410 -> "Process exited".equals(reason) ? End.EXITED : End.EXPIRED;
            case 1013 -> End.CAPACITY;
            case 1011 -> End.ERROR;
            default -> End.DISCONNECTED;
        };
    }

    private static Thread daemon(Runnable action, String name) {
        Thread thread = new Thread(action, name);
        thread.setDaemon(true);
        return thread;
    }

    private final class Dial extends WebSocketListener {
        private final String attach;
        private final boolean closing;
        private final int columns;
        private final int rows;
        private final String cwd;
        private final Listener listener;
        private final HermesClient.RequestRead ticketRead = new HermesClient.RequestRead();
        private WebSocket socket;
        private ScheduledFuture<?> deadline;
        private Listener closeWhenReady;
        private boolean detachWhenReady;
        private boolean ready;
        private boolean awaitingReplay;
        private boolean closeAcknowledged;

        private Dial(String attach, boolean closing, int columns, int rows, String cwd, Listener listener) {
            this.attach = attach;
            this.closing = closing;
            this.columns = columns;
            this.rows = rows;
            this.cwd = cwd;
            this.listener = Objects.requireNonNull(listener);
        }

        @Override
        public void onMessage(WebSocket socket, String frame) {
            if (frame.startsWith(META_PREFIX)) {
                try {
                    metadata(this, frame);
                } catch (JSONException error) {
                    finish(this, End.ERROR, Completion.CANCEL);
                }
            } else if (receives(this)) {
                if (frame.length() > MAX_OUTPUT_BYTES) {
                    finish(this, End.ERROR, Completion.CANCEL);
                } else {
                    output(ByteString.encodeUtf8(frame), false);
                }
            }
        }

        @Override
        public void onMessage(WebSocket socket, ByteString frame) {
            output(frame, true);
        }

        private void output(ByteString frame, boolean binary) {
            if (frame.size() > MAX_OUTPUT_BYTES) {
                finish(this, End.ERROR, Completion.CANCEL);
                return;
            }
            if (!receives(this) || (awaitingReplay && !binary)) {
                return;
            }
            // The Webapp sends one binary snapshot after reattach metadata, even for empty history.
            boolean replay = awaitingReplay;
            if (replay) {
                awaitingReplay = false;
            }
            if (replay || frame.size() > 0) {
                listener.onOutput(frame.toByteArray(), replay);
            }
        }

        @Override
        public void onClosing(WebSocket socket, int code, String reason) {
            End end = closing && code == 1000
                    ? (closeAcknowledged ? End.SHELL_CLOSED : End.ERROR) : socketEnd(code, reason);
            finish(this, end, Completion.PEER_CLOSED);
        }

        @Override
        public void onFailure(WebSocket socket, Throwable error, Response response) {
            // The request URL carries one-use material. Never forward exceptions or raw peer reasons.
            finish(this, response == null ? End.DISCONNECTED : httpEnd(response.code()), Completion.CANCEL);
        }

        private void cancelDeadline() {
            if (deadline != null) {
                deadline.cancel(false);
                deadline = null;
            }
        }

        private void stop() {
            cancelDeadline();
            ticketRead.cancel();
            if (socket != null) {
                socket.cancel();
            }
        }
    }
}
