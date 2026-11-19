package cn.xhs.hermespocket;

import static cn.xhs.hermespocket.HermesText.t;

import java.io.IOException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/** Owns the one-time ticket exchange and read-only framebuffer for one viewer. */
final class HermesDisplayViewer extends WebSocketListener implements AutoCloseable {
    private static final ScheduledThreadPoolExecutor TIMERS = new ScheduledThreadPoolExecutor(1, action -> {
        Thread thread = new Thread(action, "Hermes screen viewer");
        thread.setDaemon(true);
        return thread;
    });
    private static final int UPDATE_DELAY_MS = 125;
    private static final int FIRST_FRAME_SECONDS = 15;

    static {
        TIMERS.setRemoveOnCancelPolicy(true);
    }

    interface Observer {
        void state(String state);
        void resize(int width, int height);
        void row(int x, int y, int[] argb, int length);
        void frameReady();
    }

    private final HermesClient client;
    private final BooleanSupplier currentLogin;
    private final Observer observer;
    private final String profile;
    private final HermesClient.GatewayRead admission = new HermesClient.GatewayRead();
    private final RfbScreenDecoder renderer;
    private WebSocket socket;
    private ScheduledFuture<?> nextUpdate;
    private ScheduledFuture<?> firstFrame;
    private boolean started;
    private boolean closed;
    private boolean pixelsChanged;
    private boolean hasFrame;

    HermesDisplayViewer(HermesClient client, BooleanSupplier currentLogin, String profile, Observer observer) {
        if (profile == null || !profile.matches("[A-Za-z0-9_-]{0,64}")) {
            throw new HermesText.InvalidInput("native.the_bot_desktop_profile_is_invalid");
        }
        this.client = client;
        this.currentLogin = currentLogin;
        this.profile = profile;
        this.observer = observer;
        renderer = new RfbScreenDecoder(new RfbScreenDecoder.Output() {
            @Override
            public void send(ByteString message) throws IOException {
                if (!isCurrent() || socket == null || !socket.send(message)) {
                    throw new IOException("Hermes screen socket is closed");
                }
            }

            @Override
            public void resize(int width, int height) {
                observer.resize(width, height);
                pixelsChanged = false;
            }

            @Override
            public void row(int x, int y, int[] argb, int length) {
                observer.row(x, y, argb, length);
                pixelsChanged = true;
            }

            @Override
            public void frameComplete() {
                if (pixelsChanged) {
                    pixelsChanged = false;
                    if (!hasFrame) {
                        hasFrame = true;
                        if (firstFrame != null) {
                            firstFrame.cancel(false);
                        }
                        observer.state("viewing");
                    }
                    observer.frameReady();
                }
                if (nextUpdate == null || nextUpdate.isDone()) {
                    nextUpdate = TIMERS.schedule(HermesDisplayViewer.this::requestNext, UPDATE_DELAY_MS,
                            TimeUnit.MILLISECONDS);
                }
            }
        });
    }

    void connect() {
        synchronized (this) {
            if (!isCurrent() || started) {
                return;
            }
            started = true;
            observer.state("connecting");
        }
        try {
            String ticket = client.mintDisplayTicket(profile, admission);
            synchronized (this) {
                if (isCurrent()) {
                    socket = client.openDisplaySocket(ticket, this);
                }
            }
        } catch (Exception error) {
            fail(error instanceof HermesClient.RpcException ? "unavailable"
                    : error instanceof HermesClient.HttpException
                         && ((HermesClient.HttpException) error).statusCode == 401 ? "signed_out" : "failed");
        }
    }

    private boolean isCurrent() {
        return !closed && currentLogin.getAsBoolean();
    }

    private synchronized void requestNext() {
        nextUpdate = null;
        if (isCurrent()) {
            try {
                renderer.requestUpdate(true);
            } catch (IOException error) {
                fail("failed");
            }
        }
    }

    @Override
    public synchronized void onOpen(WebSocket viewSocket, Response response) {
        if (!isCurrent()) {
            viewSocket.cancel();
            return;
        }
        observer.state("waiting");
        firstFrame = TIMERS.schedule(() -> fail("timeout"), FIRST_FRAME_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    public synchronized void onMessage(WebSocket viewSocket, ByteString message) {
        if (!isCurrent()) {
            viewSocket.cancel();
            return;
        }
        try {
            renderer.receive(message);
        } catch (IOException | RuntimeException error) {
            fail("invalid_stream");
        }
    }

    @Override
    public void onMessage(WebSocket viewSocket, String message) {
        fail("invalid_stream");
    }

    @Override
    public void onClosing(WebSocket viewSocket, int code, String reason) {
        fail(code == 4001 || code == 4403 ? "unavailable"
                : code == 4401 ? "signed_out" : "disconnected");
    }

    @Override
    public void onFailure(WebSocket viewSocket, Throwable error, Response response) {
        fail(response != null && (response.code() == 401 || response.code() == 403)
                ? "unavailable" : "failed");
        if (response != null) {
            response.close();
        }
    }

    private synchronized void fail(String state) {
        if (!closed) {
            close();
            observer.state(state);
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        admission.cancel();
        if (firstFrame != null) {
            firstFrame.cancel(false);
            firstFrame = null;
        }
        if (nextUpdate != null) {
            nextUpdate.cancel(false);
            nextUpdate = null;
        }
        if (socket != null) {
            socket.cancel();
            socket = null;
        }
    }
}
