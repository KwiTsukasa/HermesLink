package cn.xhs.hermespocket;

import static cn.xhs.hermespocket.HermesText.t;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Fixed Webapp destinations and the official model-provider device flow. */
final class HermesOfficialClient {
    private static final int MAX_RESPONSE = 256 * 1024;
    private final HttpUrl base;
    private final HermesClient client;

    HermesOfficialClient(String address, HermesClient.Auth auth) {
        base = HttpUrl.get(HermesClient.normalizeBaseUrl(address));
        client = new HermesClient(base.toString(), auth);
    }

    enum Destination {
        SETTINGS("settings", "native.official_webapp_settings", "/settings",
                "native.manage_hermes_settings_in_the_official_webapp"),
        PROVIDERS("providers", "native.model_provider_accounts", "/settings?tab=providers&pview=accounts",
                "native.connect_and_manage_provider_accounts_in_settings"),
        LIVE_VOICE("liveVoice", "native.gpt_live_voice_chat", null,
                "native.after_signing_in_open_a_conversation_and_turn_on_voice"),
        MCP("mcp", "native.mcp_and_external_connectors", "/capabilities?tab=connectors",
                "native.select_the_mcp_server_on_the_connectors_page_and_complete"),
        RELAY("relay", "native.gateways_and_remote_connections", "/settings?tab=gateway",
                "native.add_or_switch_connections_on_the_official_webapp_gateways_page"),
        MESSAGING("messaging", "native.messaging_platforms_and_pairing", "/messaging",
                "native.continue_authentication_and_pairing_on_the_official_webapp_messaging_page"),
        GROUPS("groups", "native.group_chats", null, "native.after_signing_in_open_group_chats_in_the_chat_sidebar");

        final String id;
        final String titleKey;
        final String guidanceKey;
        private final String fragment;

        Destination(String id, String titleKey, String fragment, String guidanceKey) {
            this.id = id;
            this.titleKey = titleKey;
            this.fragment = fragment;
            this.guidanceKey = guidanceKey;
        }

        boolean direct() {
            return fragment != null;
        }

        static Destination find(String id) {
            for (Destination destination : values()) {
                if (destination.id.equals(id)) {
                    return destination;
                }
            }
            return null;
        }

        HttpUrl url(String connection) {
            // The installed Webapp uses HashRouter at its served base, including behind a prefix.
            return HttpUrl.get(HermesClient.normalizeBaseUrl(connection)).newBuilder()
                    .fragment(fragment).build();
        }
    }

    private enum Provider {
        NOUS("nous"), CODEX("openai-codex"), MINIMAX("minimax-oauth"), XAI("xai-oauth");

        final String id;

        Provider(String id) {
            this.id = id;
        }

        static Provider find(String id) {
            for (Provider provider : values()) {
                if (provider.id.equals(id)) {
                    return provider;
                }
            }
            return null;
        }

        boolean verificationRoute(HttpUrl url) {
            String host = url.host();
            String path = url.encodedPath();
            switch (this) {
                case NOUS:
                    return "portal.nousresearch.com".equals(host)
                            && ("/manage-subscription".equals(path) || "/device".equals(path));
                case CODEX:
                    return "auth.openai.com".equals(host) && "/codex/device".equals(path);
                case MINIMAX:
                    return ("platform.minimax.io".equals(host) || "platform.minimaxi.com".equals(host))
                            && "/oauth-authorize".equals(path);
                case XAI:
                    return "accounts.x.ai".equals(host) && "/oauth2/device".equals(path);
                default:
                    return false;
            }
        }
    }

    static final class ProviderStatus {
        final String id;
        final String label;
        final Boolean connected;
        final boolean deviceFlowAvailable;

        ProviderStatus(String id, String label, Boolean connected, boolean deviceFlowAvailable) {
            this.id = id;
            this.label = label;
            this.connected = connected;
            this.deviceFlowAvailable = deviceFlowAvailable;
        }
    }

    static final class SignInOption {
        final String id;
        final String label;
        final boolean supportsPassword;

        SignInOption(String id, String label, boolean supportsPassword) {
            this.id = id;
            this.label = label;
            this.supportsPassword = supportsPassword;
        }
    }

    static final class ProviderLogin {
        private final Provider provider;
        private final String profile;
        private final String remoteId;
        final String userCode;
        final HttpUrl verification;
        final int expiresIn;
        final int interval;

