package cn.xhs.hermespocket;

import static cn.xhs.hermespocket.HermesText.t;

import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.LifecycleEventListener;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.common.LifecycleState;

import java.io.EOFException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import okio.Buffer;

/** Owns host shells within the current login. One shell is attached to the UI at a time. */
public final class HermesTerminalModule extends ReactContextBaseJavaModule implements LifecycleEventListener {
    private static final String EVENT = "HermesTerminalEvent";
    private static final int OUTPUT_BATCH_BYTES = 32 * 1024;
    private static final int OUTPUT_BACKLOG_BYTES = 2 * 1024 * 1024;
    private static final long OUTPUT_ACK_TIMEOUT_MS = 15_000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ReactApplicationContext context;
    private final ConnectionStore store;
    private final Runnable stopObservingLogin;
    // Only the main thread manages ownership and lifecycle. Network callbacks can enqueue output.
    private final Map<String, Session> tabs = new LinkedHashMap<>();
    private ConnectionStore.LoginScope login;
    private Session attached;
    private int nextTabNumber;
    private double nextOutputSequence;
    private volatile boolean invalidated;

    public HermesTerminalModule(ReactApplicationContext context) {
        super(context);
        this.context = context;
        store = new ConnectionStore(context);
        stopObservingLogin = store.observeLoginChanges(() -> main.post(this::currentLogin));
        context.addLifecycleEventListener(this);
    }

    @Override
    public String getName() {
        return "HermesTerminal";
    }

    @ReactMethod
    public void getTabs(Promise promise) {
        submit(promise, () -> {
            currentLogin();
            WritableArray list = Arguments.createArray();
            for (Session tab : tabs.values()) {
                WritableMap row = Arguments.createMap();
                row.putString("id", tab.id);
                row.putInt("number", tab.number);
                row.putString("profile", tab.profile);
                row.putString("state", tab.phase.value);
                list.pushMap(row);
            }
            promise.resolve(list);
        });
    }

    @ReactMethod
    public void open(String viewId, double cols, double rows, String profile, String cwd, Promise promise) {
        submit(promise, () -> {
            requireView(viewId);
            checkSize(cols, rows);
            String selectedProfile = optionValue(profile, 128);
            String workingDirectory = optionValue(cwd, 4096);
            ConnectionStore.LoginScope login = requireLogin();
            requireNoAttachment();
            HermesTerminal terminal = new HermesClient(login.url, store.authFor(login))
                    .newHostTerminal(() -> !invalidated && login.equals(store.activeLogin()));
            Session opening = new Session(++nextTabNumber, selectedProfile, terminal);
            tabs.put(opening.id, opening);
            attach(opening, viewId, false);
            if (!terminal.start((int) cols, (int) rows, selectedProfile, workingDirectory, listener(opening, false))) {
                dispose(opening);
                throw new BridgeFailure("TERMINAL_BUSY", t("native.the_terminal_did_not_connect_try_again"));
            }
            promise.resolve(opening.id);
        });
    }

    @ReactMethod
    public void reconnect(String viewId, String tabId, double cols, double rows, Promise promise) {
        submit(promise, () -> {
            requireView(viewId);
            checkSize(cols, rows);
            Session current = requireTab(tabId);
            if (current.phase != Phase.DETACHED) {
                throw new BridgeFailure("NOT_DETACHED", t("native.this_terminal_cannot_reconnect_yet"));
            }
            requireNoAttachment();
            attach(current, viewId, true);
            if (!current.terminal.reattach((int) cols, (int) rows, listener(current, false))) {
                settleDetached(current);
                throw new BridgeFailure("RECONNECT_FAILED", t("native.could_not_reconnect_to_this_terminal"));
            }
            promise.resolve(null);
        });
    }

    @ReactMethod
    public void write(String viewId, String tabId, String text, Promise promise) {
        submit(promise, () -> {
            Session current = requireConnected(viewId, tabId);
            if (text == null) {
                throw new BridgeFailure("INVALID_INPUT", t("native.invalid_terminal_input"));
            }
            if (text.length() > HermesTerminal.MAX_INPUT_BYTES) {
                throw inputTooLong();
            }
            byte[] input = text.getBytes(StandardCharsets.UTF_8);
            if (input.length > HermesTerminal.MAX_INPUT_BYTES) {
                throw inputTooLong();
            }
            if (input.length != 0 && !current.terminal.sendInput(input)) {
                throw new BridgeFailure("INPUT_REJECTED", t("native.the_input_was_not_sent_check_the_terminal_connection_and"));
            }
            promise.resolve(null);
        });
    }

