package cn.xhs.hermespocket;

import org.junit.Test;

import okhttp3.HttpUrl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class OfficialWebappOriginTest {
    private final OfficialWebappOrigin configured = new OfficialWebappOrigin("https://gateway.test:8443/hermes");

    @Test
    public void boundsOfficialDocumentsToConfiguredOriginAndPrefix() {
        assertEquals("https://gateway.test:8443/hermes/", configured.address());
        assertTrue(configured.contains(url("https://gateway.test:8443/hermes/api/status")));
        assertFalse(configured.contains(url("https://gateway.test:8443/hermes-elsewhere/")));
        assertFalse(configured.contains(url("https://gateway.test:8443/hermes/%2e%2e/")));
        assertFalse(configured.contains(url("https://gateway.test/hermes/")));
        assertFalse(configured.contains(url("http://gateway.test:8443/hermes/")));
        assertFalse(configured.contains(url("https://portal.nousresearch.com/")));
        assertTrue(configured.isUiDocument(url("https://gateway.test:8443/hermes/#/settings")));
        assertTrue(configured.isUiDocument(url("https://gateway.test:8443/hermes/index.html")));
        assertFalse(configured.isUiDocument(url("https://gateway.test:8443/hermes/login")));
        assertFalse(configured.isUiDocument(url("https://gateway.test:8443/hermes/api/status")));
    }

    @Test
    public void differentiatesLoginAndContextWithoutConfusingHashRoutes() {
        HttpUrl settings = url("https://gateway.test:8443/hermes/#/settings");
        assertTrue(configured.sameDocument(settings, url("https://gateway.test:8443/hermes/#/chat")));
        assertFalse(configured.sameDocument(settings, url("https://gateway.test:8443/hermes/?tenant=other")));
        assertTrue(configured.isLoginDocument(url("https://gateway.test:8443/hermes/auth/login?provider=nous")));
        assertTrue(configured.isLoginDocument(url("https://gateway.test:8443/hermes/auth/callback")));
        assertFalse(configured.isLoginDocument(url("https://external.test/hermes/auth/login")));
    }

    @Test
    public void allowsExternalHttpsRedirectOnlyDuringTheLoginFlow() {
        HttpUrl portal = url("https://portal.nousresearch.com/oauth/authorize?state=example");
        assertFalse(configured.allowsMainFrame(portal, false));
        assertTrue(configured.allowsMainFrame(portal, true));
        assertFalse(configured.allowsMainFrame(url("http://portal.nousresearch.com/oauth/authorize"), true));
        assertFalse(configured.allowsMainFrame(url("intent://oauth/authorize"), true));
        assertTrue(configured.allowsMainFrame(url("https://gateway.test:8443/hermes/auth/callback"), true));
        assertTrue(configured.allowsMainFrame(url("https://gateway.test:8443/hermes/"), false));
    }

    @Test
    public void recognizesOnlyConfiguredCallbackHttpFailuresDuringSignIn() {
        HttpUrl callback = url("https://gateway.test:8443/hermes/auth/callback?error=access_denied");
        assertTrue(configured.isLoginCallbackFailure(callback, true, 400));
        assertTrue(configured.isLoginCallbackFailure(callback, true, 403));
        assertTrue(configured.isLoginCallbackFailure(callback, true, 502));
        assertFalse(configured.isLoginCallbackFailure(callback, false, 400));
        assertFalse(configured.isLoginCallbackFailure(callback, true, 200));
        assertFalse(configured.isLoginCallbackFailure(callback, true, 302));
        assertFalse(configured.isLoginCallbackFailure(
                url("https://gateway.test:8443/hermes/auth/password-login"), true, 401));
        assertFalse(configured.isLoginCallbackFailure(
                url("https://portal.nousresearch.com/hermes/auth/callback"), true, 400));
        assertFalse(configured.isLoginCallbackFailure(
                url("https://gateway.test:8443/hermes-elsewhere/auth/callback"), true, 400));
        assertFalse(configured.isLoginCallbackFailure(
                url("https://gateway.test:8443/hermes/api/mcp/oauth/callback/service"), true, 400));
    }

    @Test
    public void permitsDeviceMediaOnlyForTheExactSecureOrigin() {
        assertTrue(configured.canGrantMedia(url("https://gateway.test:8443")));
        assertFalse(configured.canGrantMedia(url("https://gateway.test")));
        assertFalse(configured.canGrantMedia(url("https://external.test:8443")));
        OfficialWebappOrigin privateHttp = new OfficialWebappOrigin("http://100.100.100.100:9120");
        assertFalse(privateHttp.canGrantMedia(url("http://100.100.100.100:9120")));
    }

    @Test
    public void acceptsOnlyNormalWebUrlsWithoutUserInfo() {
        assertNull(url("file:///android_asset/index.html"));
        assertNull(url("content://docs/test"));
        assertNull(url("javascript:alert(1)"));
        assertNull(url("intent://unsafe"));
        assertNull(url("https://credential@gateway.test:8443/hermes/"));
        assertEquals("external.test", url("https://external.test/").host());
    }

    private static HttpUrl url(String address) {
        return OfficialWebappOrigin.browserUrl(address);
    }
}
