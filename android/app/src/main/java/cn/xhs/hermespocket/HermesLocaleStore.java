package cn.xhs.hermespocket;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.LocaleList;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** An application-local language preference and the sealed Store translation dictionaries. */
public final class HermesLocaleStore {
    private static final String STORAGE = "hermes_ui_locale";
    private static final String PINNED = "pinned";
    private static final String ENGLISH = "en";
    private static final String ASSETS = "hermes-i18n/";
    private static final int MAX_CATALOG_BYTES = 4 * 1024 * 1024;
    private static final int CACHED_LANGUAGES = 3;
    private static final Pattern PARAMETER = Pattern.compile("\\{([a-zA-Z0-9_]+)\\}");
    private static Map<String, String> languages;
    private static final Map<String, JSONObject> dictionaries = new LinkedHashMap<>(
            CACHED_LANGUAGES + 1, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, JSONObject> entry) {
            return size() > CACHED_LANGUAGES;
        }
    };

    private HermesLocaleStore() {
    }

    static final class Settings {
        final String pinned;
        final String effective;

        Settings(String pinned, String effective) {
            this.pinned = pinned;
            this.effective = effective;
        }

        boolean same(Settings other) {
            return java.util.Objects.equals(pinned, other.pinned) && effective.equals(other.effective);
        }
    }

    public static String languageTag(Context context) {
        return settings(context).effective;
    }

    static Settings settings(Context context) {
        String stored = preferences(context).getString(PINNED, null);
        String pinned = stored == null ? null : resolveTag(context, stored);
        return new Settings(pinned, pinned == null ? deviceLanguage(context) : pinned);
    }

    static Settings setLocale(Context context, String requested) {
        String available = requested == null ? null : resolveTag(context, requested);
        if (requested != null && available == null) {
            throw new IllegalArgumentException("Unsupported app language.");
        }
        SharedPreferences.Editor edit = preferences(context).edit();
        if (available == null) {
            edit.remove(PINNED);
        } else {
            edit.putString(PINNED, available);
        }
        edit.apply();
        return settings(context);
    }

    public static Runnable observe(Context context, Runnable listener) {
        SharedPreferences preferences = preferences(context);
        SharedPreferences.OnSharedPreferenceChangeListener observer = (ignored, key) -> {
            if (PINNED.equals(key)) {
                listener.run();
            }
        };
        preferences.registerOnSharedPreferenceChangeListener(observer);
        return () -> preferences.unregisterOnSharedPreferenceChangeListener(observer);
    }

    /** A missing translation returns null if English has no matching official key either. */
    public static String officialText(Context context, String key, Object... arguments) {
        return officialText(context, key, positionalArguments(arguments));
    }

    static String exactOfficialText(Context context, String locale, String key, Object... arguments) {
        Object message = dictionary(context, locale).opt(key);
        return message instanceof String ? format((String) message, positionalArguments(arguments)) : null;
    }

    private static Map<String, Object> positionalArguments(Object[] arguments) {
        Map<String, Object> parameters = new HashMap<>();
        for (int index = 0; index < arguments.length; index++) {
            parameters.put(Integer.toString(index), arguments[index]);
        }
        return parameters;
    }

    public static String officialText(Context context, String key, Map<String, ?> arguments) {
        return officialTextAt(context, languageTag(context), key, arguments);
    }

    static String officialTextAt(Context context, String locale, String key, Map<String, ?> arguments) {
        Object message = dictionary(context, locale).opt(key);
        if (!(message instanceof String) && !ENGLISH.equals(locale)) {
            message = dictionary(context, ENGLISH).opt(key);
        }
        return message instanceof String ? format((String) message, arguments) : null;
    }

    private static String format(String template, Map<String, ?> arguments) {
        if (arguments.isEmpty()) {
            return template;
        }
        Matcher matcher = PARAMETER.matcher(template);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            Object value = arguments.get(matcher.group(1));
            matcher.appendReplacement(result, Matcher.quoteReplacement(
                    value == null ? matcher.group() : String.valueOf(value)));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(STORAGE, Context.MODE_PRIVATE);
    }

    private static String deviceLanguage(Context context) {
        LocaleList preferred = LocaleList.getAdjustedDefault();
        for (int index = 0; index < preferred.size(); index++) {
            String supported = resolveTag(context, preferred.get(index).toLanguageTag());
            if (supported != null) {
                return supported;
            }
        }
        return ENGLISH;
    }

    private static String resolveTag(Context context, String input) {
        String normalized = input.trim().replace('_', '-').toLowerCase(Locale.ROOT);
        Map<String, String> known = languages(context);
        String exact = known.get(normalized);
        if (exact != null) {
            return exact;
        }
        if (normalized.matches("zh-(tw|hk|mo)(-.*)?") || normalized.startsWith("zh-hant-")) {
            return known.get("zh-hant");
        }
        int separator = normalized.indexOf('-');
        String family = separator < 0 ? normalized : normalized.substring(0, separator);
        return known.get(family);
    }

    private static synchronized Map<String, String> languages(Context context) {
        if (languages == null) {
            Map<String, String> supported = new HashMap<>();
            supported.put(ENGLISH, ENGLISH);
            try {
                JSONArray records = new JSONArray(asset(context, "locales.json"));
                for (int index = 0; index < records.length(); index++) {
                    JSONObject row = records.getJSONObject(index);
                    String tag = row.getString("id");
                    supported.put(tag.toLowerCase(Locale.ROOT), tag);
                    supported.put(row.getString("upstream"), tag);
                }
            } catch (IOException | JSONException ignored) {
                // A missing bundled catalog leaves the default language available.
            }
            languages = Collections.unmodifiableMap(supported);
        }
        return languages;
    }

    private static synchronized JSONObject dictionary(Context context, String language) {
        JSONObject loaded = dictionaries.get(language);
        if (loaded == null) {
            try {
                loaded = new JSONObject(asset(context, "locales/" + language + ".json"));
            } catch (IOException | JSONException ignored) {
                loaded = new JSONObject();
            }
            dictionaries.put(language, loaded);
        }
        return loaded;
    }

    private static String asset(Context context, String name) throws IOException {
        try (InputStream input = context.getAssets().open(ASSETS + name)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int length; (length = input.read(buffer)) != -1;) {
                if (length > MAX_CATALOG_BYTES - output.size()) {
                    throw new IOException("App locale catalog is too large.");
                }
                output.write(buffer, 0, length);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
