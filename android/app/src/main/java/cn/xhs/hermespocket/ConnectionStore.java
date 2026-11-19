package cn.xhs.hermespocket;

import static cn.xhs.hermespocket.HermesText.t;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Objects;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import okhttp3.HttpUrl;

/** Stores one gateway login and binds every credential to its server and sign-in. */
public final class ConnectionStore {
    private static final String KEY_ALIAS = "hermes-pocket-credentials-v1";
    private static final String URL = "url";
    private static final String WEBAPP_URL = "webapp_url";
    private static final String LOGIN = "login";
    private static final String TOKENS = "tokens";
    private static final String IV = "iv";
    private static final Object STORE_LOCK = new Object();
    private static final Object REFRESH_LOCK = new Object();
    private static final long REFRESH_MARGIN_SECONDS = 30;

    private final SharedPreferences preferences;

    public ConnectionStore(Context context) {
        preferences = context.getSharedPreferences("hermes-pocket-connection", Context.MODE_PRIVATE);
    }

    public static String normalizeUrl(String value) {
        String input = value == null ? "" : value.trim();
        String incompleteAddress = t("native.enter_the_full_hermes_webapp_address_a_port_and_path");
        URI raw;
        try {
            raw = new URI(input);
        } catch (URISyntaxException error) {
            throw new IllegalArgumentException(incompleteAddress);
        }
        if (raw.getHost() == null || raw.getRawUserInfo() != null || raw.getRawQuery() != null
                || raw.getRawFragment() != null || raw.getPort() == 0) {
            throw new IllegalArgumentException(incompleteAddress);
        }
        String normalized = HermesClient.normalizeBaseUrl(input);
        HttpUrl parsed = HttpUrl.get(normalized);
        if (!parsed.isHttps() && !HermesClient.isTailscaleAddress(parsed.host())) {
            throw new HermesText.InvalidInput("native.http_is_available_for_private_tailscale_ip_addresses_use_https");
        }
        return normalized;
    }

    public String getUrl() {
        return preferences.getString(URL, null);
    }

    String getWebappUrl() {
        synchronized (STORE_LOCK) {
            String address = preferences.getString(WEBAPP_URL, null);
            if (address != null) {
                return address;
            }
            String legacy = getUrl();
            if (legacy != null) {
                preferences.edit().putString(WEBAPP_URL, legacy).apply();
            }
            return legacy;
        }
    }

    void setWebappUrl(String url) {
        String normalized = normalizeUrl(url);
        synchronized (STORE_LOCK) {
            preferences.edit().putString(WEBAPP_URL, normalized).apply();
        }
    }

    public boolean hasCredentials() {
        return activeLogin() != null;
    }

    /** A stable, native-only binding that survives token refresh and changes on re-login. */
    static final class LoginScope {
        final String url;
        private final String generation;

        private LoginScope(String url, String generation) {
            this.url = url;
            this.generation = generation;
        }

