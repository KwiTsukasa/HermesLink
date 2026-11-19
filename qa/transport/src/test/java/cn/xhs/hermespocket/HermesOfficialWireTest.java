package cn.xhs.hermespocket;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.HttpUrl;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class HermesOfficialWireTest {
    private static final String REMOTE_ID = "fixture-session-token_1";
    private static final String PROFILE = "gateway-profile";
    private static final String CODE = "ABCD-1234";

    @Test
    public void usesOnlyFixedOfficialRoutesAtTheSavedOriginAndPrefix() {
        String base = "https://hermes.example:8443/mobile";
        assertEquals(base + "/#/settings", HermesOfficialClient.Destination.SETTINGS.url(base).toString());
        assertEquals(base + "/#/settings?tab=providers&pview=accounts",
                HermesOfficialClient.Destination.PROVIDERS.url(base).toString());
        assertEquals(base + "/#/capabilities?tab=connectors",
                HermesOfficialClient.Destination.MCP.url(base).toString());
        assertEquals(base + "/#/settings?tab=gateway", HermesOfficialClient.Destination.RELAY.url(base).toString());
        assertEquals(base + "/#/messaging", HermesOfficialClient.Destination.MESSAGING.url(base).toString());
        assertEquals(base + "/", HermesOfficialClient.Destination.LIVE_VOICE.url(base).toString());
        assertEquals(base + "/", HermesOfficialClient.Destination.GROUPS.url(base).toString());
        assertFalse(HermesOfficialClient.Destination.LIVE_VOICE.direct());
        assertNull(HermesOfficialClient.Destination.find("https://wrong.example/"));
        assertThrows(IllegalArgumentException.class,
                () -> HermesOfficialClient.Destination.SETTINGS.url(base + "?token=fixture"));
    }

    @Test(timeout = 20000)
    public void readsSeparateLoginAndModelProviderCatalogs() throws Exception {
        try (Gateway gateway = new Gateway()) {
            List<HermesOfficialClient.SignInOption> signIn =
                    gateway.client.signInOptions(new HermesClient.RequestRead());
            assertEquals(1, signIn.size());
            assertEquals("nous", signIn.get(0).id);
            assertFalse(signIn.get(0).supportsPassword);
            List<HermesOfficialClient.ProviderStatus> providers =
                    gateway.client.providers(new HermesClient.RequestRead());
            assertEquals(2, providers.size());
            assertEquals("openai-codex", providers.get(0).id);
            assertEquals(Boolean.FALSE, providers.get(0).connected);
            assertTrue(providers.get(0).deviceFlowAvailable);
            assertFalse(providers.get(1).deviceFlowAvailable);
            assertEquals(List.of("GET auth/providers", "GET profiles/active", "GET providers/oauth"),
                    gateway.calls);
        }
    }

    @Test(timeout = 20000)
    public void keepsProviderAndProfileFixedThroughStartPollAndCancel() throws Exception {
        try (Gateway gateway = new Gateway()) {
            assertThrows(IOException.class,
                    () -> gateway.client.begin("../auth/unsafe", new HermesClient.RequestRead()));
            assertTrue(gateway.calls.isEmpty());
            HermesOfficialClient.ProviderLogin flow =
                    gateway.client.begin("openai-codex", new HermesClient.RequestRead());
            assertEquals(CODE, flow.userCode);
            assertEquals("https://auth.openai.com/codex/device", flow.verification.toString());
            assertEquals(5, flow.interval);
            assertEquals("pending", gateway.client.poll(flow, new HermesClient.RequestRead()));
            gateway.pollSession = "foreign-session-token";
            assertThrows(IOException.class,
                    () -> gateway.client.poll(flow, new HermesClient.RequestRead()));
            gateway.pollSession = REMOTE_ID;
            gateway.state = "approved";
            assertEquals("approved", gateway.client.poll(flow, new HermesClient.RequestRead()));
            gateway.client.cancel(flow, new HermesClient.RequestRead());
            assertEquals(List.of("GET profiles/active", "POST providers/oauth/openai-codex/start",
                    "GET providers/oauth/openai-codex/poll/" + REMOTE_ID,
                    "GET providers/oauth/openai-codex/poll/" + REMOTE_ID,
                    "GET providers/oauth/openai-codex/poll/" + REMOTE_ID,
                    "DELETE providers/oauth/sessions/" + REMOTE_ID), gateway.calls);
        }
    }

    @Test(timeout = 20000)
    public void refusesUnverifiedBrowserTargetAndCancelsItsServerSession() throws Exception {
        try (Gateway gateway = new Gateway()) {
            gateway.verification = "https://portal.nousresearch.com/manage-subscription?user_code="
                    + CODE + "&next=https://wrong.example";
            assertThrows(IOException.class,
                    () -> gateway.client.begin("nous", new HermesClient.RequestRead()));
            gateway.verification = "https://portal.nousresearch.com/manage-subscription?user_code=OTHER";
            assertThrows(IOException.class,
                    () -> gateway.client.begin("nous", new HermesClient.RequestRead()));
            gateway.verification = "https://portal.nousresearch.com/manage-subscription?user_code=" + CODE;
            HermesOfficialClient.ProviderLogin flow =
                    gateway.client.begin("nous", new HermesClient.RequestRead());
            assertEquals(gateway.verification, flow.verification.toString());
            long cancellations = gateway.calls.stream().filter(value -> value.startsWith("DELETE")).count();
            assertEquals(2, cancellations);
        }
    }

    @Test(timeout = 20000)
    public void doesNotFollowRestRedirectsOrForwardNativeCredentials() throws Exception {
        try (Gateway gateway = new Gateway(); MockWebServer other = new MockWebServer()) {
            other.start();
            gateway.redirect = other.url("/steal").toString();
            assertEquals(307, assertThrows(HermesClient.HttpException.class,
                    () -> gateway.client.signInOptions(new HermesClient.RequestRead())).statusCode);
            assertEquals(0, other.getRequestCount());
        }
    }

    private static final class Gateway implements AutoCloseable {
        private final MockWebServer server = new MockWebServer();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        private final String origin;
        private final HermesOfficialClient client;
        private volatile String verification = "https://auth.openai.com/codex/device";
        private volatile String state = "pending";
        private volatile String pollSession = REMOTE_ID;
        private volatile String redirect;

        Gateway() throws IOException {
            server.start();
            origin = server.url("/").toString().replaceFirst("/$", "");
            client = new HermesOfficialClient(origin + "/pocket", new HermesClient.Auth() {
                @Override
                public String cookieHeader(String url) {
                    return null;
                }

                @Override
                public String accessToken(String url) {
                    assertTrue(url.startsWith(origin + "/pocket/api/"));
                    return "fixture-login";
                }
            });
            server.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) {
                    try {
                        return route(request);
                    } catch (RuntimeException | AssertionError error) {
                        failure.compareAndSet(null, error);
                        return new MockResponse().setResponseCode(500);
                    }
                }
            });
        }

        private MockResponse route(RecordedRequest request) {
            HttpUrl url = request.getRequestUrl();
            assertNotNull(url);
            assertEquals(origin, request.getHeader("Origin"));
            assertEquals("Bearer fixture-login", request.getHeader("Authorization"));
            assertTrue(url.encodedPath().startsWith("/pocket/api/"));
            String path = url.encodedPath().substring("/pocket/api/".length());
            String method = request.getMethod();
            calls.add(method + " " + path);
            if ("profiles/active".equals(path)) {
                assertNull(url.query());
                return json(new JSONObject().put("current", PROFILE).put("active", "unrelated-sticky-profile"));
            }
            if ("auth/providers".equals(path)) {
                assertNull(url.query());
                if (redirect != null) {
                    return new MockResponse().setResponseCode(307).setHeader("Location", redirect);
                }
                return json(new JSONObject().put("providers", new JSONArray().put(new JSONObject()
                        .put("name", "nous").put("display_name", "Nous").put("supports_password", false))));
            }
            assertEquals(PROFILE, url.queryParameter("profile"));
            if ("providers/oauth".equals(path)) {
                JSONArray providers = new JSONArray();
                providers.put(new JSONObject().put("id", "openai-codex").put("name", "Codex")
                        .put("flow", "device_code").put("status", new JSONObject()
                                .put("logged_in", false).put("token_preview", "private suffix")));
                providers.put(new JSONObject().put("id", "anthropic").put("name", "Anthropic")
                        .put("flow", "external"));
                return json(new JSONObject().put("providers", providers));
            }
            if (path.matches("providers/oauth/(?:openai-codex|nous)/start")) {
                assertEquals("POST", method);
                assertEquals(0L, request.getBodySize());
                return json(new JSONObject().put("session_id", REMOTE_ID).put("flow", "device_code")
                        .put("user_code", CODE).put("verification_url", verification)
                        .put("expires_in", 899).put("poll_interval", 5));
            }
            if (path.equals("providers/oauth/openai-codex/poll/" + REMOTE_ID)) {
                assertEquals("GET", method);
                return json(new JSONObject().put("session_id", pollSession).put("status", state)
                        .put("error_message", "fixture private detail"));
            }
            if (path.equals("providers/oauth/sessions/" + REMOTE_ID)) {
                assertEquals("DELETE", method);
                return json(new JSONObject().put("ok", true).put("session_id", REMOTE_ID));
            }
            throw new AssertionError("Unexpected official route " + path);
        }

        private static MockResponse json(JSONObject object) {
            return new MockResponse().setHeader("Content-Type", "application/json")
                    .setBody(object.toString());
        }

        @Override
        public void close() throws Exception {
            server.close();
            if (failure.get() != null) {
                throw new AssertionError("Unexpected official request", failure.get());
            }
        }
    }
}
