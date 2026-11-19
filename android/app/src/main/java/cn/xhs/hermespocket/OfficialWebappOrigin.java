package cn.xhs.hermespocket;

import okhttp3.HttpUrl;

/** Trust boundary for the configured Webapp listener and its official page entry points. */
final class OfficialWebappOrigin {
    private static final String AUTH_CALLBACK = "auth/callback";

    private final HttpUrl base;
    private final String authority;

    OfficialWebappOrigin(String address) {
        base = HttpUrl.get(HermesClient.normalizeBaseUrl(address));
        String root = base.newBuilder().encodedPath("/").build().toString();
        authority = root.substring(0, root.length() - 1);
    }

    String address() {
        return base.toString();
    }

    String authority() {
        return authority;
    }

    boolean contains(HttpUrl url) {
        String prefix = base.encodedPath();
        return sameOrigin(url) && (url.encodedPath().startsWith(prefix)
                || url.encodedPath().equals(prefix.substring(0, prefix.length() - 1)));
    }

    boolean isUiDocument(HttpUrl url) {
        if (!contains(url)) {
            return false;
        }
        String path = url.encodedPath();
        String prefix = base.encodedPath();
        return path.equals(prefix) || path.equals(prefix.substring(0, prefix.length() - 1))
                || path.equals(prefix + "index.html") || path.equals(prefix + "chat");
    }

    boolean isLoginDocument(HttpUrl url) {
        if (!contains(url)) {
            return false;
        }
        String path = url.encodedPath();
        String prefix = base.encodedPath();
        return path.equals(prefix + "login") || path.equals(prefix + "auth/login")
                || path.equals(prefix + AUTH_CALLBACK) || path.equals(prefix + "auth/password-login");
    }

    boolean isLoginCallbackFailure(HttpUrl url, boolean signingIn, int status) {
        return signingIn && status >= 400 && status <= 599 && contains(url)
                && url.encodedPath().equals(base.encodedPath() + AUTH_CALLBACK);
    }

    boolean allowsMainFrame(HttpUrl url, boolean signingIn) {
        return contains(url) || (signingIn && url != null && url.isHttps());
    }

    boolean canGrantMedia(HttpUrl origin) {
        return base.isHttps() && sameOrigin(origin);
    }

    boolean sameDocument(HttpUrl first, HttpUrl second) {
        return contains(first) && contains(second)
                && first.encodedPath().equals(second.encodedPath())
                && java.util.Objects.equals(first.encodedQuery(), second.encodedQuery());
    }

    private boolean sameOrigin(HttpUrl url) {
        return url != null && base.scheme().equals(url.scheme()) && base.host().equals(url.host())
                && base.port() == url.port() && url.encodedUsername().isEmpty()
                && url.encodedPassword().isEmpty();
    }

    static HttpUrl browserUrl(String address) {
        HttpUrl url = address == null ? null : HttpUrl.parse(address);
        return url != null && url.encodedUsername().isEmpty() && url.encodedPassword().isEmpty()
                ? url : null;
    }
}
