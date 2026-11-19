package cn.xhs.hermespocket;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Localized native copy with optional lookup in the official Hermes catalog. */
final class HermesText {
    private static final String ASSET = "native-messages.json";
    private static volatile JSONObject messages;
    private static volatile LocaleSource source;

    private HermesText() {}

    interface LocaleSource {
        String languageTag();
        String officialText(String locale, String key, Object... values);
    }

    static void install(InputStream input, LocaleSource localeSource) throws IOException, JSONException {
        messages = readMessages(input);
        source = localeSource;
    }

    static String languageTag() {
        LocaleSource current = source;
        return current == null ? Locale.getDefault().toLanguageTag() : current.languageTag();
    }

    static Locale locale() {
        return Locale.forLanguageTag(languageTag());
    }

    static String t(String key, Object... values) {
        JSONObject row = catalog().optJSONObject(key);
        if (row == null) {
            throw new IllegalArgumentException("Missing native message key: " + key);
        }
        LocaleSource current = source;
        String tag = current == null ? Locale.getDefault().toLanguageTag() : current.languageTag();
        String officialKey = row.optString("official");
        boolean hasOfficialText = current != null && !officialKey.isEmpty();
        if (hasOfficialText) {
            String official = current.officialText(tag, officialKey, values);
            if (official != null && !official.isEmpty()) {
                return official;
            }
        }
        String template = row.optString(tag);
        if (template.isEmpty() && tag.startsWith("zh-")
                && !tag.contains("Hant") && !tag.endsWith("-TW")
                && !tag.endsWith("-HK") && !tag.endsWith("-MO")) {
            template = row.optString("zh-CN");
        }
        if (template.isEmpty() && hasOfficialText && !"en".equals(tag)) {
            String english = current.officialText("en", officialKey, values);
            if (english != null && !english.isEmpty()) {
                return english;
            }
        }
        if (template.isEmpty()) {
            template = row.optString("en");
        }
        return values.length == 0 ? template : String.format(Locale.forLanguageTag(tag), template, values);
    }

    private static synchronized JSONObject catalog() {
        if (messages == null) {
            try (InputStream input = HermesText.class.getResourceAsStream("/" + ASSET)) {
                if (input == null) {
                    throw new IOException("Native messages resource is missing");
                }
                messages = readMessages(input);
            } catch (IOException | JSONException error) {
                throw new IllegalStateException("Could not load native messages", error);
            }
        }
        return messages;
    }

    private static JSONObject readMessages(InputStream input) throws IOException, JSONException {
        try (InputStream data = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] bytes = new byte[8192];
            int count;
            while ((count = data.read(bytes)) >= 0) {
                if (output.size() + count > 1024 * 1024) {
                    throw new IOException("Native messages resource is too large");
                }
                output.write(bytes, 0, count);
            }
            return new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
        }
    }

    interface MessageError {}

    static class Failure extends IOException implements MessageError {
        private final String key;
        private final Object[] values;

        Failure(String key, Object... values) {
            this(null, key, values);
        }

        Failure(Throwable cause, String key, Object... values) {
            super(cause);
            this.key = key;
            this.values = values;
        }

        @Override
        public String getMessage() {
            return t(key, values);
        }
    }

    static final class InvalidInput extends IllegalArgumentException implements MessageError {
        private final String key;
        private final Object[] values;

        InvalidInput(String key, Object... values) {
            this.key = key;
            this.values = values;
        }

        @Override
        public String getMessage() {
            return t(key, values);
        }
    }
}