        String preferenceBinding() {
            return generation;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof LoginScope)) {
                return false;
            }
            LoginScope scope = (LoginScope) other;
            return url.equals(scope.url) && generation.equals(scope.generation);
        }

        @Override
        public int hashCode() {
            return Objects.hash(url, generation);
        }
    }

    LoginScope activeLogin() {
        synchronized (STORE_LOCK) {
            String url = getUrl();
            String generation = preferences.getString(LOGIN, null);
            if (url == null || generation == null || !preferences.contains(TOKENS) || !preferences.contains(IV)) {
                return null;
            }
            return new LoginScope(url, generation);
        }
    }

    Runnable observeLoginChanges(Runnable changed) {
        SharedPreferences.OnSharedPreferenceChangeListener listener = (ignored, key) -> {
            if (key == null || LOGIN.equals(key) || URL.equals(key)) {
                changed.run();
            }
        };
        preferences.registerOnSharedPreferenceChangeListener(listener);
        return () -> preferences.unregisterOnSharedPreferenceChangeListener(listener);
    }

    public void setUrl(String url) {
        String normalized = normalizeUrl(url);
        synchronized (STORE_LOCK) {
            if (!normalized.equals(getUrl())) {
                removeCredentials(preferences.edit()).putString(URL, normalized).apply();
            }
        }
    }

    public void clear() {
        synchronized (STORE_LOCK) {
            removeCredentials(preferences.edit()).remove(URL).commit();
            deleteKey();
        }
    }

    void saveLogin(HttpUrl server, NativeSignIn.Tokens tokens) throws IOException {
        synchronized (STORE_LOCK) {
            writeTokens(server.toString(), UUID.randomUUID().toString(), tokens);
        }
    }

    public HermesClient.Auth authFor(String url) {
        HttpUrl server = HttpUrl.get(normalizeUrl(url));
        String login;
        synchronized (STORE_LOCK) {
            login = server.toString().equals(getUrl()) ? preferences.getString(LOGIN, null) : null;
        }
        return scopedAuth(server, login);
    }

    HermesClient.Auth authFor(LoginScope scope) {
        return scopedAuth(HttpUrl.get(normalizeUrl(scope.url)), scope.generation);
    }

    private HermesClient.Auth scopedAuth(HttpUrl server, String login) {
        return new HermesClient.Auth() {
            @Override
            public String cookieHeader(String requestUrl) {
                return null;
            }

            @Override
            public String accessToken(String requestUrl) throws IOException {
                HttpUrl target = HttpUrl.parse(requestUrl);
                if (target == null || !server.scheme().equals(target.scheme())
                        || !server.host().equals(target.host()) || server.port() != target.port()
                        || !target.encodedPath().startsWith(server.encodedPath())
                        || !target.username().isEmpty() || !target.password().isEmpty()) {
                    throw new HermesText.Failure("native.the_request_address_does_not_match_the_sign_in_address");
                }
                return validAccessToken(server, login);
            }
        };
    }

    private String validAccessToken(HttpUrl server, String login) throws IOException {
        String url = server.toString();
        NativeSignIn.Tokens tokens = readTokens(url, login);
        if (tokens.expiresAt > now() + REFRESH_MARGIN_SECONDS) {
            return tokens.access;
        }
        synchronized (REFRESH_LOCK) {
            tokens = readTokens(url, login);
            if (tokens.expiresAt > now() + REFRESH_MARGIN_SECONDS) {
                return tokens.access;
            }
            if (tokens.refresh.isEmpty()) {
                if (tokens.expiresAt > now()) {
                    return tokens.access;
                }
                forgetLogin(url, login);
                throw new SignInRequiredException();
            }
            NativeSignIn.Tokens refreshed;
            try {
                refreshed = NativeSignIn.refresh(server, tokens);
            } catch (SignInRequiredException error) {
                forgetLogin(url, login);
                throw error;
            }
            synchronized (STORE_LOCK) {
                requireCurrentLogin(url, login);
                writeTokens(url, login, refreshed);
            }
            return refreshed.access;
        }
    }

    private NativeSignIn.Tokens readTokens(String url, String login) throws IOException {
        synchronized (STORE_LOCK) {
            requireCurrentLogin(url, login);
            try {
                byte[] encrypted = Base64.decode(preferences.getString(TOKENS, ""), Base64.NO_WRAP);
                byte[] iv = Base64.decode(preferences.getString(IV, ""), Base64.NO_WRAP);
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(128, iv));
                cipher.updateAAD(associatedData(url, login));
                String json = new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
                return NativeSignIn.Tokens.parse(new JSONObject(json));
            } catch (GeneralSecurityException | IllegalArgumentException | IOException | JSONException error) {
                forgetLogin(url, login);
                throw new SignInRequiredException();
            }
        }
    }

    private void writeTokens(String url, String login, NativeSignIn.Tokens tokens) throws IOException {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(true));
            cipher.updateAAD(associatedData(url, login));
            byte[] encrypted = cipher.doFinal(tokens.json().toString().getBytes(StandardCharsets.UTF_8));
            boolean saved = preferences.edit()
                    .putString(URL, url)
                    .putString(LOGIN, login)
                    .putString(TOKENS, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                    .putString(IV, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                    .commit();
            if (!saved) {
                removeCredentials(preferences.edit()).apply();
                throw new HermesText.Failure("native.could_not_save_the_credentials_check_device_storage_and_try");
            }
        } catch (GeneralSecurityException | JSONException error) {
            throw new HermesText.Failure(error, "native.could_not_store_the_sign_in_credentials_in_the_device");
        }
    }

    private void requireCurrentLogin(String url, String login) throws SignInRequiredException {
        if (!isCurrentLogin(url, login)) {
            throw new SignInRequiredException();
        }
    }

    private boolean isCurrentLogin(String url, String login) {
        return login != null && url.equals(getUrl()) && login.equals(preferences.getString(LOGIN, null));
    }

    private void forgetLogin(String url, String login) {
        synchronized (STORE_LOCK) {
            if (isCurrentLogin(url, login)) {
                removeCredentials(preferences.edit()).apply();
            }
        }
    }

    private static SharedPreferences.Editor removeCredentials(SharedPreferences.Editor editor) {
        return editor.remove(LOGIN).remove(TOKENS).remove(IV);
    }

    private static SecretKey key(boolean create) throws GeneralSecurityException, IOException {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        SecretKey key = (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        if (key != null) {
            return key;
        }
        if (!create) {
            throw new GeneralSecurityException("Login key unavailable");
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }

    private static void deleteKey() {
        try {
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            keyStore.deleteEntry(KEY_ALIAS);
        } catch (GeneralSecurityException | IOException ignored) {
            // Clearing preferences also removes the only stored encrypted credential.
        }
    }

    private static byte[] associatedData(String url, String login) {
        return (url + "\n" + login).getBytes(StandardCharsets.UTF_8);
    }

    static long now() {
        return System.currentTimeMillis() / 1000;
    }

    public static final class SignInRequiredException extends IOException {
        SignInRequiredException() {
            super(t("native.your_session_expired_sign_in_again"));
        }
    }
}
