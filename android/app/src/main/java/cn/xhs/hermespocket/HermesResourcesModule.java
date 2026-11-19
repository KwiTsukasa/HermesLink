package cn.xhs.hermespocket;

import static cn.xhs.hermespocket.HermesText.t;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;

import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.common.LifecycleState;

/** Opens the private Android media reader for one configured Hermes file. */
public final class HermesResourcesModule extends ReactContextBaseJavaModule {
    private final ReactApplicationContext context;
    private final ConnectionStore store;
    private final HermesProfilePicker archives;
    private final Handler main = new Handler(Looper.getMainLooper());

    public HermesResourcesModule(ReactApplicationContext context) {
        super(context);
        this.context = context;
        store = new ConnectionStore(context);
        archives = new HermesProfilePicker(context, store);
    }

    @ReactMethod
    public void exportProfile(String name, Promise promise) {
        archives.begin(name, false, promise);
    }

    @ReactMethod
    public void importProfile(String name, Promise promise) {
        archives.begin(name, true, promise);
    }

    @Override
    public void invalidate() {
        archives.invalidate();
        super.invalidate();
    }

    @Override
    public String getName() {
        return "HermesResources";
    }

    @ReactMethod
    public void playMedia(String path, String profile, String sessionId, Promise promise) {
        main.post(() -> {
            Activity activity = context.getCurrentActivity();
            ConnectionStore.LoginScope scope = store.activeLogin();
            if (scope == null || !(activity instanceof WorkspaceActivity)
                    || context.getLifecycleState() != LifecycleState.RESUMED) {
                promise.reject("MEDIA_UNAVAILABLE", t("native.sign_in_to_hermes_and_open_media_from_the_foreground"));
                return;
            }
            try {
                HermesResourceClient.Target target = new HermesResourceClient.Target(path, profile, sessionId);
                HermesResourceActivity.open(activity, scope, target);
                promise.resolve(null);
            } catch (IllegalArgumentException error) {
                promise.reject("MEDIA_TARGET", t("native.the_file_path_type_or_conversation_is_invalid"));
            } catch (RuntimeException error) {
                promise.reject("MEDIA_UNAVAILABLE", t("native.could_not_open_the_device_media_player"));
            }
        });
    }
}
