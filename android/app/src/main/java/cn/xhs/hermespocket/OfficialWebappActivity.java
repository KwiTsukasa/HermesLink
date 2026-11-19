package cn.xhs.hermespocket;

import static cn.xhs.hermespocket.HermesText.t;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.MimeTypeMap;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SafeBrowsingResponse;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebBackForwardList;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import okhttp3.HttpUrl;

/** Loads the server's own renderer. Browser cookies never cross into the native sign-in store. */
public final class OfficialWebappActivity extends Activity {
    static final String EXTRA_LEGACY_NATIVE = "cn.xhs.hermespocket.OPEN_LEGACY_NATIVE";
    public static final String ACTION_OPEN_BROWSER = "cn.xhs.hermespocket.OPEN_OFFICIAL_BROWSER";
    public static final String ACTION_OPEN_NATIVE = "cn.xhs.hermespocket.OPEN_NATIVE_SHORTCUT";

    private static final int CHOOSE_DOCUMENT = 7801;
    private static final int MEDIA_PERMISSIONS = 7802;
    private static final int MAX_CHOOSER_ITEMS = 32;
    private static final int MAX_CSS_BYTES = 128 * 1024;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final String SIGN_IN_FAILED = "native.official_webapp_sign_in_failed";
    private static final String RETURN_TO_SIGN_IN = "native.official_webapp_return_to_sign_in";

    private ConnectionStore store;
    private PocketStyle style;
    private FrameLayout content;
    private WebView browser;
    private OfficialWebappOrigin origin;
    private HttpUrl committed;
    private String configuredAddress;
    private String css = "";
    private String problem;
    private boolean verifiedUi;
    private boolean resumed;
    private boolean signingIn;
    private boolean verifyingDocument;
    private boolean loadFinished;
    private boolean pickerPending;
    private int navigation;
    private int chooserNavigation;
    private String chooserAddress;
    private ValueCallback<Uri[]> chooser;
    private MediaConsent mediaConsent;
    private MediaConsent platformConsent;
    private AlertDialog mediaDialog;
    private AlertDialog appDialog;
    private AlertDialog addressDialog;
    private OnBackInvokedCallback backCallback;

