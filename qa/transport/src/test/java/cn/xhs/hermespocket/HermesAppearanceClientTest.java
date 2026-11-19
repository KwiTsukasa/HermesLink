package cn.xhs.hermespocket;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import java.net.InetAddress;
import java.util.concurrent.TimeUnit;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;

public final class HermesAppearanceClientTest {
    @Test(timeout = 15000)
    public void updatesOneOfficialProfileMapWithFreshSiblingPreferencesIntact() throws Exception {
        try (MockWebServer mock = server()) {
            HermesAppearanceClient transport = new HermesAppearanceClient(client(mock));
            JSONObject values = new JSONObject().put("hermes-desktop-theme-v2", "nous")
                    .put("hermes-desktop-mode-v1", "system")
                    .put("hermes-desktop-profile-themes-v1", "{\"research\":\"slate\",\"elsewhere\":\"github\"}")
                    .put("hermes.desktop.unrelated", "preserve-me");
            mock.enqueue(document(values, 5));
            HermesAppearanceClient.Document before = transport.read(new HermesClient.RequestRead());
            assertEquals("slate", before.theme("research"));
            assertEquals("nous", before.theme("default"));
            assertFalse(before.cache().toString().contains("preserve-me"));

            values.put("hermes-desktop-profile-themes-v1", "{\"research\":\"ember\",\"elsewhere\":\"github\"}");
            mock.enqueue(document(values, 6));
            HermesAppearanceClient.Document after = transport.update(before,
                    new HermesAppearanceClient.Change("research", "ember", null), new HermesClient.RequestRead());
            assertEquals("ember", after.theme("research"));
            assertEquals("github", after.theme("elsewhere"));
            assertEquals("nous", after.theme("default"));
            assertEquals(6, after.revision);

            RecordedRequest read = request(mock);
            RecordedRequest write = request(mock);
            assertEquals("GET", read.getMethod());
            assertEquals("PATCH", write.getMethod());
            assertEquals("/webapp/api/ui-preferences", read.getPath());
            assertEquals(read.getPath(), write.getPath());
            assertEquals("Bearer fixture_bearer", write.getHeader("Authorization"));
            assertEquals("http://127.0.0.1:" + mock.getPort(), write.getHeader("Origin"));
            JSONObject changed = new JSONObject(write.getBody().readUtf8()).getJSONObject("values");
            assertEquals(1, changed.length());
            JSONObject profileThemes = new JSONObject(changed.getString("hermes-desktop-profile-themes-v1"));
            assertEquals("ember", profileThemes.getString("research"));
            assertEquals("github", profileThemes.getString("elsewhere"));
        }
    }

    @Test(timeout = 15000)
    public void keepsOnlyNumericCustomPaletteValuesAndRejectsMalformedProfileWrites() throws Exception {
        try (MockWebServer mock = server()) {
            HermesAppearanceClient transport = new HermesAppearanceClient(client(mock));
            JSONObject palette = new JSONObject().put("background", "#FFFFFF").put("foreground", "#113355")
                    .put("accent", "url(https://fixture.invalid/ignored)");
            JSONObject custom = new JSONObject().put("fixture", new JSONObject().put("label", "Custom fixture")
                    .put("colors", palette).put("customCSS", "fixture_css"));
            JSONObject values = new JSONObject().put("hermes-desktop-user-themes-v1", custom.toString())
                    .put("hermes-desktop-profile-modes-v1", "broken map");
            mock.enqueue(document(values, 2));
            HermesAppearanceClient.Document before = transport.read(new HermesClient.RequestRead());
            JSONObject saved = before.customTheme("fixture");
            assertEquals("#FFFFFF", saved.getJSONObject("colors").getString("background"));
            assertFalse(saved.toString().contains("fixture_css"));
            assertFalse(saved.toString().contains("fixture.invalid"));
            assertThrows(JSONException.class, () -> transport.update(before,
                    new HermesAppearanceClient.Change("research", null, "dark"), new HermesClient.RequestRead()));
            assertEquals(1, mock.getRequestCount());
            assertThrows(IllegalArgumentException.class,
                    () -> new HermesAppearanceClient.Change("research", null, "inverted"));
        }
    }

    private static MockResponse document(JSONObject values, int revision) {
        return new MockResponse().setBody(new JSONObject().put("exists", true).put("values", values)
                .put("revision", revision).toString());
    }

    private static MockWebServer server() throws Exception {
        MockWebServer mock = new MockWebServer();
        mock.start(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 0);
        return mock;
    }

    private static HermesClient client(MockWebServer mock) {
        return new HermesClient("http://127.0.0.1:" + mock.getPort() + "/webapp/", new HermesClient.Auth() {
            @Override
            public String cookieHeader(String url) {
                return null;
            }

            @Override
            public String accessToken(String url) {
                return "fixture_bearer";
            }
        });
    }

    private static RecordedRequest request(MockWebServer mock) throws InterruptedException {
        RecordedRequest next = mock.takeRequest(2, TimeUnit.SECONDS);
        assertNotNull(next);
        return next;
    }
}
