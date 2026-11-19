package cn.xhs.hermespocket;

import static cn.xhs.hermespocket.HermesText.t;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;

import okhttp3.HttpUrl;

/** Keeps the sign-in screen native and opens the device's browser for authorization. */
public final class LoginActivity extends Activity {
    private PocketStyle style;
    private HttpUrl server;
    private ConnectionStore store;
    private SignInAttempt attempt;
    private TextView status;
    private ProgressBar progress;
    private Button retry;
    private boolean resumed;
    private Runnable stopObservingAppearance;
    private String visibleError;

    @Override
    protected void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        setTitle(R.string.app_name);
        setResult(RESULT_CANCELED);
        try {
            server = HttpUrl.get(ConnectionStore.normalizeUrl(getIntent().getStringExtra("url")));
        } catch (IllegalArgumentException error) {
            setResult(RESULT_CANCELED, new Intent().putExtra("error", error.getMessage()));
            finish();
            return;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        store = new ConnectionStore(getApplicationContext());
        buildScreen();
        stopObservingAppearance = HermesAppearanceStore.observe(this, this::refreshAppearance);
        SignInAttempt retained = (SignInAttempt) getLastNonConfigurationInstance();
        if (retained != null && server.equals(retained.server)) {
            attempt = retained;
            attempt.activity = this;
            update();
        } else if (savedState == null) {
            startLogin();
        } else {
            showError(t("native.sign_in_was_interrupted_open_browser_sign_in_again"));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        HermesAppearanceStore.refresh(this, null);
        refreshAppearance();
        update();
    }

    @Override
    protected void onPause() {
        resumed = false;
        super.onPause();
    }

    @Override
    public Object onRetainNonConfigurationInstance() {
        return attempt;
    }

    @Override
    protected void onDestroy() {
        if (stopObservingAppearance != null) {
            stopObservingAppearance.run();
        }
        if (attempt != null) {
            attempt.activity = null;
            if (!isChangingConfigurations()) {
                attempt.cancel();
            }
        }
        super.onDestroy();
    }

    private void buildScreen() {
        style = new PocketStyle(this);
        LinearLayout root = style.column();
        root.setBackgroundColor(style.background);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        LinearLayout outer = style.column();
        outer.setGravity(Gravity.CENTER);
        outer.setPadding(style.dp(24), style.dp(24), style.dp(24), style.dp(28));
        scroll.addView(outer, new ScrollView.LayoutParams(-1, -2));

        LinearLayout panel = style.column();
        panel.setBackground(style.box(style.surface, 4, style.line));
        panel.setPadding(style.dp(20), style.dp(20), style.dp(20), style.dp(20));
        outer.addView(panel, new LinearLayout.LayoutParams(
                Math.max(style.dp(160), Math.min(style.dp(720),
                        getResources().getDisplayMetrics().widthPixels - style.dp(48))), -2));
        LinearLayout headingRow = new LinearLayout(this);
        headingRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams markSize = new LinearLayout.LayoutParams(style.dp(24), style.dp(24));
        markSize.setMarginEnd(style.dp(9));
        headingRow.addView(style.mark(), markSize);
        TextView heading = style.label(t("native.sign_in_to_hermes"), 16, style.text, true);
        if (Build.VERSION.SDK_INT >= 28) {
            heading.setAccessibilityHeading(true);
        }
        headingRow.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        panel.addView(headingRow, item(0));
        TextView address = style.label(server.toString(), 13, style.secondary, false);
        address.setTextDirection(View.TEXT_DIRECTION_LTR);
        panel.addView(address, item(8));

        LinearLayout activity = new LinearLayout(this);
        activity.setGravity(Gravity.CENTER_VERTICAL);
        panel.addView(activity, item(16));
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
        progress.setIndeterminateTintList(ColorStateList.valueOf(style.accent));
        progress.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(style.dp(20), style.dp(20));
        progressParams.setMarginEnd(style.dp(10));
        activity.addView(progress, progressParams);
        status = style.label("", 14, style.secondary, false);
        status.setLineSpacing(style.dp(4), 1f);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        activity.addView(status, new LinearLayout.LayoutParams(0, -2, 1));

        retry = style.button(t("native.sign_in_again"), true, this::startLogin);
        retry.setVisibility(View.GONE);
        panel.addView(retry, item(16));
        Button cancel = style.button(t("native.cancel"), false, this::finish);
        panel.addView(cancel, item(10));
        setContentView(root);
        style.applyWindow(getWindow(), root, false);
    }

    private void refreshAppearance() {
        if (!resumed || style == null || style.sameAppearance(new PocketStyle(this))) {
            return;
        }
        buildScreen();
        if (attempt == null && visibleError != null) {
            showError(visibleError);
        } else {
            update();
        }
    }

    private LinearLayout.LayoutParams item(int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = style.dp(top);
        return params;
    }

    private void startLogin() {
        visibleError = null;
        if (attempt != null) {
            attempt.activity = null;
            attempt.cancel();
        }
        setResult(RESULT_CANCELED);
        attempt = new SignInAttempt(server, this);
        update();
        attempt.start();
    }

    private void update() {
        if (attempt == null || isFinishing()) {
            return;
        }
        if (attempt.failure != null) {
            showError(attempt.failure);
            return;
        }
        if (attempt.tokens != null) {
            try {
                store.saveLogin(server, attempt.tokens);
                setResult(RESULT_OK);
                finish();
            } catch (IOException error) {
                attempt.fail(error.getMessage());
            }
            return;
        }
        progress.setVisibility(View.VISIBLE);
        retry.setVisibility(View.GONE);
        status.setTextColor(style.secondary);
        if (attempt.authorizeUrl == null) {
            status.setText(t("native.preparing_sign_in"));
        } else if (attempt.exchanging) {
            status.setText(t("native.verifying_authorization"));
        } else {
            status.setText(t("native.after_authorizing_in_your_browser_return_to", getString(R.string.app_name)));
        }
        if (resumed && attempt.authorizeUrl != null && !attempt.browserOpened) {
            try {
                Intent browser = new Intent(Intent.ACTION_VIEW, Uri.parse(attempt.authorizeUrl.toString()));
                browser.addCategory(Intent.CATEGORY_BROWSABLE);
                attempt.browserOpened = true;
                startActivity(browser);
            } catch (ActivityNotFoundException | SecurityException error) {
                attempt.fail(t("native.could_not_open_the_system_browser_make_sure_a_browser"));
            }
        }
    }

    private void showError(String message) {
        visibleError = message;
        progress.setVisibility(View.GONE);
        retry.setVisibility(View.VISIBLE);
        status.setTextColor(style.problem);
        status.setText(message);
        setResult(RESULT_CANCELED, new Intent().putExtra("error", message));
    }

    private static final class SignInAttempt {
        final HttpUrl server;
        final Handler main = new Handler(Looper.getMainLooper());
        final Thread worker;
        LoginActivity activity;
        HttpUrl authorizeUrl;
        String failure;
        NativeSignIn.Tokens tokens;
        boolean browserOpened;
        boolean exchanging;
        volatile boolean cancelled;
        volatile LoopbackCallback callback;

        SignInAttempt(HttpUrl server, LoginActivity activity) {
            this.server = server;
            this.activity = activity;
            worker = new Thread(this::run, "Hermes sign-in");
        }

        void start() {
            worker.start();
        }

        private void run() {
            try {
                NativeSignIn.checkSupport(server);
                if (cancelled) {
                    return;
                }
                String verifier = NativeSignIn.randomValue();
                String state = NativeSignIn.randomValue();
                String code;
                try (LoopbackCallback listener = new LoopbackCallback(state)) {
                    callback = listener;
                    if (cancelled) {
                        return;
                    }
                    HttpUrl authorization = NativeSignIn.authorizeUrl(server, verifier, state, listener.redirectUri());
                    publish(() -> authorizeUrl = authorization);
                    code = listener.awaitCode();
                } finally {
                    callback = null;
                }
                if (cancelled) {
                    return;
                }
                publish(() -> exchanging = true);
                NativeSignIn.Tokens confirmed = NativeSignIn.exchange(server, code, verifier);
                publish(() -> tokens = confirmed);
            } catch (IOException error) {
                publish(() -> failure = error.getMessage() == null ? t("native.sign_in_failed_try_again") : error.getMessage());
            }
        }

        private void publish(Runnable change) {
            main.post(() -> {
                if (!cancelled) {
                    change.run();
                    if (activity != null) {
                        activity.update();
                    }
                }
            });
        }

        void fail(String reason) {
            failure = reason == null ? t("native.sign_in_failed_try_again") : reason;
            cancel();
            if (activity != null) {
                activity.update();
            }
        }

        void cancel() {
            cancelled = true;
            tokens = null;
            LoopbackCallback listener = callback;
            if (listener != null) {
                listener.close();
            }
            worker.interrupt();
        }
    }
}