    @Override
    protected void onCreate(Bundle savedState) {
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        setTheme(dark ? android.R.style.Theme_Material_NoActionBar
                : android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(savedState);
        setTitle(R.string.app_name);
        store = new ConnectionStore(getApplicationContext());
        style = new PocketStyle(this);
        css = loadMobileStyles();

        LinearLayout root = style.column();
        root.setBackgroundColor(style.background);
        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(MATCH, 0, 1));
        setContentView(root);
        style.applyWindow(getWindow(), root, true);

        if (Build.VERSION.SDK_INT >= 33) {
            backCallback = this::back;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
        }
        loadConfiguredAddress();
        if (savedState == null) {
            handleShortcut(getIntent());
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        loadConfiguredAddress();
        handleShortcut(intent);
    }

    private void handleShortcut(Intent intent) {
        if (intent != null && ACTION_OPEN_BROWSER.equals(intent.getAction())) {
            if (origin == null) {
                showAddressDialog(true);
            } else {
                openExternal(HttpUrl.get(origin.address()));
            }
        } else if (intent != null && ACTION_OPEN_NATIVE.equals(intent.getAction())) {
            openLegacy();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        loadConfiguredAddress();
        if (browser != null) {
            browser.onResume();
        }
        finishMediaConsent();
    }

    @Override
    protected void onPause() {
        resumed = false;
        if (mediaConsent != null && !mediaConsent.platformPending) {
            cancelMediaConsent();
        }
        if (browser != null) {
            browser.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        }
        if (appDialog != null) {
            appDialog.dismiss();
        }
        if (addressDialog != null) {
            addressDialog.dismiss();
        }
        releaseBrowser();
        super.onDestroy();
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            showAppMenu();
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    private void loadConfiguredAddress() {
        String saved = store.getWebappUrl();
        if (java.util.Objects.equals(configuredAddress, saved) && (browser != null || problem != null)) {
            return;
        }
        configuredAddress = saved;
        releaseBrowser();
        problem = null;
        origin = null;
        if (saved == null) {
            showSetup();
            return;
        }
        try {
            origin = new OfficialWebappOrigin(ConnectionStore.normalizeUrl(saved));
        } catch (IllegalArgumentException invalid) {
            showSetup();
            return;
        }
        startBrowser();
    }

    private void startBrowser() {
        if (origin == null) {
            showSetup();
            return;
        }
        releaseBrowser();
        problem = null;
        content.removeAllViews();
        WebView renderer = new WebView(this);
        browser = renderer;
        renderer.setBackgroundColor(style.background);
        renderer.setSaveEnabled(false);
        WebSettings settings = renderer.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSafeBrowsingEnabled(true);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(false);
        if (Build.VERSION.SDK_INT >= 33) {
            settings.setAlgorithmicDarkeningAllowed(false);
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(renderer, false);
        renderer.setWebViewClient(new RendererClient());
        renderer.setWebChromeClient(new RendererChrome());
        content.addView(renderer, new FrameLayout.LayoutParams(MATCH, MATCH));
        renderer.loadUrl(origin.address());
    }

    private void releaseBrowser() {
        releaseBrowser(false);
    }

    private void releaseBrowser(boolean processGone) {
        navigation++;
        committed = null;
        verifiedUi = false;
        verifyingDocument = false;
        loadFinished = false;
        signingIn = false;
        clearChooser();
        cancelMediaConsent();
        WebView previous = browser;
        browser = null;
        if (previous != null) {
            if (!processGone) {
                previous.stopLoading();
            }
            content.removeView(previous);
            previous.destroy();
        }
    }

    private void showSetup() {
        showCard("native.official_webapp_address_needed",
                t("native.connection_settings"), () -> showAddressDialog(false));
    }

    private void showAddressDialog(boolean openInBrowser) {
        if (addressDialog != null) {
            return;
        }
        LinearLayout field = style.column();
        field.setPadding(style.dp(24), 0, style.dp(24), 0);
        EditText address = new EditText(this);
        address.setSingleLine(true);
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setImeOptions(EditorInfo.IME_ACTION_GO);
        address.setTextDirection(View.TEXT_DIRECTION_LTR);
        address.setHint(t("native.https_your_hermes_address"));
        address.setText(configuredAddress);
        field.addView(address, new LinearLayout.LayoutParams(MATCH, WRAP));
        AlertDialog dialog = new AlertDialog.Builder(this, dialogTheme())
                .setTitle(t("native.hermes_webapp_address"))
                .setView(field)
                .setPositiveButton(t(openInBrowser ? "native.official_webapp_open_browser"
                        : "native.official_webapp_open"), null)
                .setNegativeButton(t("native.cancel"), null)
                .create();
        addressDialog = dialog;
        dialog.setOnDismissListener(dismissed -> addressDialog = null);
        Runnable submit = () -> {
            try {
                store.setWebappUrl(address.getText().toString());
            } catch (IllegalArgumentException error) {
                address.setError(error.getMessage());
                return;
            }
            dialog.dismiss();
            if (openInBrowser) {
                openExternal(HttpUrl.get(store.getWebappUrl()));
            } else if (browser == null && origin != null && origin.address().equals(store.getWebappUrl())) {
                startBrowser();
            } else {
                loadConfiguredAddress();
            }
        };
        dialog.setOnShowListener(shown -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(button -> submit.run()));
        address.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_GO) {
                return false;
            }
            submit.run();
            return true;
        });
        dialog.show();
        address.requestFocus();
    }

    private void showCard(String key, String actionLabel, Runnable action) {
        problem = key;
        content.removeAllViews();
        LinearLayout panel = style.column();
        panel.setPadding(style.dp(22), style.dp(22), style.dp(22), style.dp(22));
        panel.setBackground(style.box(style.surface, 6, style.line));
        TextView message = style.label(t(key), 15, style.text, false);
        message.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        panel.addView(message, new LinearLayout.LayoutParams(MATCH, WRAP));
        Button next = style.button(actionLabel, true, action);
        LinearLayout.LayoutParams button = new LinearLayout.LayoutParams(MATCH, WRAP);
        button.topMargin = style.dp(12);
        panel.addView(next, button);
        if (origin != null) {
            panel.addView(style.button(t("native.connection_settings"), false,
                    () -> showAddressDialog(false)), new LinearLayout.LayoutParams(MATCH, WRAP));
        }
        panel.addView(style.button(t("native.official_webapp_open_legacy"), false, this::openLegacy),
                new LinearLayout.LayoutParams(MATCH, WRAP));
        FrameLayout.LayoutParams place = new FrameLayout.LayoutParams(
                Math.min(style.dp(520), getResources().getDisplayMetrics().widthPixels - style.dp(40)),
                WRAP, Gravity.CENTER);
        content.addView(panel, place);
    }

    private void pageFailed(String key) {
        releaseBrowser();
        showCard(key, t("native.retry"), this::startBrowser);
    }

    private void openLegacy() {
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_LEGACY_NATIVE, true));
    }

    private void showAppMenu() {
        if (appDialog != null && appDialog.isShowing()) {
            return;
        }
        ArrayList<String> labels = new ArrayList<>();
        ArrayList<Runnable> actions = new ArrayList<>();
        if (origin != null) {
            if (signingIn || SIGN_IN_FAILED.equals(problem)) {
                labels.add(t(RETURN_TO_SIGN_IN));
                actions.add(this::startBrowser);
            } else {
                labels.add(t("native.refresh"));
                actions.add(() -> {
                    if (browser == null || signingIn) {
                        startBrowser();
                    } else {
                        browser.reload();
                    }
                });
            }
            labels.add(t("native.official_webapp_open_browser"));
            actions.add(() -> openExternal(HttpUrl.get(origin.address())));
        }
        labels.add(t("native.connection_settings"));
        actions.add(() -> showAddressDialog(false));
        labels.add(t("native.official_webapp_open_legacy"));
        actions.add(this::openLegacy);
        labels.add(t("native.close"));
        actions.add(this::finish);
        appDialog = new AlertDialog.Builder(this, dialogTheme()).setTitle(R.string.app_name)
                .setItems(labels.toArray(new String[0]), (dialog, selected) -> actions.get(selected).run())
                .setOnDismissListener(dialog -> appDialog = null).show();
    }

    private int dialogTheme() {
        return style.dark ? android.R.style.Theme_Material_Dialog_Alert
                : android.R.style.Theme_Material_Light_Dialog_Alert;
    }

    private void openExternal(HttpUrl target) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(target.toString()))
                    .addCategory(Intent.CATEGORY_BROWSABLE));
        } catch (RuntimeException unavailable) {
            Toast.makeText(this, t("native.this_device_cannot_open_the_browser_page"), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onBackPressed() {
        back();
    }

    private void back() {
        if (browser != null) {
            WindowInsets insets = content.getRootWindowInsets();
            if (Build.VERSION.SDK_INT >= 30 && insets != null && insets.isVisible(WindowInsets.Type.ime())) {
                InputMethodManager input = getSystemService(InputMethodManager.class);
                if (input != null) {
                    input.hideSoftInputFromWindow(browser.getWindowToken(), 0);
                }
                return;
            }
            // Provider pages can return a failed sign-in with HTTP 200. Back offers a fresh entry
            // without inspecting the page or restoring an OAuth URL from browser history.
            if (signingIn) {
                showAppMenu();
                return;
            }
            WebBackForwardList history = browser.copyBackForwardList();
            int current = history.getCurrentIndex();
            for (int index = current - 1; index >= 0; index--) {
                HttpUrl prior = OfficialWebappOrigin.browserUrl(history.getItemAtIndex(index).getUrl());
                if (origin.contains(prior) && !origin.isLoginDocument(prior)) {
                    browser.goBackOrForward(index - current);
                    return;
                }
            }
        }
        if (origin == null) {
            finish();
        } else {
            showAppMenu();
        }
    }

    private final class RendererClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            if (view != browser || origin == null) {
                return true;
            }
            String address = request.getUrl().toString();
            HttpUrl target = OfficialWebappOrigin.browserUrl(address);
            if (!request.isForMainFrame()) {
                return target == null && !"about:blank".equals(address)
                        && !"blob".equals(request.getUrl().getScheme())
                        && !"data".equals(request.getUrl().getScheme());
            }
            if (target == null) {
                return true;
            }
            if (origin.allowsMainFrame(target, signingIn)) {
                if (origin.isLoginDocument(target)) {
                    signingIn = true;
                }
                return false;
            }
            if (request.hasGesture()) {
                openExternal(target);
            }
            return true;
        }

        @Override
        public void onPageStarted(WebView view, String address, android.graphics.Bitmap icon) {
            if (view != browser || origin == null) {
                return;
            }
            navigation++;
            committed = null;
            verifiedUi = false;
            verifyingDocument = false;
            loadFinished = false;
            clearChooser();
            cancelMediaConsent();
            HttpUrl target = OfficialWebappOrigin.browserUrl(address);
            if (origin.contains(target)) {
                signingIn = origin.isLoginDocument(target);
            } else if (!origin.allowsMainFrame(target, signingIn)) {
                view.stopLoading();
            }
        }

        @Override
        public void onPageCommitVisible(WebView view, String address) {
            HttpUrl document = OfficialWebappOrigin.browserUrl(address);
            if (view == browser && origin != null && origin.isUiDocument(document)
                    && origin.sameDocument(document, browserLocation())) {
                committed = document;
                applyMobileStyles();
            }
        }

        @Override
        public void onPageFinished(WebView view, String address) {
            if (view == browser && committed != null
                    && origin.sameDocument(committed, OfficialWebappOrigin.browserUrl(address))) {
                loadFinished = true;
                applyMobileStyles();
            }
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (view != browser || origin == null || !request.isForMainFrame()) {
                return;
            }
            String address = request.getUrl().toString();
            if (!origin.allowsMainFrame(OfficialWebappOrigin.browserUrl(address), signingIn)) {
                return;
            }
            int code = error.getErrorCode();
            if (code == ERROR_FAILED_SSL_HANDSHAKE) {
                pageFailed("native.could_not_verify_the_https_connection_check_the_hermes_address");
            } else if (code == ERROR_HOST_LOOKUP || code == ERROR_CONNECT || code == ERROR_TIMEOUT
                    || address.equals(view.getUrl())) {
                pageFailed("native.official_webapp_loading_error");
            }
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                        android.webkit.WebResourceResponse response) {
            if (view != browser || origin == null || !request.isForMainFrame()) {
                return;
            }
            HttpUrl target = OfficialWebappOrigin.browserUrl(request.getUrl().toString());
            int status = response.getStatusCode();
            if (origin.isLoginCallbackFailure(target, signingIn, status)) {
                releaseBrowser();
                showCard(SIGN_IN_FAILED, t(RETURN_TO_SIGN_IN), OfficialWebappActivity.this::startBrowser);
            } else if ((status == 404 || status >= 500) && origin.isUiDocument(target)) {
                pageFailed("native.official_webapp_loading_error");
            }
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            handler.cancel();
            if (view == browser && error.getUrl().equals(view.getUrl())) {
                pageFailed("native.could_not_verify_the_https_connection_check_the_hermes_address");
            }
        }

        @Override
        public void onSafeBrowsingHit(WebView view, WebResourceRequest request, int threatType,
                                       SafeBrowsingResponse callback) {
            callback.backToSafety(false);
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            if (view == browser) {
                releaseBrowser(true);
                showCard("native.official_webapp_loading_error", t("native.retry"),
                        OfficialWebappActivity.this::startBrowser);
            }
            return true;
        }
    }

    private HttpUrl browserLocation() {
        return browser == null ? null : OfficialWebappOrigin.browserUrl(browser.getUrl());
    }

    private boolean isVerifiedUi() {
        return verifiedUi && committed != null && origin != null
                && origin.sameDocument(committed, browserLocation());
    }

    private void applyMobileStyles() {
        WebView renderer = browser;
        if (renderer == null || verifiedUi || verifyingDocument || committed == null
                || !origin.sameDocument(committed, browserLocation())) {
            return;
        }
        int identity = navigation;
        boolean finishing = loadFinished;
        String script = "(() => {if (location.origin !== " + JSONObject.quote(origin.authority())
                + " || location.pathname !== " + JSONObject.quote(committed.encodedPath())
                + ") return false; const root = document.getElementById('root');"
                + "const entry = Array.from(document.scripts).some(s => {"
                + "if (s.type !== 'module' || !s.src) return false;"
                + "const u = new URL(s.src, location.href);"
                + "return u.origin === location.origin && /\\/assets\\/index-[\\w-]+\\.js$/.test(u.pathname);});"
                + "if (!root || !root.classList.contains('scrollbar-dt') || !entry || !document.head) return false;"
                + "if (!document.getElementById('hermeslink-mobile-layout')) {"
                + "const style = document.createElement('style');style.id = 'hermeslink-mobile-layout';"
                + "style.textContent = " + JSONObject.quote(css) + ";document.head.appendChild(style);}"
                + "const layout = document.getElementById('hermeslink-mobile-layout');"
                + "return layout.sheet && layout.sheet.cssRules.length > 0 ? 'applied' : 'absent';})()";
        verifyingDocument = true;
        renderer.evaluateJavascript(script, result -> {
            if (renderer == browser && identity == navigation) {
                verifyingDocument = false;
                verifiedUi = "\"applied\"".equals(result) || "\"absent\"".equals(result);
                if (verifiedUi) {
                    android.util.Log.i("HermesOfficial",
                            "official-document verified; mobile-css=" + ("\"applied\"".equals(result)
                                    ? "applied" : "absent"));
                }
                if (!verifiedUi && loadFinished && !finishing) {
                    applyMobileStyles();
                }
            }
        });
    }

    private String loadMobileStyles() {
        try (InputStream input = getAssets().open("hermes-mobile-layout.css");
             ByteArrayOutputStream data = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (data.size() + count > MAX_CSS_BYTES) {
                    return "";
                }
                data.write(buffer, 0, count);
            }
            return new String(data.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException missing) {
            return "";
        }
    }

    private final class RendererChrome extends WebChromeClient {
        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
            if (view != browser || !resumed || !isVerifiedUi() || pickerPending
                    || params.getMode() != FileChooserParams.MODE_OPEN
                    && params.getMode() != FileChooserParams.MODE_OPEN_MULTIPLE) {
                callback.onReceiveValue(null);
                return true;
            }
            Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Set<String> types = acceptedMimeTypes(params.getAcceptTypes());
            picker.setType(types.size() == 1 ? types.iterator().next() : "*/*");
            if (types.size() > 1) {
                picker.putExtra(Intent.EXTRA_MIME_TYPES, types.toArray(new String[0]));
            }
            picker.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
            chooser = callback;
            chooserNavigation = navigation;
            chooserAddress = browser.getUrl();
            pickerPending = true;
            try {
                startActivityForResult(picker, CHOOSE_DOCUMENT);
            } catch (RuntimeException unavailable) {
                pickerPending = false;
                clearChooser();
            }
            return true;
        }

        @Override
        public void onGeolocationPermissionsShowPrompt(String address, GeolocationPermissions.Callback callback) {
            callback.invoke(address, false, false);
        }

        @Override
        public void onPermissionRequest(PermissionRequest request) {
            requestMediaConsent(request);
        }

        @Override
        public void onPermissionRequestCanceled(PermissionRequest request) {
            if (mediaConsent != null && mediaConsent.request == request) {
                mediaConsent = null;
                dismissMediaDialog();
            }
        }
    }

    private static Set<String> acceptedMimeTypes(String[] accepted) {
        Set<String> types = new LinkedHashSet<>();
        if (accepted == null) {
            return types;
        }
        for (String group : accepted) {
            if (group == null) {
                continue;
            }
            for (String item : group.split(",")) {
                String mime = item.trim().toLowerCase(Locale.ROOT);
                if (mime.matches("\\.[a-z0-9]{1,12}")) {
                    mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(mime.substring(1));
                }
                if (mime != null && mime.matches("[a-z0-9*][a-z0-9.+*-]*/[a-z0-9*][a-z0-9.+*-]*")) {
                    types.add(mime);
                }
            }
        }
        return types;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != CHOOSE_DOCUMENT) {
            return;
        }
        pickerPending = false;
        if (chooser == null) {
            return;
        }
        ValueCallback<Uri[]> receive = chooser;
        chooser = null;
        Uri[] result = null;
        if (resultCode == RESULT_OK && data != null && chooserNavigation == navigation && isVerifiedUi()
                && java.util.Objects.equals(chooserAddress, browser.getUrl())) {
            result = scopedDocuments(data);
        }
        chooserAddress = null;
        receive.onReceiveValue(result);
    }

    private Uri[] scopedDocuments(Intent result) {
        ClipData clip = result.getClipData();
        int count = clip == null ? (result.getData() == null ? 0 : 1) : clip.getItemCount();
        if (count == 0 || count > MAX_CHOOSER_ITEMS) {
            return null;
        }
        ArrayList<Uri> documents = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            Uri uri = clip == null ? result.getData() : clip.getItemAt(index).getUri();
            if (uri == null || !"content".equals(uri.getScheme()) || uri.getAuthority() == null
                    || uri.getAuthority().startsWith(getPackageName())
                    || checkUriPermission(uri, Process.myPid(), Process.myUid(),
                            Intent.FLAG_GRANT_READ_URI_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
                return null;
            }
            documents.add(uri);
        }
        return documents.toArray(new Uri[0]);
    }

    private void clearChooser() {
        ValueCallback<Uri[]> previous = chooser;
        chooser = null;
        chooserAddress = null;
        if (previous != null) {
            previous.onReceiveValue(null);
        }
    }

    private void requestMediaConsent(PermissionRequest request) {
        HttpUrl requestingOrigin = OfficialWebappOrigin.browserUrl(request.getOrigin().toString());
        if (!resumed || !isVerifiedUi() || !origin.canGrantMedia(requestingOrigin)
                || mediaConsent != null || platformConsent != null) {
            request.deny();
            return;
        }
        Map<String, String> media = new LinkedHashMap<>();
        for (String resource : request.getResources()) {
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
                media.put(resource, Manifest.permission.RECORD_AUDIO);
            } else if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) {
                media.put(resource, Manifest.permission.CAMERA);
            }
        }
        if (media.isEmpty()) {
            request.deny();
            return;
        }
        MediaConsent pending = new MediaConsent(request, requestingOrigin, navigation, browser.getUrl(), media);
        mediaConsent = pending;
        String item = media.size() > 1 ? t("native.official_webapp_microphone_and_camera")
                : media.containsValue(Manifest.permission.CAMERA) ? t("native.official_webapp_camera")
                : t("native.official_webapp_microphone");
        int theme = style.dark ? android.R.style.Theme_Material_Dialog_Alert
                : android.R.style.Theme_Material_Light_Dialog_Alert;
        mediaDialog = new AlertDialog.Builder(this, theme)
                .setTitle(t("native.official_webapp_allow_media", item))
                .setNegativeButton(t("native.deny"), (dialog, choice) -> cancelMediaConsent())
                .setPositiveButton(t("native.allow_once"), (dialog, choice) -> confirmMediaConsent(pending))
                .setOnCancelListener(dialog -> cancelMediaConsent())
                .show();
    }

    private void confirmMediaConsent(MediaConsent pending) {
        if (!validMediaConsent(pending)) {
            cancelMediaConsent();
            return;
        }
        ArrayList<String> missing = new ArrayList<>();
        for (String permission : pending.resources.values()) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                missing.add(permission);
            }
        }
        if (missing.isEmpty()) {
            pending.ready = true;
            finishMediaConsent();
        } else {
            pending.platformPending = true;
            platformConsent = pending;
            try {
                requestPermissions(missing.toArray(new String[0]), MEDIA_PERMISSIONS);
            } catch (RuntimeException unavailable) {
                platformConsent = null;
                cancelMediaConsent();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == MEDIA_PERMISSIONS) {
            MediaConsent pending = platformConsent;
            platformConsent = null;
            if (pending != null && pending == mediaConsent) {
                pending.platformPending = false;
                pending.ready = true;
                finishMediaConsent();
            }
        }
    }

    private boolean validMediaConsent(MediaConsent pending) {
        return resumed && pending == mediaConsent && pending.navigation == navigation
                && isVerifiedUi() && origin.canGrantMedia(pending.origin)
                && java.util.Objects.equals(pending.address, browser.getUrl());
    }

    private void finishMediaConsent() {
        MediaConsent pending = mediaConsent;
        if (!resumed || pending == null || !pending.ready) {
            return;
        }
        if (!validMediaConsent(pending)) {
            cancelMediaConsent();
            return;
        }
        ArrayList<String> allowed = new ArrayList<>();
        for (Map.Entry<String, String> item : pending.resources.entrySet()) {
            if (checkSelfPermission(item.getValue()) == PackageManager.PERMISSION_GRANTED) {
                allowed.add(item.getKey());
            }
        }
        mediaConsent = null;
        dismissMediaDialog();
        if (allowed.isEmpty()) {
            pending.request.deny();
        } else {
            pending.request.grant(allowed.toArray(new String[0]));
        }
    }

    private void cancelMediaConsent() {
        MediaConsent previous = mediaConsent;
        mediaConsent = null;
        dismissMediaDialog();
        if (previous != null) {
            previous.request.deny();
        }
    }

    private void dismissMediaDialog() {
        if (mediaDialog != null) {
            mediaDialog.dismiss();
            mediaDialog = null;
        }
    }

    private static final class MediaConsent {
        final PermissionRequest request;
        final HttpUrl origin;
        final int navigation;
        final String address;
        final Map<String, String> resources;
        boolean platformPending;
        boolean ready;

        MediaConsent(PermissionRequest request, HttpUrl origin, int navigation, String address,
                     Map<String, String> resources) {
            this.request = request;
            this.origin = origin;
            this.navigation = navigation;
            this.address = address;
            this.resources = resources;
        }
    }
}
