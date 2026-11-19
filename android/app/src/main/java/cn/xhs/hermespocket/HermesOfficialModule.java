package cn.xhs.hermespocket;

import static cn.xhs.hermespocket.HermesText.t;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.common.LifecycleState;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.HttpUrl;

/** Opens fixed official pages and keeps the device-login session identifier native. */
public final class HermesOfficialModule extends ReactContextBaseJavaModule {

    private final ReactApplicationContext context;
    private final ConnectionStore store;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService workers = Executors.newFixedThreadPool(2);
    private final Runnable stopObservingLogin;
    private volatile boolean closed;
    private Flow flow;

    public HermesOfficialModule(ReactApplicationContext context) {
        super(context);
        this.context = context;
        store = new ConnectionStore(context);
        stopObservingLogin = store.observeLoginChanges(() -> main.post(this::discardStaleFlow));
    }

    @Override
    public String getName() {
        return "HermesOfficial";
    }

    @ReactMethod
    public void destinations(Promise promise) {
        WritableArray rows = Arguments.createArray();
        for (HermesOfficialClient.Destination destination : HermesOfficialClient.Destination.values()) {
            WritableMap row = Arguments.createMap();
            row.putString("id", destination.id);
            row.putString("title", t(destination.titleKey));
            row.putString("guidance", t(destination.guidanceKey));
            row.putBoolean("direct", destination.direct());
            rows.pushMap(row);
        }
        promise.resolve(rows);
    }

    @ReactMethod
    public void open(String id, Promise promise) {
        main.post(() -> {
            HermesOfficialClient.Destination destination = HermesOfficialClient.Destination.find(id);
            String address = store.getUrl();
            if (destination == null || address == null) {
                reject(promise, t("native.select_an_official_destination_for_this_hermes_webapp"));
                return;
            }
            try {
                launch(destination.url(address), promise);
            } catch (IllegalArgumentException error) {
                reject(promise, t("native.the_current_hermes_webapp_address_is_invalid"));
            }
        });
    }

    @ReactMethod
    public void webappSignInOptions(Promise promise) {
        read(promise, HermesOfficialClient::signInOptions, HermesOfficialModule::signInRows);
    }

    @ReactMethod
    public void providers(Promise promise) {
        read(promise, HermesOfficialClient::providers, HermesOfficialModule::providerRows);
    }

    @ReactMethod
    public void beginProviderLogin(String provider, Promise promise) {
        main.post(() -> {
            if (!inWorkspace()) {
                reject(promise, t("native.start_provider_authorization_from_the_foreground_workspace"));
                return;
            }
            ConnectionStore.LoginScope scope = currentScope(promise);
            if (scope == null) {
                return;
            }
            discardStaleFlow();
            releaseExpiredFlow();
            if (!HermesOfficialClient.allowsDeviceLogin(provider)) {
                reject(promise, t("native.manage_this_provider_authorization_in_the_official_webapp"));
                return;
            }
            if (flow != null) {
                reject(promise, t("native.complete_or_cancel_the_current_provider_authorization_first"));
                return;
            }
            Flow started = new Flow(scope, officialClient(scope));
            flow = started;
            run(() -> {
                HermesOfficialClient.ProviderLogin login = started.client.begin(provider, started.read);
                if (closed) {
                    cancelQuietly(started.client, login);
                }
                return login;
            }, (result, error) -> {
                if (!isCurrent(started)) {
                    reject(promise, t("native.your_hermes_login_changed_reload_the_authorization_status"));
                    return;
                }
                started.read = null;
                if (error != null) {
                    flow = null;
                    reject(promise, readable(error, t("native.could_not_start_provider_authorization_check_the_official_webapp")));
                    return;
                }
                started.login = result;
                started.deadline = SystemClock.elapsedRealtime() + result.expiresIn * 1000L;
                WritableMap code = Arguments.createMap();
                code.putString("id", started.id);
                code.putString("userCode", result.userCode);
                code.putDouble("expiresAt", System.currentTimeMillis() + result.expiresIn * 1000L);
                code.putInt("intervalSeconds", result.interval);
                promise.resolve(code);
            });
        });
    }

    @ReactMethod
    public void openProviderVerification(String id, Promise promise) {
        main.post(() -> {
            Flow current = find(id, promise);
            if (current == null) {
                return;
            }
            if (current.cancelling || SystemClock.elapsedRealtime() >= current.deadline) {
                reject(promise, t("native.this_provider_code_has_expired_or_is_being_canceled"));
                return;
            }
            launch(current.login.verification, promise);
        });
    }

