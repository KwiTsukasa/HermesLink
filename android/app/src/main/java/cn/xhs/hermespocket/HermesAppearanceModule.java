package cn.xhs.hermespocket;

import android.os.Handler;
import android.os.Looper;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.LifecycleEventListener;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.common.LifecycleState;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;

/** Supplies official appearance preferences to the foreground React workspace. */
public final class HermesAppearanceModule extends ReactContextBaseJavaModule implements LifecycleEventListener {
    private static final String EVENT = "HermesAppearanceChanged";
    private static final int POLL_MS = 10_000;
    private final ReactApplicationContext context;
    private final HermesAppearanceStore store;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable stopObserving;
    private volatile String profile = "default";
    private String lastEvent;
    private boolean foreground;
    private volatile boolean invalidated;
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!invalidated && foreground) {
                HermesAppearanceStore.refresh(context, profile);
                main.postDelayed(this, POLL_MS);
            }
        }
    };

    public HermesAppearanceModule(ReactApplicationContext context) {
        super(context);
        this.context = context;
        store = HermesAppearanceStore.instance(context);
        stopObserving = HermesAppearanceStore.observe(context, this::publish);
        context.addLifecycleEventListener(this);
        if (context.getLifecycleState() == LifecycleState.RESUMED) {
            main.post(this::onHostResume);
        }
    }

    @Override
    public String getName() {
        return "HermesAppearance";
    }

    @ReactMethod
    public void getAppearance(String requestedProfile, Promise promise) {
        try {
            String selected = HermesAppearanceClient.profile(requestedProfile);
            profile = selected;
            promise.resolve(payload(store.snapshot(selected)));
        } catch (JSONException | IllegalArgumentException error) {
            reject(promise);
        }
    }

    @ReactMethod
    public void refresh(String requestedProfile, Promise promise) {
        submit(requestedProfile, null, null, false, promise);
    }

    @ReactMethod
    public void update(String requestedProfile, String themeName, String mode, Promise promise) {
        submit(requestedProfile, themeName, mode, true, promise);
    }

    @ReactMethod
    public void addListener(String event) {
        // DeviceEventEmitter owns the JS subscription.
    }

    @ReactMethod
    public void removeListeners(double count) {
        // DeviceEventEmitter owns the JS subscription.
    }

    private void submit(String requestedProfile, String themeName, String mode, boolean write, Promise promise) {
        try {
            String selected = HermesAppearanceClient.profile(requestedProfile);
            profile = selected;
            HermesAppearanceClient.Change change = write
                    ? new HermesAppearanceClient.Change(selected, themeName, mode) : null;
            store.submit(selected, change, (settings, failure) -> {
                if (failure != null) {
                    reject(promise);
                } else {
                    try {
                        promise.resolve(payload(settings));
                    } catch (JSONException error) {
                        reject(promise);
                    }
                }
            });
        } catch (IllegalArgumentException error) {
            reject(promise);
        }
    }

    private static void reject(Promise promise) {
        promise.reject("APPEARANCE", "Could not load Webapp appearance.");
    }

    private void publish() {
        if (invalidated || !context.hasActiveReactInstance()) {
            return;
        }
        try {
            JSONObject value = store.snapshot(profile);
            String key = value.toString();
            if (!key.equals(lastEvent)) {
                lastEvent = key;
                context.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class).emit(EVENT, payload(value));
            }
        } catch (JSONException ignored) {
            // Keep the last complete appearance state.
        }
    }

    private static WritableMap payload(JSONObject object) throws JSONException {
        WritableMap value = Arguments.createMap();
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object item = object.get(key);
            if (item instanceof JSONObject) {
                value.putMap(key, payload((JSONObject) item));
            } else if (item instanceof Boolean) {
                value.putBoolean(key, (Boolean) item);
            } else if (item instanceof Number) {
                value.putDouble(key, ((Number) item).doubleValue());
            } else if (item instanceof String) {
                value.putString(key, (String) item);
            }
        }
        return value;
    }

    @Override
    public void onHostResume() {
        if (invalidated || foreground) {
            return;
        }
        foreground = true;
        poll.run();
    }

    @Override
    public void onHostPause() {
        foreground = false;
        main.removeCallbacks(poll);
    }

    @Override
    public void onHostDestroy() {
        onHostPause();
    }

    @Override
    public void invalidate() {
        invalidated = true;
        onHostPause();
        stopObserving.run();
        context.removeLifecycleEventListener(this);
        super.invalidate();
    }
}