    @ReactMethod
    public void resize(String viewId, String tabId, double cols, double rows, Promise promise) {
        submit(promise, () -> {
            checkSize(cols, rows);
            Session current = requireConnected(viewId, tabId);
            if (!current.terminal.resize((int) cols, (int) rows)) {
                throw new BridgeFailure("RESIZE_REJECTED", t("native.the_terminal_size_did_not_update_check_the_connection"));
            }
            promise.resolve(null);
        });
    }

    @ReactMethod
    public void detach(String viewId, String tabId, Promise promise) {
        submit(promise, () -> {
            requireLogin();
            Session current = attached;
            if (!owns(current, viewId, tabId)
                    || current.phase != Phase.DETACHING && !detach(current)) {
                throw notConnected();
            }
            promise.resolve(null);
        });
    }

    @ReactMethod
    public void terminate(String viewId, String tabId, Promise promise) {
        submit(promise, () -> {
            requireView(viewId);
            Session current = requireTab(tabId);
            if (current.phase != Phase.DETACHED && (current.phase != Phase.CONNECTED || !owns(current, viewId))) {
                throw new BridgeFailure("TERMINAL_BUSY", t("native.the_terminal_is_changing_connection_state_try_ending_it_again"));
            }
            current.viewId = viewId;
            closeShell(current, true);
            promise.resolve(null);
        });
    }

    @ReactMethod
    public void close(String viewId) {
        main.post(() -> leave(viewId));
    }

    @ReactMethod
    public void ackOutput(String viewId, double sequence) {
        main.post(() -> {
            currentLogin();
            Session current = attached;
            if (owns(current, viewId) && current.output != null && sequence > 0 && current.outputSequence == sequence) {
                clearAckTimeout(current);
                current.output.ack();
                pumpOutput(current);
            }
        });
    }

    private void attach(Session current, String viewId, boolean reconnecting) {
        attached = current;
        current.viewId = viewId;
        current.phase = Phase.CONNECTING;
        current.reconnecting = reconnecting;
    }

    private boolean detach(Session current) {
        if ((current.phase != Phase.CONNECTED && current.phase != Phase.CONNECTING)
                || !current.terminal.detach()) {
            return false;
        }
        current.phase = Phase.DETACHING;
        clearOutput(current);
        return true;
    }

    private void leave(String viewId) {
        currentLogin();
        for (Session current : new ArrayList<>(tabs.values())) {
            if (owns(current, viewId)) {
                leave(current);
            }
        }
    }

    private void leave(Session current) {
        current.viewId = null;
        if (current.phase == Phase.DRAINING) {
            finishConnection(current, current.pendingEnd);
        } else if (current.phase != Phase.CLOSING && current.phase != Phase.DETACHING
                && !(current.phase == Phase.CONNECTING && current.reconnecting && detach(current))) {
            closeShell(current, false);
        }
    }

    private void closeShell(Session current, boolean keepOnFailure) {
        current.phase = Phase.CLOSING;
        current.keepOnCloseFailure = keepOnFailure;
        clearOutput(current);
        if (!current.terminal.closeShell(listener(current, true))) {
            finishClose(current, HermesTerminal.End.ERROR);
        }
    }

    private HermesTerminal.Listener listener(Session current, boolean closing) {
        int generation = ++current.listenerGeneration;
        OutputQueue output = closing ? null : new OutputQueue(() -> main.post(() -> {
            if (isCurrent(current, generation)) {
                pumpOutput(current);
            }
        }));
        current.output = output;
        return new HermesTerminal.Listener() {
            @Override
            public void onReady(boolean reconnected, boolean truncated) {
                main.post(() -> {
                    if (isCurrent(current, generation) && current.phase == Phase.CONNECTING) {
                        current.phase = Phase.CONNECTED;
                        current.reconnecting = false;
                        WritableMap event = event("connected");
                        event.putBoolean("reconnected", reconnected);
                        event.putBoolean("truncated", truncated);
                        emit(current.id, current.viewId, event);
                        pumpOutput(current);
                    }
                });
            }

            @Override
            public void onOutput(byte[] data, boolean replay) {
                if (output != null) {
                    output.append(data, replay);
                }
            }

            @Override
            public void onClosed(HermesTerminal.End end) {
                main.post(() -> {
                    if (isCurrent(current, generation)) {
                        if (closing) {
                            finishClose(current, end);
                        } else if (current.output == null) {
                            finishConnection(current, end);
                        } else {
                            current.pendingEnd = end;
                            current.phase = Phase.DRAINING;
                            pumpOutput(current);
                        }
                    }
                });
            }
        };
    }

