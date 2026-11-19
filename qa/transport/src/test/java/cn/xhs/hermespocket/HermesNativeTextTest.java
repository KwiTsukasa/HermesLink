package cn.xhs.hermespocket;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public final class HermesNativeTextTest {
    private static final Pattern KEY = Pattern.compile("\"(native\\.[a-z0-9_]+)\"");
    private static final Pattern HAN = Pattern.compile("\\p{IsHan}");
    private static final Pattern STATIC_TRANSLATION = Pattern.compile("static final String [A-Z_]+ = t\\(");

    @After
    public void resetNativeCatalog() throws Exception {
        HermesText.install(resource("/native-messages.json"), null);
    }

    @Test
    public void followsOfficialSelectionAndResolvesErrorsAtDisplayTime() throws Exception {
        AtomicReference<String> language = new AtomicReference<>("en");
        HermesText.install(resource("/native-messages.json"), new HermesText.LocaleSource() {
            @Override
            public String languageTag() {
                return language.get();
            }

            @Override
            public String officialText(String locale, String key, Object... values) {
                if ("fr".equals(locale) && "common.back".equals(key)) {
                    return "Retour";
                }
                if ("fr".equals(locale) && "composer.removeAttachment".equals(key)) {
                    return "Retirer " + values[0];
                }
                if ("en".equals(locale) && "updates.connectionSettings".equals(key)) {
                    return "Official connection settings";
                }
                return null;
            }
        });
        HermesText.Failure error = new HermesText.Failure("native.the_attachment_exceeds", 16);
        assertEquals("Back", HermesText.t("native.back"));
        assertEquals("The attachment exceeds 16 MB.", error.getMessage());

        language.set("zh-CN");
        assertEquals("返回", HermesText.t("native.back"));
        assertEquals("附件大小超过 16 MB。", error.getMessage());
        assertEquals("连接设置", HermesText.t("native.connection_settings"));

        language.set("zh-SG");
        assertEquals("连接设置", HermesText.t("native.connection_settings"));

        language.set("fr");
        assertEquals("Retour", HermesText.t("native.back"));
        assertEquals("Retirer report.pdf", HermesText.t("native.remove_attachment", "report.pdf"));
        assertEquals("The attachment exceeds 16 MB.", error.getMessage());
        assertEquals("Official connection settings", HermesText.t("native.connection_settings"));
    }

    @Test
    public void nativeSourceUsesCatalogKeysAndHasNoInlineHanCopy() throws Exception {
        JSONObject nativeMessages = json("/native-messages.json");
        JSONObject officialEnglish = json("/hermes-i18n/locales/en.json");
        Set<String> referenced = new HashSet<>();
        Path main = Path.of("..", "..", "android", "app", "src", "main").normalize();
        for (String name : new String[] { "java", "res" }) {
            try (Stream<Path> paths = Files.walk(main.resolve(name))) {
                for (Path path : paths.filter(Files::isRegularFile)
                        .filter(file -> file.toString().matches(".*\\.(?:java|kt|xml)$")).toList()) {
                    String content = Files.readString(path);
                    // Android launchers resolve shortcut labels from localized resource files.
                    boolean localizedStrings = name.equals("res")
                            && path.getParent().getFileName().toString().startsWith("values-")
                            && path.getFileName().toString().equals("strings.xml");
                    if (!localizedStrings) {
                        assertFalse("Inline Han text in " + path, HAN.matcher(content).find());
                    }
                    assertFalse("Static translation in " + path, STATIC_TRANSLATION.matcher(content).find());
                    Matcher keys = KEY.matcher(content);
                    while (keys.find()) {
                        assertTrue("Missing native message key " + keys.group(1), nativeMessages.has(keys.group(1)));
                        referenced.add(keys.group(1));
                    }
                }
            }
        }
        assertTrue("Native message keys extracted", referenced.size() > 400);
        for (String key : nativeMessages.keySet()) {
            JSONObject message = nativeMessages.getJSONObject(key);
            assertFalse(key, message.getString("en").isBlank());
            assertFalse(key, message.getString("zh-CN").isBlank());
            if (message.has("official")) {
                String officialKey = message.getString("official");
                assertTrue("Missing official message key " + officialKey, officialEnglish.has(officialKey));
            }
        }
    }

    private static JSONObject json(String path) throws Exception {
        try (InputStream input = resource(path)) {
            return new JSONObject(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static InputStream resource(String path) {
        InputStream input = HermesNativeTextTest.class.getResourceAsStream(path);
        assertNotNull(path, input);
        return input;
    }
}