        ProviderLogin(Provider provider, String profile, String remoteId, String userCode,
                      HttpUrl verification, int expiresIn, int interval) {
            this.provider = provider;
            this.profile = profile;
            this.remoteId = remoteId;
            this.userCode = userCode;
            this.verification = verification;
            this.expiresIn = expiresIn;
            this.interval = interval;
        }
    }

    static boolean allowsDeviceLogin(String provider) {
        return Provider.find(provider) != null;
    }

    List<ProviderStatus> providers(HermesClient.RequestRead read) throws IOException, JSONException {
        String profile = currentProfile(read);
        JSONArray rows = request("GET", profileUrl("api/providers/oauth", profile), read)
                .getJSONArray("providers");
        List<ProviderStatus> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < rows.length(); index++) {
            JSONObject row = rows.optJSONObject(index);
            String id = field(row, "id");
            if (!validSelector(id) || !seen.add(id)) {
                continue;
            }
            JSONObject status = row.optJSONObject("status");
            Object signedIn = status == null ? null : status.opt("logged_in");
            result.add(new ProviderStatus(id, label(field(row, "name"), id),
                    signedIn instanceof Boolean ? (Boolean) signedIn : null,
                    allowsDeviceLogin(id) && "device_code".equals(field(row, "flow"))));
        }
        return result;
    }

    List<SignInOption> signInOptions(HermesClient.RequestRead read) throws IOException, JSONException {
        JSONArray rows = request("GET", base.resolve("api/auth/providers"), read).getJSONArray("providers");
        List<SignInOption> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < rows.length(); index++) {
            JSONObject row = rows.optJSONObject(index);
            String id = field(row, "name");
            if (validSelector(id) && seen.add(id)) {
                result.add(new SignInOption(id, label(field(row, "display_name"), id),
                        Boolean.TRUE.equals(row.opt("supports_password"))));
            }
        }
        return result;
    }

    ProviderLogin begin(String providerId, HermesClient.RequestRead read)
            throws IOException, JSONException {
        Provider provider = Provider.find(providerId);
        if (provider == null) {
            throw new ProtocolFailure("native.manage_this_provider_authorization_in_the_official_webapp");
        }
        String profile = currentProfile(read);
        HttpUrl endpoint = base.resolve("api/providers/oauth/").newBuilder()
                .addPathSegment(provider.id).addPathSegment("start")
                .addQueryParameter("profile", profile).build();
        JSONObject result = request("POST", endpoint, read);
        String remoteId = field(result, "session_id");
        if (!remoteId.matches("[A-Za-z0-9_-]{16,128}")) {
            throw invalidReply();
        }
        try {
            String code = field(result, "user_code");
            if (!"device_code".equals(field(result, "flow")) || !code.matches("[A-Za-z0-9-]{4,32}")) {
                throw invalidReply();
            }
            HttpUrl verification = verificationUrl(provider, field(result, "verification_url"), code);
            int expiresIn = positiveInt(result, "expires_in");
            int interval = positiveInt(result, "poll_interval");
            return new ProviderLogin(provider, profile, remoteId, code, verification,
                    Math.min(expiresIn, 15 * 60), Math.max(interval, 2));
        } catch (ProtocolFailure error) {
            // The server created a session before returning the verification metadata.
            // Stop that exact session if its URL or protocol cannot be presented safely.
            try {
                cancel(profile, remoteId, new HermesClient.RequestRead());
            } catch (IOException | JSONException ignored) {
                // Hermes expires the unpresented flow even if its DELETE fails.
            }
            throw error;
        }
    }

    String poll(ProviderLogin login, HermesClient.RequestRead read) throws IOException, JSONException {
        HttpUrl endpoint = base.resolve("api/providers/oauth/").newBuilder()
                .addPathSegment(login.provider.id).addPathSegment("poll").addPathSegment(login.remoteId)
                .addQueryParameter("profile", login.profile).build();
        JSONObject result;
        try {
            result = request("GET", endpoint, read);
        } catch (HermesClient.HttpException error) {
            if (error.statusCode == 404) {
                return "expired";
            }
            throw error;
        }
        if (!login.remoteId.equals(field(result, "session_id"))) {
            throw invalidReply();
        }
        String state = field(result, "status");
        switch (state) {
            case "pending":
            case "approved":
            case "denied":
            case "expired":
            case "cancelled":
            case "error":
                return state;
            default:
                throw invalidReply();
        }
    }

    void cancel(ProviderLogin login, HermesClient.RequestRead read) throws IOException, JSONException {
        cancel(login.profile, login.remoteId, read);
    }

    private void cancel(String profile, String id, HermesClient.RequestRead read)
            throws IOException, JSONException {
        HttpUrl endpoint = base.resolve("api/providers/oauth/sessions/").newBuilder()
                .addPathSegment(id).addQueryParameter("profile", profile).build();
        JSONObject result = request("DELETE", endpoint, read);
        if (Boolean.TRUE.equals(result.opt("ok")) && id.equals(field(result, "session_id"))) {
            return;
        }
        if (Boolean.FALSE.equals(result.opt("ok")) && "session not found".equals(field(result, "message"))) {
            return; // Already expired or already cancelled.
        }
        throw invalidReply();
    }

    private String currentProfile(HermesClient.RequestRead read) throws IOException, JSONException {
        String profile = field(request("GET", base.resolve("api/profiles/active"), read), "current");
        if (!validSelector(profile) || "current".equalsIgnoreCase(profile)) {
            throw new ProtocolFailure("native.could_not_identify_the_hermes_profile_used_for_provider_authorization");
        }
        return profile;
    }

    private HttpUrl profileUrl(String path, String profile) {
        return base.resolve(path).newBuilder().addQueryParameter("profile", profile).build();
    }

    private JSONObject request(String method, HttpUrl url, HermesClient.RequestRead read)
            throws IOException, JSONException {
        try (Response response = client.executeOfficialRequest(url, method, read)) {
            ResponseBody body = response.body();
            MediaType media = body == null ? null : body.contentType();
            if (media == null || !"application".equals(media.type())
                    || !"json".equals(media.subtype()) || body.source().request(MAX_RESPONSE + 1L)) {
                throw invalidReply();
            }
            return new JSONObject(body.source().readUtf8());
        }
    }

    private static HttpUrl verificationUrl(Provider provider, String value, String code)
            throws ProtocolFailure {
        HttpUrl url = value.length() > 1024 ? null : HttpUrl.parse(value);
        if (url == null || !url.isHttps() || url.port() != 443 || url.fragment() != null
                || !url.encodedUsername().isEmpty() || !url.encodedPassword().isEmpty()
                || !provider.verificationRoute(url)) {
            throw unverifiedUrl();
        }
        HttpUrl.Builder verified = url.newBuilder().query(null);
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < url.querySize(); i++) {
            String name = url.queryParameterName(i);
            String parameter = url.queryParameterValue(i);
            if (!keys.add(name)) {
                throw unverifiedUrl();
            }
            if ("user_code".equals(name) && provider != Provider.CODEX
                    && code.equalsIgnoreCase(parameter)) {
                verified.addQueryParameter(name, parameter);
            } else if ("client".equals(name) && provider == Provider.MINIMAX
                    && parameter != null && parameter.matches("[A-Za-z0-9._-]{1,60}")) {
                verified.addQueryParameter(name, parameter);
            } else {
                throw unverifiedUrl();
            }
        }
        return verified.build();
    }

    private static int positiveInt(JSONObject object, String key) throws ProtocolFailure {
        Object raw = object.opt(key);
        long value;
        if (raw instanceof Integer || raw instanceof Long) {
            value = ((Number) raw).longValue();
        } else {
            throw invalidReply();
        }
        if (value <= 0 || value > 86400) {
            throw invalidReply();
        }
        return (int) value;
    }

    private static boolean validSelector(String value) {
        return value.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}");
    }

    private static String label(String value, String fallback) {
        String label = value.trim();
        if (label.isEmpty() || label.length() > 140 || label.chars().anyMatch(Character::isISOControl)
                || label.toLowerCase(Locale.ROOT).matches(".*(https?://|bearer\\s).*")) {
            return fallback;
        }
        return label;
    }

    private static String field(JSONObject object, String key) {
        Object value = object == null ? null : object.opt(key);
        return value instanceof String ? (String) value : "";
    }

    private static ProtocolFailure unverifiedUrl() {
        return new ProtocolFailure("native.hermes_returned_an_unverified_authorization_address_continue_in_the_official");
    }

    private static ProtocolFailure invalidReply() {
        return new ProtocolFailure("native.could_not_verify_the_authorization_session_returned_by_hermes");
    }

    static final class ProtocolFailure extends HermesText.Failure {
        ProtocolFailure(String key, Object... values) {
            super(key, values);
        }
    }
}
