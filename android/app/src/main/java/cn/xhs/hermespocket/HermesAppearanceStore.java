package cn.xhs.hermespocket;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Account-isolated local view and outbox for the host's official appearance preferences. */
public final class HermesAppearanceStore {
    private static final String PREFS = "hermes_appearance_v1";
    private static final String[] TOKENS = {"bg", "panel", "ink", "sub", "line", "accent", "wash", "danger"};
    private static final String[] DESKTOP_TOKENS = {"background", "card", "foreground", "mutedForeground",
            "border", "midground", "accent", "destructive"};
    private static final int MAX_PENDING = 16;
    private static HermesAppearanceStore instance;

    public static final class Palette {
        public final int bg, panel, ink, sub, line, accent, wash, danger;
        public final boolean dark;
        public final String themeName;

        private Palette(JSONObject colors, String theme) {
            themeName = theme;
            bg = color(colors, "bg", Color.WHITE);
            panel = color(colors, "panel", bg);
            ink = color(colors, "ink", Color.BLACK);
            sub = color(colors, "sub", ink);
            line = color(colors, "line", ink);
            accent = color(colors, "accent", ink);
            wash = color(colors, "wash", panel);
            danger = color(colors, "danger", ink);
            dark = 0.2126 * Color.red(bg) + 0.7152 * Color.green(bg) + 0.0722 * Color.blue(bg) <= 127.5;
        }

        private static int color(JSONObject colors, String key, int fallback) {
            String value = colors.optString(key, "");
            if (value.matches("(?i)#[0-9a-f]{8}")) {
                return Color.parseColor("#" + value.substring(7) + value.substring(1, 7));
            }
            return value.matches("(?i)#[0-9a-f]{6}") ? Color.parseColor(value) : fallback;
        }
    }

    interface Completion {
        void finish(JSONObject settings, Exception failure);
    }