    private void pumpOutput(Session current) {
        OutputQueue output = current.output;
        if (output == null || current.phase != Phase.CONNECTED && current.phase != Phase.DRAINING) {
            return;
        }
        if (current.pendingEnd != null && context.getLifecycleState() != LifecycleState.RESUMED) {
            finishConnection(current, current.pendingEnd);
            return;
        }
        OutputBatch batch = output.take();
        if (output.overflowed()) {
            abortOutput(current, "OUTPUT_LIMIT", t("native.the_terminal_produced_more_output_than_the_view_can_handle"));
            return;
        }
        if (batch != null) {
            double sequence = ++nextOutputSequence;
            current.outputSequence = sequence;
            current.ackTimeout = () -> {
                currentLogin();
                if (attached == current && current.outputSequence == sequence) {
                    abortOutput(current, "OUTPUT_TIMEOUT", t("native.the_terminal_view_stopped_responding_the_connection_stopped"));
                }
            };
            main.postDelayed(current.ackTimeout, OUTPUT_ACK_TIMEOUT_MS);
            WritableMap event = event("output");
            event.putString("data", Base64.encodeToString(batch.data, Base64.NO_WRAP));
            event.putDouble("sequence", sequence);
            event.putBoolean("replay", batch.replay);
            event.putBoolean("replayEnd", batch.replayEnd);
            emit(current.id, current.viewId, event);
        } else if (current.pendingEnd != null && output.isEmpty()) {
            finishConnection(current, current.pendingEnd);
        }
    }

    private void abortOutput(Session current, String code, String message) {
        String owner = current.viewId;
        current.viewId = null;
        closeShell(current, false);
        error(current.id, owner, code, message, false);
    }

    private void finishConnection(Session current, HermesTerminal.End end) {
        String owner = current.viewId;
        boolean canReconnect = current.terminal.canReattach();
        if (canReconnect) {
            settleDetached(current);
        } else {
            dispose(current);
        }
        publishEnd(current.id, owner, end, canReconnect);
    }

    private void finishClose(Session current, HermesTerminal.End end) {
        String owner = current.viewId;
        boolean canReconnect = current.keepOnCloseFailure && current.terminal.canReattach();
        if (canReconnect) {
            settleDetached(current);
        } else {
            dispose(current);
        }
        publishEnd(current.id, owner, end, canReconnect);
    }

    private void publishEnd(String tabId, String owner, HermesTerminal.End end, boolean canReconnect) {
        switch (end) {
            case DETACHED:
                if (canReconnect) {
                    emit(tabId, owner, event("detached"));
                } else {
                    closed(tabId, owner, "left");
                }
                break;
            case SHELL_CLOSED:
                closed(tabId, owner, "terminated");
                break;
            case EXITED:
                closed(tabId, owner, "exited");
                break;
            case EXPIRED:
                closed(tabId, owner, "expired");
                break;
            case DENIED:
                error(tabId, owner, "DENIED", t("native.hermes_rejected_the_terminal_request_check_the_host_terminal_setting"), canReconnect);
                break;
            case SIGNED_OUT:
                error(tabId, owner, "SIGN_IN", t("native.your_hermes_session_expired_sign_in_again"), false);
                break;
            case CAPACITY:
                error(tabId, owner, "CAPACITY", t("native.the_hermes_host_terminal_reached_its_session_limit"), canReconnect);
                break;
            case SUPERSEDED:
                error(tabId, owner, "SUPERSEDED", t("native.this_terminal_connection_was_replaced"), canReconnect);
                break;
            case DISCONNECTED:
                error(tabId, owner, "DISCONNECTED", t("native.the_host_terminal_disconnected"), canReconnect);
                break;
            default:
                error(tabId, owner, "TERMINAL_ERROR", t("native.the_terminal_action_failed_check_the_hermes_connection_and_host"), canReconnect);
                break;
        }
    }

    private void dispose(Session current) {
        clearOutput(current);
        tabs.remove(current.id);
        clearAttachment(current);
        current.terminal.close();
    }

    private void settleDetached(Session current) {
        ++current.listenerGeneration;
        clearOutput(current);
        current.phase = Phase.DETACHED;
        current.viewId = null;
        current.reconnecting = false;
        clearAttachment(current);
    }

    private void clearAttachment(Session current) {
        if (attached == current) {
            attached = null;
        }
    }

    private void clearOutput(Session current) {
        clearAckTimeout(current);
        current.pendingEnd = null;
        if (current.output != null) {
            current.output.stop();
            current.output = null;
        }
    }

    private void clearAckTimeout(Session current) {
        if (current.ackTimeout != null) {
            main.removeCallbacks(current.ackTimeout);
            current.ackTimeout = null;
        }
        current.outputSequence = 0;
    }