    @ReactMethod
    public void pollProviderLogin(String id, Promise promise) {
        main.post(() -> {
            Flow current = find(id, promise);
            if (current == null) {
                return;
            }
            if (current.cancelling) {
                reject(promise, t("native.this_authorization_is_being_canceled"));
                return;
            }
            long now = SystemClock.elapsedRealtime();
            if (current.read != null || now < current.nextPoll) {
                promise.resolve(status("pending"));
                return;
            }
            HermesClient.RequestRead read = new HermesClient.RequestRead();
            current.read = read;
            current.nextPoll = now + current.login.interval * 1000L;
            run(() -> current.client.poll(current.login, read), (state, error) -> {
                if (!isCurrent(current) || current.read != read) {
                    reject(promise, t("native.the_authorization_status_changed"));
                    return;
                }
                current.read = null;
                if (error != null) {
                    reject(promise, readable(error, t("native.could_not_confirm_the_provider_authorization_result_yet")));
                    return;
                }
                if ("pending".equals(state) && SystemClock.elapsedRealtime() >= current.deadline) {
                    flow = null;
                    workers.execute(() -> cancelQuietly(current.client, current.login));
                    promise.resolve(status("expired"));
                } else {
                    if (!"pending".equals(state)) {
                        flow = null;
                    }
                    promise.resolve(status(state));
                }
            });
        });
    }

    @ReactMethod
    public void cancelProviderLogin(String id, Promise promise) {
        main.post(() -> {
            Flow current = find(id, promise);
            if (current == null) {
                return;
            }
            if (current.cancelling) {
                reject(promise, t("native.this_authorization_is_being_canceled"));
                return;
            }
            current.cancelling = true;
            if (current.read != null) {
                current.read.cancel();
            }
            HermesClient.RequestRead read = new HermesClient.RequestRead();
            current.read = read;
            run(() -> {
                // Closing a pending device flow cannot remove a credential already saved by Hermes.
                String state = current.client.poll(current.login, read);
                if ("pending".equals(state)) {
                    current.client.cancel(current.login, read);
                }
                return state;
            }, (state, error) -> {
                if (!isCurrent(current) || current.read != read) {
                    reject(promise, t("native.your_hermes_login_changed_reload_the_authorization_status"));
                    return;
                }
                current.read = null;
                current.cancelling = false;
                if (error != null) {
                    reject(promise, readable(error, t("native.could_not_confirm_cancellation_try_again")));
                    return;
                }
                flow = null;
                if ("approved".equals(state)) {
                    reject(promise, t("native.the_provider_is_already_connected_disconnect_it_in_the_official"));
                } else {
                    promise.resolve(null);
                }
            });
        });
    }

