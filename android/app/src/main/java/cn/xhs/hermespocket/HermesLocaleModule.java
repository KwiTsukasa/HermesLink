package cn.xhs.hermespocket;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.LifecycleEventListener;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;

/** Links the Android language preference and system locale changes to React Native. */
public final class HermesLocaleModule extends ReactContextBaseJavaModule implements LifecycleEventListener {
    private static final String EVENT = "HermesLocaleChanged";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ReactApplicationContext context;
    private final Runnable stopObserving;
    private HermesLocaleStore.Settings published;
    private volatile boolean closed;
    private final BroadcastReceiver systemLocale = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            publish();
        }
    };

    public HermesLocaleModule(ReactApplicationContext context) {
        super(context);
        this.context = context;
        published = HermesLocaleStore.settings(context);
        stopObserving = HermesLocaleStore.observe(context, this::publish);
        IntentFilter filter = new IntentFilter(Intent.ACTION_LOCALE_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(systemLocale, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(systemLocale, filter);
        }
        context.addLifecycleEventListener(this);
    }

    @Override
    public String getName() {
        return "HermesLocale";
    }

    @ReactMethod
    public void getSettings(Promise promise) {
        promise.resolve(payload(HermesLocaleStore.settings(context)));
    }

    @ReactMethod
    public void setLocale(String locale, Promise promise) {
        try {
            promise.resolve(payload(HermesLocaleStore.setLocale(context, locale)));
            publish();
        } catch (IllegalArgumentException error) {
            promise.reject("INVALID_LOCALE", error.getMessage());
        }
    }

    @ReactMethod
    public void addListener(String event) {
        // DeviceEventEmitter owns the subscription.
    }

    @ReactMethod
    public void removeListeners(double count) {
        // DeviceEventEmitter owns the subscription.
    }

    private void publish() {
        main.post(() -> {
            if (closed || !context.hasActiveReactInstance()) {
                return;
            }
            HermesLocaleStore.Settings settings = HermesLocaleStore.settings(context);
            if (!settings.same(published)) {
                published = settings;
                context.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                        .emit(EVENT, payload(settings));
            }
        });
    }

    private static WritableMap payload(HermesLocaleStore.Settings settings) {
        WritableMap result = Arguments.createMap();
        result.putString("mode", settings.pinned == null ? "system" : "pinned");
        result.putString("effective", settings.effective);
        if (settings.pinned != null) {
            result.putString("locale", settings.pinned);
        }
        return result;
    }

    @Override
    public void onHostResume() {
        publish();
    }

    @Override
    public void onHostPause() {
    }

    @Override
    public void onHostDestroy() {
    }

    @Override
    public void invalidate() {
        if (closed) {
            return;
        }
        closed = true;
        stopObserving.run();
        context.unregisterReceiver(systemLocale);
        context.removeLifecycleEventListener(this);
        super.invalidate();
    }
}