    private ConnectionStore.LoginScope currentLogin() {
        ConnectionStore.LoginScope actual = invalidated ? null : store.activeLogin();
        if (!Objects.equals(actual, login)) {
            login = actual;
            nextTabNumber = 0;
            for (Session current : new ArrayList<>(tabs.values())) {
                dispose(current);
                error(current.id, current.viewId, "STALE_LOGIN", t("native.the_hermes_login_changed_reopen_the_host_terminal"), false);
            }
        }
        return actual;
    }

    private boolean isCurrent(Session current, int generation) {
        currentLogin();
        return tabs.get(current.id) == current && current.listenerGeneration == generation;
    }

    private ConnectionStore.LoginScope requireLogin() throws BridgeFailure {
        ConnectionStore.LoginScope login = currentLogin();
        if (login == null) {
            throw new BridgeFailure("SIGN_IN", t("native.sign_in_to_hermes_from_connection_settings_first"));
        }
        return login;
    }

    private Session requireTab(String tabId) throws BridgeFailure {
        requireLogin();
        Session current = tabs.get(tabId);
        if (current == null) {
            throw new BridgeFailure("TERMINAL_GONE", t("native.this_host_terminal_ended_or_is_outside_the_current_login"));
        }
        return current;
    }

    private void requireNoAttachment() throws BridgeFailure {
        if (attached != null) {
            throw new BridgeFailure("TERMINAL_BUSY", t("native.disconnect_or_end_the_current_terminal_first"));
        }
    }

    private Session requireConnected(String viewId, String tabId) throws BridgeFailure {
        requireView(viewId);
        requireLogin();
        Session current = attached;
        if (!owns(current, viewId, tabId) || current.phase != Phase.CONNECTED) {
            throw notConnected();
        }
        return current;
    }

    private static boolean owns(Session current, String viewId) {
        return current != null && viewId != null && viewId.equals(current.viewId);
    }

    private static boolean owns(Session current, String viewId, String tabId) {
        return owns(current, viewId) && current.id.equals(tabId);
    }

    private void requireView(String viewId) throws BridgeFailure {
        if (!(context.getCurrentActivity() instanceof WorkspaceActivity)
                || context.getLifecycleState() != LifecycleState.RESUMED) {
            throw new BridgeFailure("NO_TERMINAL_VIEW", t("native.open_the_host_terminal_in_the_foreground_workspace"));
        }
        if (viewId == null || viewId.isEmpty() || viewId.length() > 128) {
            throw new BridgeFailure("INVALID_VIEW", t("native.this_terminal_view_expired_open_it_again"));
        }
    }

    private static void checkSize(double cols, double rows) throws BridgeFailure {
        if (cols != (int) cols || rows != (int) rows || cols < 1 || cols > HermesTerminal.MAX_COLUMNS
                || rows < 1 || rows > HermesTerminal.MAX_ROWS) {
            throw new BridgeFailure("INVALID_SIZE", t("native.invalid_terminal_dimensions"));
        }
    }