    private final Context context;
    private final SharedPreferences preferences;
    private final ConnectionStore login;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(24));
    private final Map<String, JSONObject> presets = new HashMap<>();
    private final Map<String, HermesAppearanceClient.Change> pending = new LinkedHashMap<>();
    private final Runnable loginObservation;
    private ConnectionStore.LoginScope scope;
    private HermesClient.RequestRead request;
    private HermesAppearanceClient.Document document = HermesAppearanceClient.Document.empty();
    private String sync = "local";
    private long scopeVersion;

    private HermesAppearanceStore(Context app) {
        context = app;
        preferences = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        login = new ConnectionStore(app);
        loadPresets();
        loginObservation = login.observeLoginChanges(() -> main.post(this::loginChanged));
    }

    static synchronized HermesAppearanceStore instance(Context context) {
        if (instance == null) {
            instance = new HermesAppearanceStore(context.getApplicationContext());
        }
        return instance;
    }

    public static Palette palette(Context context, String profile) {
        try {
            JSONObject snapshot = instance(context).snapshot(HermesAppearanceClient.profile(profile));
            boolean systemDark = (context.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            String mode = snapshot.getString("mode");
            boolean selectedDark = "dark".equals(mode) || ("system".equals(mode) && systemDark);
            JSONObject colors = snapshot.getJSONObject(selectedDark ? "dark" : "light");
            return new Palette(colors, snapshot.getString("themeName"));
        } catch (JSONException | IllegalArgumentException error) {
            return new Palette(new JSONObject(), "nous");
        }
    }

    public static Runnable observe(Context context, Runnable observer) {
        HermesAppearanceStore store = instance(context);
        store.listeners.add(observer);
        return () -> store.listeners.remove(observer);
    }

    public static void refresh(Context context, String profile) {
        try {
            instance(context).submit(HermesAppearanceClient.profile(profile), null, (settings, error) -> {});
        } catch (IllegalArgumentException ignored) {
            // The native page can still use the host default while its profile is unavailable.
        }
    }

    synchronized JSONObject snapshot(String profile) throws JSONException {
        synchronizeScope();
        HermesAppearanceClient.Document displayed = document;
        for (HermesAppearanceClient.Change change : pending.values()) {
            try {
                displayed = displayed.local(change);
            } catch (JSONException ignored) {
                // Keep the last readable palette if a desktop override map is malformed.
            }
        }
        String selected = displayed.theme(profile);
        JSONObject preset = presets.get(selected);
        JSONObject fallback = presets.get("nous");
        JSONObject light = fallback == null ? new JSONObject() : fallback.getJSONObject("light");
        JSONObject dark = fallback == null ? new JSONObject() : fallback.getJSONObject("dark");
        boolean available = preset != null;
        String label = selected;
        if (preset != null) {
            light = preset.getJSONObject("light");
            dark = preset.getJSONObject("dark");
            label = preset.optString("label", selected);
        } else {
            JSONObject custom = displayed.customTheme(selected);
            if (custom != null) {
                label = custom.optString("label", selected);
                JSONObject seed = custom.optJSONObject("colors");
                JSONObject alternate = custom.optJSONObject("darkColors");
                JSONObject customLight = alternate == null ? lightFromSingle(seed) : colors(seed);
                JSONObject customDark = colors(alternate == null ? seed : alternate);
                if (customLight != null && customDark != null) {
                    light = customLight;
                    dark = customDark;
                    available = true;
                }
            }
        }
        return new JSONObject().put("profile", profile).put("themeName", selected).put("label", label)
                .put("mode", displayed.mode(profile)).put("sync", sync).put("revision", document.revision)
                .put("scopeVersion", scopeVersion)
                .put("available", available).put("light", light).put("dark", dark).put("signedIn", scope != null);
    }

    void submit(String profile, HermesAppearanceClient.Change change, Completion completion) {
        final ConnectionStore.LoginScope captured;
        synchronized (this) {
            synchronizeScope();
            captured = scope;
        }
        try {
            worker.execute(() -> run(profile, change, captured, completion));
        } catch (RejectedExecutionException error) {
            completion.finish(null, error);
        }
    }

    private void run(String profile, HermesAppearanceClient.Change change, ConnectionStore.LoginScope captured,
            Completion completion) {
        try {
            HermesClient.RequestRead cancellation = new HermesClient.RequestRead();
            synchronized (this) {
                requireCurrent(captured);
                if (captured == null) {
                    if (change != null) {
                        throw new IOException("Sign in before changing host appearance preferences.");
                    }
                    completion.finish(snapshot(profile), null);
                    return;
                }
                if (change != null) {
                    if (change.themeName != null && !presets.containsKey(change.themeName)
                            && document.customTheme(change.themeName) == null) {
                        throw new IllegalArgumentException("Unknown Webapp appearance preset.");
                    }
                    stage(change);
                }
                request = cancellation;
                sync = "syncing";
            }
            if (change != null) {
                changed();
            }
            boolean loaded = false;
            try {
                HermesAppearanceClient client = new HermesAppearanceClient(
                        new HermesClient(captured.url, login.authFor(captured)));
                HermesAppearanceClient.Document latest = client.read(cancellation);
                ArrayList<HermesAppearanceClient.Change> changes;
                synchronized (this) {
                    requireCurrent(captured);
                    document = latest;
                    changes = new ArrayList<>(pending.values());
                }
                for (HermesAppearanceClient.Change queued : changes) {
                    synchronized (this) {
                        requireCurrent(captured);
                    }
                    latest = client.update(latest, queued, cancellation);
                    synchronized (this) {
                        requireCurrent(captured);
                        document = latest;
                        pending.remove(queued.profile);
                        persist();
                    }
                }
                loaded = true;
            } catch (IOException | JSONException unavailable) {
                // Keep the scoped cache and replay only explicitly chosen fields when this login reconnects.
            } finally {
                synchronized (this) {
                    if (request == cancellation) {
                        request = null;
                    }
                }
            }
            JSONObject result;
            synchronized (this) {
                requireCurrent(captured);
                sync = loaded ? "synced" : pending.isEmpty() ? "offline" : "pending";
                persist();
                result = snapshot(profile);
            }
            changed();
            completion.finish(result, null);
        } catch (IOException | JSONException | IllegalArgumentException error) {
            completion.finish(null, error);
        }
    }

    private void stage(HermesAppearanceClient.Change change) throws JSONException {
        HermesAppearanceClient.Change previous = pending.get(change.profile);
        if (previous != null) {
            change = new HermesAppearanceClient.Change(change.profile,
                    change.themeName == null ? previous.themeName : change.themeName,
                    change.mode == null ? previous.mode : change.mode);
        } else if (pending.size() >= MAX_PENDING) {
            throw new IllegalArgumentException("Too many appearance changes are awaiting sync.");
        }
        pending.put(change.profile, change);
        persist();
    }

    private void synchronizeScope() {
        ConnectionStore.LoginScope current = login.activeLogin();
        if (java.util.Objects.equals(scope, current)) {
            return;
        }
        if (request != null) {
            request.cancel();
            request = null;
        }
        scope = current;
        scopeVersion++;
        pending.clear();
        document = HermesAppearanceClient.Document.empty();
        sync = current == null ? "local" : "cached";
        if (current == null) {
            return;
        }
        String cached = preferences.getString(cacheKey(), "");
        if (cached.isEmpty()) {
            return;
        }
        try {
            JSONObject record = new JSONObject(cached);
            document = new HermesAppearanceClient.Document(record.getJSONObject("values"), record.optLong("revision"));
            JSONArray outbox = record.optJSONArray("outbox");
            if (outbox != null) {
                for (int index = 0; index < Math.min(outbox.length(), MAX_PENDING); index++) {
                    JSONObject row = outbox.getJSONObject(index);
                    HermesAppearanceClient.Change change = new HermesAppearanceClient.Change(row.getString("profile"),
                            row.isNull("themeName") ? null : row.getString("themeName"),
                            row.isNull("mode") ? null : row.getString("mode"));
                    pending.put(change.profile, change);
                }
            }
        } catch (JSONException | IllegalArgumentException invalid) {
            document = HermesAppearanceClient.Document.empty();
            pending.clear();
        }
        if (!pending.isEmpty()) {
            sync = "pending";
        }
    }

    private void requireCurrent(ConnectionStore.LoginScope captured) throws IOException {
        synchronizeScope();
        if (!java.util.Objects.equals(scope, captured)) {
            throw new IOException("Appearance sign-in changed.");
        }
    }

    private String cacheKey() {
        return "scope." + scope.preferenceBinding();
    }

    private void persist() throws JSONException {
        JSONArray outbox = new JSONArray();
        for (HermesAppearanceClient.Change change : pending.values()) {
            outbox.put(new JSONObject().put("profile", change.profile).put("themeName", change.themeName)
                    .put("mode", change.mode));
        }
        JSONObject saved = document.cache().put("outbox", outbox);
        preferences.edit().putString(cacheKey(), saved.toString()).apply();
    }

    private void changed() {
        main.post(() -> {
            for (Runnable listener : listeners) {
                listener.run();
            }
        });
    }

    private void loginChanged() {
        boolean refresh;
        synchronized (this) {
            ConnectionStore.LoginScope previous = scope;
            synchronizeScope();
            refresh = scope != null && !scope.equals(previous);
        }
        changed();
        if (refresh) {
            submit("default", null, (settings, error) -> {});
        }
    }

    private static JSONObject colors(JSONObject custom) throws JSONException {
        if (custom == null) {
            return null;
        }
        JSONObject palette = new JSONObject();
        for (int index = 0; index < TOKENS.length; index++) {
            if (!custom.isNull(DESKTOP_TOKENS[index])) {
                palette.put(TOKENS[index], custom.getString(DESKTOP_TOKENS[index]));
            }
        }
        if (custom.isNull("midground") && !custom.isNull("ring")) {
            palette.put("accent", custom.getString("ring"));
        }
        return palette.length() == TOKENS.length ? palette : null;
    }

    private static JSONObject lightFromSingle(JSONObject custom) throws JSONException {
        if (custom == null) {
            return null;
        }
        String primary = custom.optString("ring", custom.optString("primary", ""));
        if (primary.isEmpty()) {
            return null;
        }
        return new JSONObject().put("bg", "#ffffff").put("panel", "#ffffff").put("ink", "#161616")
                .put("sub", mix("#6b6b70", primary, 0.16)).put("line", mix("#ececef", primary, 0.14))
                .put("accent", custom.optString("midground", primary)).put("wash", mix("#ffffff", primary, 0.10))
                .put("danger", "#b94a3a");
    }

    private static String mix(String left, String right, double amount) {
        int a = Color.parseColor(left);
        int b = Color.parseColor(right);
        return String.format(java.util.Locale.ROOT, "#%02x%02x%02x",
                blend(Color.red(a), Color.red(b), amount), blend(Color.green(a), Color.green(b), amount),
                blend(Color.blue(a), Color.blue(b), amount));
    }

    private static int blend(int first, int second, double amount) {
        return (int) Math.round(first + (second - first) * amount);
    }

    private void loadPresets() {
        try (InputStream input = context.getAssets().open("hermes-appearance/presets.json")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int count; (count = input.read(buffer)) != -1;) {
                if (bytes.size() > 128 * 1024 - count) {
                    throw new IOException("Invalid official theme asset.");
                }
                bytes.write(buffer, 0, count);
            }
            JSONArray rows = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8)).getJSONArray("presets");
            for (int index = 0; index < rows.length(); index++) {
                JSONObject row = rows.getJSONObject(index);
                presets.put(row.getString("id"), row);
            }
        } catch (IOException | JSONException error) {
            presets.clear();
        }
    }
}