    private void launch(HttpUrl url, Promise promise) {
        if (!inWorkspace()) {
            reject(promise, t("native.open_the_official_browser_page_from_the_foreground_workspace"));
            return;
        }
        Activity activity = context.getCurrentActivity();
        try {
            // Standard external browser intent. The browser has its own cookies and sign-in.
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url.toString()))
                    .addCategory(Intent.CATEGORY_BROWSABLE));
            promise.resolve(null);
        } catch (RuntimeException error) {
            reject(promise, t("native.this_device_cannot_open_the_browser_page"));
        }
    }

    private boolean inWorkspace() {
        return !closed && context.getCurrentActivity() instanceof WorkspaceActivity
                && context.getLifecycleState() == LifecycleState.RESUMED;
    }

    private Flow find(String id, Promise promise) {
        discardStaleFlow();
        Flow current = flow;
        if (current == null || current.login == null || !current.id.equals(id)) {
            reject(promise, t("native.this_provider_authorization_ended_reload_the_account_status"));
            return null;
        }
        return current;
    }

    private boolean isCurrent(Flow candidate) {
        return !closed && flow == candidate && candidate.scope.equals(store.activeLogin());
    }

    private void discardStaleFlow() {
        if (flow != null && !isCurrent(flow)) {
            if (flow.read != null) {
                flow.read.cancel();
            }
            flow = null;
        }
    }

    private void releaseExpiredFlow() {
        Flow expired = flow;
        if (expired == null || expired.login == null || expired.cancelling
                || SystemClock.elapsedRealtime() < expired.deadline) {
            return;
        }
        flow = null;
        if (expired.read != null) {
            expired.read.cancel();
        }
        workers.execute(() -> cancelQuietly(expired.client, expired.login));
    }

    private static void cancelQuietly(HermesOfficialClient client, HermesOfficialClient.ProviderLogin login) {
        try {
            client.cancel(login, new HermesClient.RequestRead());
        } catch (Exception ignored) {
            // An abandoned, unapproved device session expires on the server.
        }
    }

    @Override
    public void invalidate() {
        closed = true;
        stopObservingLogin.run();
        main.post(() -> {
            Flow current = flow;
            flow = null;
            if (current != null) {
                if (current.read != null) {
                    current.read.cancel();
                }
                if (current.login != null && current.scope.equals(store.activeLogin())) {
                    workers.execute(() -> cancelQuietly(current.client, current.login));
                }
            }
            workers.shutdown();
        });
        super.invalidate();
    }

    private HermesOfficialClient officialClient(ConnectionStore.LoginScope scope) {
        return new HermesOfficialClient(scope.url, store.authFor(scope));
    }

    private ConnectionStore.LoginScope currentScope(Promise promise) {
        ConnectionStore.LoginScope scope = closed ? null : store.activeLogin();
        if (scope == null) {
            reject(promise, t("native.sign_in_to_hermes_first"));
        }
        return scope;
    }

    private <T> void read(Promise promise, Read<T> query, Rows<T> toRows) {
        main.post(() -> {
            ConnectionStore.LoginScope scope = currentScope(promise);
            if (scope == null) {
                return;
            }
            HermesOfficialClient client = officialClient(scope);
            run(() -> query.get(client, new HermesClient.RequestRead()), (rows, error) -> {
                if (closed || !scope.equals(store.activeLogin())) {
                    reject(promise, t("native.your_hermes_login_changed_reload_the_authorization_status"));
                } else if (error != null) {
                    reject(promise, readable(error, t("native.could_not_read_the_hermes_provider_status")));
                } else {
                    promise.resolve(toRows.convert(rows));
                }
            });
        });
    }

    private <T> void run(Request<T> operation, Result<T> callback) {
        workers.execute(() -> {
            try {
                T result = operation.get();
                main.post(() -> callback.deliver(result, null));
            } catch (Exception error) {
                main.post(() -> callback.deliver(null, error));
            }
        });
    }

    private static WritableArray signInRows(List<HermesOfficialClient.SignInOption> entries) {
        WritableArray rows = Arguments.createArray();
        for (HermesOfficialClient.SignInOption entry : entries) {
            WritableMap row = Arguments.createMap();
            row.putString("id", entry.id);
            row.putString("label", entry.label);
            row.putBoolean("supportsPassword", entry.supportsPassword);
            rows.pushMap(row);
        }
        return rows;
    }

    private static WritableArray providerRows(List<HermesOfficialClient.ProviderStatus> entries) {
        WritableArray rows = Arguments.createArray();
        for (HermesOfficialClient.ProviderStatus entry : entries) {
            WritableMap row = Arguments.createMap();
            row.putString("id", entry.id);
            row.putString("label", entry.label);
            if (entry.connected == null) {
                row.putNull("connected");
            } else {
                row.putBoolean("connected", entry.connected);
            }
            row.putBoolean("deviceFlowAvailable", entry.deviceFlowAvailable);
            rows.pushMap(row);
        }
        return rows;
    }

    private static WritableMap status(String state) {
        WritableMap status = Arguments.createMap();
        status.putString("state", state);
        switch (state) {
            case "approved":
                status.putString("message", t("native.hermes_saved_the_provider_authorization"));
                break;
            case "denied":
                status.putString("message", t("native.the_provider_denied_authorization"));
                break;
            case "expired":
                status.putString("message", t("native.this_code_has_expired"));
                break;
            case "cancelled":
                status.putString("message", t("native.authorization_wait_canceled"));
                break;
            case "error":
                status.putString("message", t("native.the_provider_did_not_finish_authorization_check_the_official_webapp"));
                break;
        }
        return status;
    }

    private static String readable(Exception error, String fallback) {
        if (error instanceof ConnectionStore.SignInRequiredException) {
            return t("native.sign_in_to_hermes_first");
        }
        if (error instanceof HermesOfficialClient.ProtocolFailure) {
            return error.getMessage();
        }
        if (error instanceof HermesClient.HttpException) {
            int status = ((HermesClient.HttpException) error).statusCode;
            if (status == 401 || status == 403) {
                return t("native.this_login_cannot_perform_this_action_continue_in_the_official");
            }
        }
        return fallback;
    }

    private static void reject(Promise promise, String message) {
        promise.reject("HERMES_OFFICIAL", message);
    }

    private static final class Flow {
        final String id = UUID.randomUUID().toString();
        final ConnectionStore.LoginScope scope;
        final HermesOfficialClient client;
        HermesClient.RequestRead read = new HermesClient.RequestRead();
        HermesOfficialClient.ProviderLogin login;
        long deadline;
        long nextPoll;
        boolean cancelling;

        Flow(ConnectionStore.LoginScope scope, HermesOfficialClient client) {
            this.scope = scope;
            this.client = client;
        }
    }

    private interface Request<T> {
        T get() throws Exception;
    }

    private interface Result<T> {
        void deliver(T response, Exception error);
    }

    private interface Read<T> {
        T get(HermesOfficialClient client, HermesClient.RequestRead read) throws Exception;
    }

    private interface Rows<T> {
        WritableArray convert(T value);
    }
}