    private static String optionValue(String option, int limit) throws BridgeFailure {
        if (option == null) {
            return null;
        }
        if (option.length() > limit || option.chars().anyMatch(Character::isISOControl)) {
            throw new BridgeFailure("INVALID_OPTION", t("native.invalid_host_terminal_profile_or_working_directory"));
        }
        String trimmed = option.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static BridgeFailure inputTooLong() {
        return new BridgeFailure("INVALID_INPUT", t("native.the_input_exceeds_the_single_send_limit"));
    }

    private static BridgeFailure notConnected() {
        return new BridgeFailure("NOT_CONNECTED", t("native.this_terminal_connection_is_unavailable"));
    }

    private void submit(Promise promise, Command command) {
        main.post(() -> {
            if (invalidated) {
                promise.reject("TERMINAL_UNAVAILABLE", t("native.the_host_terminal_closed_reopen_the_workspace"));
                return;
            }
            try {
                command.run();
            } catch (BridgeFailure failure) {
                promise.reject(failure.code, failure.getMessage());
            }
        });
    }

    private static WritableMap event(String kind) {
        WritableMap event = Arguments.createMap();
        event.putString("kind", kind);
        return event;
    }

    private void emit(String tabId, String viewId, WritableMap event) {
        if (viewId != null && !invalidated && context.hasActiveReactInstance()) {
            event.putString("tabId", tabId);
            event.putString("viewId", viewId);
            context.emitDeviceEvent(EVENT, event);
        }
    }

    private void closed(String tabId, String viewId, String reason) {
        WritableMap event = event("closed");
        event.putString("reason", reason);
        emit(tabId, viewId, event);
    }

    private void error(String tabId, String viewId, String code, String message, boolean canReconnect) {
        WritableMap event = event("error");
        event.putString("code", code);
        event.putString("message", message);
        event.putBoolean("canReconnect", canReconnect);
        emit(tabId, viewId, event);
    }

    @Override
    public void onHostResume() {
        currentLogin();
    }

    @Override
    public void onHostPause() {
        currentLogin();
        Session current = attached;
        if (current != null) {
            if (current.phase == Phase.DRAINING) {
                finishConnection(current, current.pendingEnd);
            } else {
                detach(current);
            }
        }
    }

    @Override
    public void onHostDestroy() {
        currentLogin();
        for (Session current : new ArrayList<>(tabs.values())) {
            if (current.viewId != null) {
                leave(current);
            }
        }
    }

    @Override
    public void invalidate() {
        invalidated = true;
        stopObservingLogin.run();
        context.removeLifecycleEventListener(this);
        main.post(this::currentLogin);
        super.invalidate();
    }

    private enum Phase {
        CONNECTING("connecting"),
        CONNECTED("connected"),
        DETACHING("detaching"),
        DETACHED("detached"),
        DRAINING("closing"),
        CLOSING("closing");

        final String value;

        Phase(String value) {
            this.value = value;
        }
    }

    private static final class Session {
        final String id = UUID.randomUUID().toString();
        final int number;
        final String profile;
        final HermesTerminal terminal;
        String viewId;
        Phase phase = Phase.CONNECTING;
        int listenerGeneration;
        boolean reconnecting;
        boolean keepOnCloseFailure;
        OutputQueue output;
        HermesTerminal.End pendingEnd;
        double outputSequence;
        Runnable ackTimeout;

        Session(int number, String profile, HermesTerminal terminal) {
            this.number = number;
            this.profile = profile;
            this.terminal = terminal;
        }
    }

    /** One batch can be in React Native at a time. Only one wakeup can wait on the main queue. */
    private static final class OutputQueue {
        private final Buffer live = new Buffer();
        private final Buffer replay = new Buffer();
        private final Runnable wake;
        // A zero-byte snapshot also owns an outstanding acknowledgement.
        private int inFlight = -1;
        private boolean replayPending;
        private boolean wakePending;
        private boolean overflowed;
        private boolean stopped;

        OutputQueue(Runnable wake) {
            this.wake = wake;
        }

        synchronized void append(byte[] data, boolean historical) {
            if (stopped || overflowed || !historical && data.length == 0) {
                return;
            }
            if (data.length > OUTPUT_BACKLOG_BYTES - live.size() - replay.size() - Math.max(0, inFlight)) {
                overflowed = true;
                live.clear();
                replay.clear();
                replayPending = false;
            } else if (historical) {
                replay.write(data);
                replayPending = true;
            } else {
                live.write(data);
            }
            if (!wakePending && (overflowed || inFlight < 0)) {
                wakePending = true;
                wake.run();
            }
        }

        synchronized boolean overflowed() {
            return overflowed;
        }

        synchronized boolean isEmpty() {
            return inFlight < 0 && !replayPending && live.size() == 0;
        }

        synchronized OutputBatch take() {
            wakePending = false;
            if (stopped || overflowed || inFlight >= 0 || !replayPending && live.size() == 0) {
                return null;
            }
            boolean historical = replayPending;
            Buffer source = historical ? replay : live;
            inFlight = (int) Math.min(source.size(), OUTPUT_BATCH_BYTES);
            try {
                byte[] data = source.readByteArray(inFlight);
                boolean replayEnd = historical && source.size() == 0;
                if (replayEnd) {
                    replayPending = false;
                }
                return new OutputBatch(data, historical, replayEnd);
            } catch (EOFException error) {
                throw new IllegalStateException(error);
            }
        }

        synchronized void ack() {
            inFlight = -1;
        }

        synchronized void stop() {
            stopped = true;
            live.clear();
            replay.clear();
            replayPending = false;
            inFlight = -1;
        }
    }

    private static final class OutputBatch {
        final byte[] data;
        final boolean replay;
        final boolean replayEnd;

        OutputBatch(byte[] data, boolean replay, boolean replayEnd) {
            this.data = data;
            this.replay = replay;
            this.replayEnd = replayEnd;
        }
    }

    private interface Command {
        void run() throws BridgeFailure;
    }

    private static final class BridgeFailure extends Exception {
        final String code;

        BridgeFailure(String code, String message) {
            super(message);
            this.code = code;
        }
    }
}
