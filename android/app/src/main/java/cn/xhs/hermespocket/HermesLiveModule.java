package cn.xhs.hermespocket;

import static cn.xhs.hermespocket.HermesText.t;

import android.os.Handler;
import android.os.Looper;

import com.facebook.react.bridge.LifecycleEventListener;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.common.LifecycleState;

import org.json.JSONException;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Keeps a single board subscription inside the currently signed-in workspace. */
public final class HermesLiveModule extends ReactContextBaseJavaModule implements LifecycleEventListener {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1), new ThreadPoolExecutor.DiscardOldestPolicy());
    private final ReactApplicationContext context;
    private final ConnectionStore store;
    private final Runnable stopObservingLogin;
    private String viewer;
    private ConnectionStore.LoginScope login;
    private KanbanEventStream stream;
    private volatile boolean invalidated;

    public HermesLiveModule(ReactApplicationContext context) {
        super(context);
        this.context = context;
        store = new ConnectionStore(context);
        stopObservingLogin = store.observeLoginChanges(() -> main.post(this::discardStale));
        context.addLifecycleEventListener(this);
    }

    @Override
    public String getName() {
        return "HermesLive";
    }

    @ReactMethod
    public void watch(String viewerId, String profile, String board, Promise promise) {
        main.post(() -> {
            discardStale();
            ConnectionStore.LoginScope scope = store.activeLogin();
            if (invalidated || scope == null) {
                promise.reject("SIGN_IN", t("native.sign_in_to_hermes_first"));
                return;
            }
            if (context.getLifecycleState() != LifecycleState.RESUMED) {
                promise.reject("PAUSED", t("native.open_the_workspace_before_connecting_to_kanban"));
                return;
            }
            try {
                if (viewerId == null || !viewerId.matches("[A-Za-z0-9_-]{1,80}")) {
                    throw new IllegalArgumentException();
                }
                if (stream == null || !viewerId.equals(viewer) || !stream.matches(profile, board)) {
                    KanbanEventStream next = new KanbanEventStream(new HermesClient(scope.url, store.authFor(scope)),
                            () -> !invalidated && scope.equals(store.activeLogin()), profile, board);
                    discard();
                    viewer = viewerId;
                    login = scope;
                    stream = next;
                }
                Runnable start = stream.prepareStart();
                if (start != null) {
                    worker.execute(start);
                }
                promise.resolve(stream.snapshot());
            } catch (IllegalArgumentException | JSONException error) {
                promise.reject("INVALID_BOARD", t("native.select_a_valid_profile_and_board"));
            }
        });
    }

    @ReactMethod
    public void snapshot(String viewerId, Promise promise) {
        main.post(() -> {
            discardStale();
            try {
                promise.resolve(stream != null && viewer.equals(viewerId) ? stream.snapshot() : null);
            } catch (JSONException error) {
                promise.reject("INVALID_EVENTS", t("native.could_not_read_kanban_events"));
            }
        });
    }

    @ReactMethod
    public void stop(String viewerId) {
        main.post(() -> {
            if (viewerId != null && viewerId.equals(viewer)) {
                discard();
            }
        });
    }

    private void discardStale() {
        if (stream != null && (invalidated || !login.equals(store.activeLogin()))) {
            discard();
        }
    }

    private void discard() {
        if (stream != null) {
            stream.stop();
            stream = null;
        }
        login = null;
        viewer = null;
        worker.getQueue().clear();
    }

    @Override
    public void onHostResume() {
        discardStale();
    }

    @Override
    public void onHostPause() {
        if (stream != null) {
            stream.stop();
        }
        worker.getQueue().clear();
    }

    @Override
    public void onHostDestroy() {
        discard();
    }

    @Override
    public void invalidate() {
        invalidated = true;
        stopObservingLogin.run();
        context.removeLifecycleEventListener(this);
        main.post(() -> {
            discard();
            worker.shutdownNow();
        });
        super.invalidate();
    }
}
