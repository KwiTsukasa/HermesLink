package cn.xhs.hermespocket;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Iterator;

import okhttp3.MediaType;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Filters and writes only the appearance keys of Hermes Webapp's shared preference document. */
final class HermesAppearanceClient {
    private static final String GLOBAL_THEME = "hermes-desktop-theme-v2";
    private static final String GLOBAL_MODE = "hermes-desktop-mode-v1";
    private static final String PROFILE_THEMES = "hermes-desktop-profile-themes-v1";
    private static final String PROFILE_MODES = "hermes-desktop-profile-modes-v1";
    private static final String USER_THEMES = "hermes-desktop-user-themes-v1";
    private static final int MAX_RESPONSE_BYTES = 12 * 1024 * 1024;
    private static final int MAX_CUSTOM_THEMES = 128;
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final HermesClient client;

    HermesAppearanceClient(HermesClient client) {
        this.client = client;
    }

    Document read(HermesClient.RequestRead cancellation) throws IOException, JSONException {
        return exchange(null, cancellation);
    }

    Document update(Document before, Change change, HermesClient.RequestRead cancellation)
            throws IOException, JSONException {
        JSONObject request = new JSONObject().put("values", before.patch(change));
        return exchange(RequestBody.create(request.toString(), JSON), cancellation);
    }

    private Document exchange(RequestBody request, HermesClient.RequestRead cancellation)
            throws IOException, JSONException {
        try (Response response = client.executeAppearanceRequest(request, cancellation)) {
            ResponseBody body = response.body();
            if (body == null || body.source().request(MAX_RESPONSE_BYTES + 1L)) {
                throw new IOException("Invalid Webapp appearance preferences.");
            }
            JSONObject payload = new JSONObject(body.source().readUtf8());
            JSONObject values = payload.optJSONObject("values");
            if (values == null) {
                throw new IOException("Invalid Webapp appearance preferences.");
            }
            return new Document(values, Math.max(0, payload.optLong("revision", 0)));
        }
    }

    static String profile(String candidate) {
        if (candidate == null || candidate.trim().isEmpty()) {
            return "default";
        }
        String value = candidate.trim();
        if (!value.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Unsupported appearance profile.");
        }
        return value;
    }

    static boolean themeName(String name) {
        return name != null && name.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,95}");
    }

    static boolean mode(String name) {
        return "light".equals(name) || "dark".equals(name) || "system".equals(name);
    }

    static final class Change {
        final String profile;
        final String themeName;
        final String mode;

        Change(String profile, String themeName, String mode) {
            this.profile = profile(profile);
            if ((themeName == null && mode == null) || (themeName != null && !themeName(themeName))
                    || (mode != null && !mode(mode))) {
                throw new IllegalArgumentException("Unsupported Webapp appearance selection.");
            }
            this.themeName = themeName;
            this.mode = mode;
        }
    }

    static final class Document {
        private final JSONObject values;
        final long revision;

        Document(JSONObject data, long revision) throws JSONException {
            this.revision = revision;
            values = new JSONObject();
            for (String key : new String[]{GLOBAL_THEME, GLOBAL_MODE, PROFILE_THEMES, PROFILE_MODES}) {
                Object value = data.opt(key);
                if (value instanceof String) {
                    values.put(key, value);
                }
            }
            String custom = data.optString(USER_THEMES, "");
            if (!custom.isEmpty()) {
                values.put(USER_THEMES, typedCustomThemes(custom).toString());
            }
        }

        static Document empty() {
            try {
                return new Document(new JSONObject(), 0);
            } catch (JSONException error) {
                throw new IllegalStateException(error);
            }
        }

        String theme(String profile) {
            String value = selected(profile, PROFILE_THEMES, GLOBAL_THEME);
            if ("default".equals(value) || "gold".equals(value) || "nous-light".equals(value)) {
                return "nous";
            }
            return themeName(value) ? value : "nous";
        }

        String mode(String profile) {
            String value = selected(profile, PROFILE_MODES, GLOBAL_MODE);
            return HermesAppearanceClient.mode(value) ? value : "system";
        }

        JSONObject customTheme(String title) {
            try {
                return new JSONObject(values.optString(USER_THEMES, "{}")).optJSONObject(title);
            } catch (JSONException error) {
                return null;
            }
        }

        private String selected(String profile, String perProfile, String global) {
            String fallback = values.optString(global, "");
            try {
                return new JSONObject(values.optString(perProfile, "{}")).optString(profile, fallback);
            } catch (JSONException error) {
                return fallback;
            }
        }

        JSONObject patch(Change change) throws JSONException {
            JSONObject changes = new JSONObject();
            set(changes, change.profile, GLOBAL_THEME, PROFILE_THEMES, change.themeName);
            set(changes, change.profile, GLOBAL_MODE, PROFILE_MODES, change.mode);
            return changes;
        }

        private void set(JSONObject changes, String profile, String global, String perProfile, String input)
                throws JSONException {
            if (input == null) {
                return;
            }
            if ("default".equals(profile)) {
                changes.put(global, input);
            } else {
                // Read the latest profile map before a write. Retain every unrelated member.
                JSONObject profiles = new JSONObject(values.optString(perProfile, "{}"));
                changes.put(perProfile, profiles.put(profile, input).toString());
            }
        }

        Document local(Change change) throws JSONException {
            JSONObject merged = new JSONObject(values.toString());
            JSONObject patch = patch(change);
            Iterator<String> keys = patch.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                merged.put(key, patch.get(key));
            }
            return new Document(merged, revision);
        }

        JSONObject cache() throws JSONException {
            return new JSONObject().put("values", values).put("revision", revision);
        }

        private static JSONObject typedCustomThemes(String raw) throws JSONException {
            JSONObject safe = new JSONObject();
            JSONObject themes;
            try {
                themes = new JSONObject(raw);
            } catch (JSONException error) {
                return safe;
            }
            Iterator<String> names = themes.keys();
            while (safe.length() < MAX_CUSTOM_THEMES && names.hasNext()) {
                String name = names.next();
                JSONObject theme = themes.optJSONObject(name);
                if (!themeName(name) || theme == null) {
                    continue;
                }
                JSONObject colors = colors(theme.optJSONObject("colors"));
                JSONObject darkColors = colors(theme.optJSONObject("darkColors"));
                if (colors != null) {
                    JSONObject palette = new JSONObject().put("colors", colors);
                    if (darkColors != null) {
                        palette.put("darkColors", darkColors);
                    }
                    Object label = theme.opt("label");
                    if (label instanceof String && ((String) label).length() <= 96) {
                        palette.put("label", label);
                    }
                    safe.put(name, palette);
                }
            }
            return safe;
        }

        private static JSONObject colors(JSONObject source) throws JSONException {
            if (source == null) {
                return null;
            }
            JSONObject result = new JSONObject();
            for (String key : new String[]{"background", "card", "foreground", "mutedForeground", "border",
                    "midground", "primary", "ring", "accent", "destructive"}) {
                Object color = source.opt(key);
                if (color instanceof String && ((String) color).matches("(?i)#[0-9a-f]{6}")) {
                    result.put(key, color);
                }
            }
            return result.has("background") && result.has("foreground") ? result : null;
        }
    }
}
