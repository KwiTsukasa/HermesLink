package cn.xhs.hermespocket;

import static cn.xhs.hermespocket.HermesText.t;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLException;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;

/** The gateway's RFC 8252 login, token exchange, and refresh endpoints. */
final class NativeSignIn {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int BASE64_URL = Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING;
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build();

    private NativeSignIn() {
    }

    static void checkSupport(HttpUrl server) throws IOException {
        JSONObject status = request(new Request.Builder().url(endpoint(server, "api/status")),
                "native.could_not_check_how_this_hermes_webapp_signs_users_in");
        JSONArray flows = status.optJSONArray("auth_flows");
        if (flows != null) {
            for (int index = 0; index < flows.length(); index++) {
                if ("native_pkce".equals(flows.optString(index))) {
                    return;
                }
            }
        }
        throw new HermesText.Failure("native.this_hermes_webapp_does_not_offer_native_sign_in_enable");
    }

    static HttpUrl authorizeUrl(HttpUrl server, String verifier, String state, String redirectUri) {
        return endpoint(server, "auth/native/authorize").newBuilder()
                .addQueryParameter("code_challenge_method", "S256")
                .addQueryParameter("code_challenge", challenge(verifier))
                .addQueryParameter("redirect_uri", redirectUri)
                .addQueryParameter("state", state)
                .build();
    }

    static Tokens exchange(HttpUrl server, String code, String verifier) throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("code", code).put("code_verifier", verifier);
        } catch (JSONException invalidBody) {
            throw new HermesText.Failure(invalidBody, "native.could_not_build_the_sign_in_request");
        }
        Tokens tokens = requestTokens(server, "auth/native/token", body, "native.hermes_did_not_accept_the_browser_authorization");
        JSONObject me = request(new Request.Builder().url(endpoint(server, "api/auth/me"))
                        .header("Authorization", "Bearer " + tokens.access),
                "native.hermes_did_not_confirm_the_sign_in");
        if (!tokens.user.equals(me.optString("user_id")) || !tokens.provider.equals(me.optString("provider"))) {
            throw new HermesText.Failure("native.hermes_returned_a_different_sign_in_identity_sign_in_again");
        }
        return tokens;
    }

    static Tokens refresh(HttpUrl server, Tokens old) throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("refresh_token", old.refresh).put("provider", old.provider);
        } catch (JSONException invalidBody) {
            throw new HermesText.Failure(invalidBody, "native.could_not_build_the_token_refresh_request");
        }
        try {
            Tokens next = requestTokens(server, "auth/native/refresh", body, "native.hermes_could_not_refresh_sign_in_right_now");
            if (!next.user.equals(old.user) || !next.provider.equals(old.provider)) {
                throw new ConnectionStore.SignInRequiredException();
            }
            return next;
        } catch (HttpFailure error) {
            if (error.status == 401 || error.status == 403) {
                throw new ConnectionStore.SignInRequiredException();
            }
            throw error;
        }
    }

    private static Tokens requestTokens(HttpUrl server, String path, JSONObject body, String failureKey)
            throws IOException {
        JSONObject result = request(new Request.Builder().url(endpoint(server, path))
                .post(RequestBody.create(body.toString(), JSON)), failureKey);
        try {
            Tokens tokens = Tokens.parse(result);
            if (tokens.expiresAt <= ConnectionStore.now()) {
                throw new HermesText.Failure("native.hermes_returned_expired_credentials_check_the_device_and_server_clocks");
            }
            return tokens;
        } catch (JSONException error) {
            throw new HermesText.Failure(error, "native.hermes_returned_unusable_credentials_sign_in_again");
        }
    }

    private static JSONObject request(Request.Builder builder, String failureKey) throws IOException {
        Request request = builder.header("Accept", "application/json").build();
        try (Response response = HTTP.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new HttpFailure(response.code(), failureKey);
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException();
            }
            BufferedSource source = body.source();
            if (source.request(MAX_RESPONSE_BYTES + 1)) {
                throw new IOException();
            }
            return new JSONObject(source.readUtf8());
        } catch (HttpFailure error) {
            throw error;
        } catch (SSLException error) {
            throw new RequestFailure(error, failureKey, "native.check_the_https_certificate");
        } catch (IOException | JSONException error) {
            throw new RequestFailure(error, failureKey, "native.check_your_network_and_the_server_address");
        }
    }

    private static HttpUrl endpoint(HttpUrl server, String path) {
        return server.newBuilder().addPathSegments(path).build();
    }

    static String randomValue() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.encodeToString(bytes, BASE64_URL);
    }

    private static String challenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.encodeToString(digest, BASE64_URL);
        } catch (NoSuchAlgorithmException missingSha256) {
            throw new AssertionError(missingSha256);
        }
    }

    static final class Tokens {
        final String access;
        final String refresh;
        final String provider;
        final String user;
        final long expiresAt;

        private Tokens(String access, String refresh, String provider, String user, long expiresAt) {
            this.access = access;
            this.refresh = refresh;
            this.provider = provider;
            this.user = user;
            this.expiresAt = expiresAt;
        }

        static Tokens parse(JSONObject json) throws JSONException {
            String access = json.getString("access_token");
            String provider = json.getString("provider");
            String user = json.getString("user_id");
            long expiresAt = json.getLong("expires_at");
            if (!"Bearer".equalsIgnoreCase(json.getString("token_type")) || !access.matches("[!-~]+")
                    || provider.isEmpty() || user.isEmpty() || expiresAt <= 0) {
                throw new JSONException("Invalid credential");
            }
            return new Tokens(access, json.optString("refresh_token", ""), provider, user, expiresAt);
        }

        JSONObject json() throws JSONException {
            return new JSONObject().put("access_token", access).put("refresh_token", refresh)
                    .put("token_type", "Bearer").put("provider", provider).put("user_id", user)
                    .put("expires_at", expiresAt);
        }
    }

    private static final class HttpFailure extends IOException {
        final int status;

        private final String failureKey;

        HttpFailure(int status, String failureKey) {
            this.status = status;
            this.failureKey = failureKey;
        }

        @Override
        public String getMessage() {
            return t("native.http_failure_format", t(failureKey), status);
        }
    }

    private static final class RequestFailure extends IOException {
        private final String failureKey;
        private final String detailKey;

        RequestFailure(Throwable cause, String failureKey, String detailKey) {
            super(cause);
            this.failureKey = failureKey;
            this.detailKey = detailKey;
        }

        @Override
        public String getMessage() {
            return t(detailKey, t(failureKey));
        }
    }
}
