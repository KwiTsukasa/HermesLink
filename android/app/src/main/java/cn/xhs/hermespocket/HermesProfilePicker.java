package cn.xhs.hermespocket;

import static cn.xhs.hermespocket.HermesText.t;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import com.facebook.react.bridge.BaseActivityEventListener;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.common.LifecycleState;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** System document selection and private temporary files for a scoped profile transfer. */
final class HermesProfilePicker extends BaseActivityEventListener {
    private static final int IMPORT = 7354;
    private static final int EXPORT = 7355;
    private final ReactApplicationContext context;
    private final ConnectionStore store;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Runnable stopObservingLogin;
    private Transfer active;
    private boolean closed;

    HermesProfilePicker(ReactApplicationContext context, ConnectionStore store) {
        this.context = context;
        this.store = store;
        stopObservingLogin = store.observeLoginChanges(() -> main.post(() -> {
            if (active != null && !active.scope.equals(store.activeLogin())) {
                invalidateTransfer();
            }
        }));
        context.addActivityEventListener(this);
    }

    void begin(String name, boolean importing, Promise promise) {
        main.post(() -> {
            ConnectionStore.LoginScope scope = store.activeLogin();
            if (closed || workspace() == null || scope == null) {
                promise.reject("PROFILE_ARCHIVE", t("native.sign_in_to_hermes_and_select_the_archive_in_the"));
                return;
            }
            if (active != null || !HermesProfileArchives.validProfile(name, importing)) {
                promise.reject("PROFILE_ARCHIVE", active == null
                        ? t("native.enter_a_valid_profile_name_use_a_new_name_for")
                        : t("native.finish_the_current_profile_transfer_first"));
                return;
            }
            Transfer transfer = new Transfer(scope, promise, name, importing);
            active = transfer;
            if (importing) {
                choose(transfer);
            } else {
                transfer.working = true;
                worker.execute(() -> prepareExport(transfer));
            }
        });
    }

    private void prepareExport(Transfer transfer) {
        File file = null;
        try {
            file = newTemp();
            client(transfer).exportArchive(transfer.name, file, transfer.read);
            File ready = file;
            main.post(() -> {
                transfer.working = false;
                if (current(transfer)) {
                    transfer.file = ready;
                    choose(transfer);
                } else {
                    ready.delete();
                }
            });
        } catch (Exception error) {
            if (file != null) {
                file.delete();
            }
            main.post(() -> finish(transfer, false, error));
        }
    }

    private void choose(Transfer transfer) {
        Activity activity = workspace();
        if (activity == null) {
            finish(transfer, false, new IOException());
            return;
        }
        Intent picker = new Intent(transfer.importing ? Intent.ACTION_OPEN_DOCUMENT
                : Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE);
        if (transfer.importing) {
            picker.setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES, new String[] {
                    "application/gzip", "application/x-gzip", "application/x-tar",
                    "application/octet-stream"
            });
        } else {
            picker.setType("application/gzip").putExtra(Intent.EXTRA_TITLE, transfer.name + ".tar.gz");
        }
        try {
            activity.startActivityForResult(picker, transfer.importing ? IMPORT : EXPORT);
        } catch (RuntimeException error) {
            finish(transfer, false, error);
        }
    }

    @Override
    public void onActivityResult(Activity activity, int requestCode, int resultCode, Intent data) {
        Transfer transfer = active;
        if (transfer == null || transfer.working || requestCode != (transfer.importing ? IMPORT : EXPORT)) {
            return;
        }
        Uri uri = data == null ? null : data.getData();
        if (resultCode != Activity.RESULT_OK || uri == null
                || !ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
            finish(transfer, false, null);
            return;
        }
        if (!current(transfer)) {
            invalidateTransfer();
            return;
        }
        ContentResolver resolver = context.getContentResolver();
        transfer.working = true;
        worker.execute(() -> transferDocument(transfer, resolver, uri));
    }

    private void transferDocument(Transfer transfer, ContentResolver resolver, Uri uri) {
        File file = transfer.file;
        Exception problem = null;
        try {
            transfer.read.checkCancelled();
            if (transfer.importing) {
                file = newTemp();
                try (InputStream in = resolver.openInputStream(uri); OutputStream out = new FileOutputStream(file)) {
                    if (in == null) {
                        throw new IOException();
                    }
                    HermesProfileArchives.transfer(in, out, HermesProfileArchives.MAX_ARCHIVE, transfer.read);
                }
                client(transfer).importArchive(transfer.name, file, transfer.read);
            } else {
                try (InputStream in = new FileInputStream(file);
                     OutputStream out = resolver.openOutputStream(uri, "wt")) {
                    if (out == null) {
                        throw new IOException();
                    }
                    HermesProfileArchives.transfer(in, out, HermesProfileArchives.MAX_ARCHIVE, transfer.read);
                }
            }
        } catch (Exception error) {
            problem = error;
        } finally {
            if (file != null) {
                file.delete();
            }
        }
        Exception error = problem;
        main.post(() -> finish(transfer, error == null, error));
    }

    private File newTemp() throws IOException {
        return File.createTempFile("hermes-profile-", ".tar.gz", context.getCacheDir());
    }

    private HermesProfileArchives client(Transfer transfer) {
        return new HermesProfileArchives(transfer.scope.url, store.authFor(transfer.scope));
    }

    private Activity workspace() {
        Activity activity = context.getCurrentActivity();
        return activity instanceof WorkspaceActivity && context.getLifecycleState() == LifecycleState.RESUMED
                ? activity : null;
    }

    private boolean current(Transfer transfer) {
        return !closed && active == transfer && transfer.scope.equals(store.activeLogin());
    }

    private void finish(Transfer transfer, boolean done, Exception error) {
        if (active != transfer) {
            return;
        }
        if (!current(transfer)) {
            invalidateTransfer();
            return;
        }
        active = null;
        if (transfer.file != null) {
            transfer.file.delete();
        }
        if (error != null) {
            transfer.promise.reject("PROFILE_ARCHIVE", error instanceof HermesProfileArchives.ArchiveFailure
                    ? error.getMessage() : transfer.importing
                    ? t("native.could_not_confirm_the_import_result_refresh_the_profile_list_2")
                    : t("native.could_not_save_the_archive_check_the_server_and_destination"));
        } else {
            transfer.promise.resolve(done);
        }
    }

    private void invalidateTransfer() {
        Transfer transfer = active;
        active = null;
        if (transfer != null) {
            transfer.read.cancel();
            if (!transfer.working && transfer.file != null) {
                transfer.file.delete();
            }
            transfer.promise.reject("PROFILE_ARCHIVE", t("native.the_hermes_login_changed_the_profile_transfer_was_canceled"));
        }
    }

    void invalidate() {
        context.removeActivityEventListener(this);
        stopObservingLogin.run();
        main.post(() -> {
            closed = true;
            invalidateTransfer();
            worker.shutdownNow();
        });
    }

    private static final class Transfer {
        final ConnectionStore.LoginScope scope;
        final Promise promise;
        final String name;
        final boolean importing;
        final HermesClient.RequestRead read = new HermesClient.RequestRead();
        boolean working;
        File file;

        Transfer(ConnectionStore.LoginScope scope, Promise promise, String name, boolean importing) {
            this.scope = scope;
            this.promise = promise;
            this.name = name;
            this.importing = importing;
        }
    }
}
