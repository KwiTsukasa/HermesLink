package cn.xhs.hermespocket;

import static cn.xhs.hermespocket.HermesText.t;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.OpenableColumns;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.BaseActivityEventListener;
import com.facebook.react.bridge.LifecycleEventListener;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.common.LifecycleState;
import com.facebook.react.modules.core.PermissionAwareActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Authenticated audio for the foreground workspace. RN receives status and transcripts only. */
public final class HermesMediaModule extends ReactContextBaseJavaModule
        implements LifecycleEventListener {
    private static final int PICK_AUDIO = 7351;
    private static final int MICROPHONE_PERMISSION = 7352;
    private static final String TRANSCRIPTION = "HermesMediaTranscription";
    private static final String SPEECH = "HermesMediaSpeech";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService workers = Executors.newFixedThreadPool(2);
    private final ReactApplicationContext context;
    private final ConnectionStore store;
    private final Runnable stopObservingLogin;
    private Job active;
    private Job statusJob;
    private Job picking;
    private Job granting;
    private volatile boolean closed;

    private final BaseActivityEventListener documents = new BaseActivityEventListener() {
        @Override
        public void onActivityResult(Activity activity, int code, int result, Intent data) {
            if (code == PICK_AUDIO) {
                finishPicker(result, data);
            }
        }
    };

    public HermesMediaModule(ReactApplicationContext context) {
        super(context);
        this.context = context;
        store = new ConnectionStore(context);
        stopObservingLogin = store.observeLoginChanges(() -> main.post(this::cancelStaleLogin));
        context.addActivityEventListener(documents);
        context.addLifecycleEventListener(this);
    }

    @Override
    public String getName() {
        return "HermesMedia";
    }

    @ReactMethod
    public void addListener(String name) {}

    @ReactMethod
    public void removeListeners(double count) {}

    @ReactMethod
    public void getStatus(Promise promise) {
        main.post(() -> {
            ConnectionStore.LoginScope scope = login(promise);
            if (scope == null) {
                return;
            }
            if (statusJob != null) {
                rejectCancelled(statusJob);
            }
            Job job = new Job(scope, Kind.STATUS, promise);
            statusJob = job;
            workers.execute(() -> {
                HermesMediaClient.Status status = null;
                Exception error = null;
                try {
                    status = job.media.status(job.operation);
                } catch (Exception failure) {
                    error = failure;
                }
                HermesMediaClient.Status result = status;
                Exception problem = error;
                main.post(() -> {
                    if (statusJob != job) {
                        return;
                    }
                    statusJob = null;
                    if (!currentLogin(job)) {
                        rejectCancelled(job);
                    } else if (problem != null) {
                        job.reject(message(problem, t("native.could_not_read_the_hermes_audio_status")));
                    } else {
                        job.resolve(statusMap(result));
                    }
                });
            });
        });
    }

    @ReactMethod
    public void pickAndTranscribe(Promise promise) {
        main.post(() -> {
            Activity activity = foreground(promise);
            Job job = activity == null ? null : newJob(Kind.FILE, promise);
            if (job == null) {
                return;
            }
            picking = job;
            try {
                activity.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*"), PICK_AUDIO);
            } catch (RuntimeException error) {
                picking = null;
                job.reject(t("native.this_device_cannot_open_the_audio_file_picker"));
            }
        });
    }

    @ReactMethod
    public void startLiveTranscription(Promise promise) {
        main.post(() -> {
            Activity activity = foreground(promise);
            Job job = activity == null ? null : newJob(Kind.LIVE, promise);
            if (job == null) {
                return;
            }
            if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {
                beginCapture(job);
                return;
            }
            if (!(activity instanceof PermissionAwareActivity)) {
                job.reject(t("native.this_screen_cannot_request_microphone_access"));
                return;
            }
            granting = job;
            try {
                ((PermissionAwareActivity) activity).requestPermissions(
                        new String[] {Manifest.permission.RECORD_AUDIO}, MICROPHONE_PERMISSION,
                        (code, permissions, results) -> {
                            if (code != MICROPHONE_PERMISSION) {
                                return false;
                            }
                            boolean allowed = results.length == 1
                                    && results[0] == PackageManager.PERMISSION_GRANTED;
                            main.post(() -> finishPermission(job, allowed));
                            return true;
                        });
            } catch (RuntimeException error) {
                granting = null;
                job.reject(t("native.could_not_request_microphone_access"));
            }
        });
    }

    @ReactMethod
    public void stopLiveTranscription(Promise promise) {
        main.post(() -> {
            if (granting != null) {
                Job pending = granting;
                granting = null;
                rejectCancelled(pending);
            }
            Job job = active;
            if (job != null && job.kind == Kind.LIVE) {
                if (job.recording && !job.finishing) {
                    job.finishing = true;
                    emit(TRANSCRIPTION, "finishing", null, null, null);
                } else if (!job.recording) {
                    cancelActive();
                }
            }
            promise.resolve(null);
        });
    }

    @ReactMethod
    public void speak(String text, Promise promise) {
        main.post(() -> {
            Activity activity = foreground(promise);
            if (activity == null) {
                return;
            }
            String speech = text == null ? "" : text.trim();
            if (speech.isEmpty() || speech.length() > HermesMediaClient.MAX_SPEECH_TEXT) {
                promise.reject("MEDIA_INPUT", t("native.enter_1_to_20_000_characters_to_read_aloud"));
                return;
            }
            Job job = newJob(Kind.SPEECH, promise);
            if (job == null) {
                return;
            }
            active = job;
            emit(SPEECH, "generating", null, null, null);
            job.resolve(null);
            workers.execute(() -> playSpeech(job, speech));
        });
    }

    @ReactMethod
    public void stopSpeaking(Promise promise) {
        main.post(() -> {
            if (active != null && active.kind == Kind.SPEECH) {
                cancelActive();
            }
            promise.resolve(null);
        });
    }

    @ReactMethod
    public void cancelAudio(Promise promise) {
        main.post(() -> {
            cancelAll();
            promise.resolve(null);
        });
    }

    private void finishPermission(Job job, boolean allowed) {
        if (granting != job) {
            return;
        }
        granting = null;
        if (!allowed) {
            job.reject(t("native.allow_microphone_access_before_recording"));
        } else if (isForeground() && currentLogin(job)) {
            beginCapture(job);
        } else {
            rejectCancelled(job);
        }
    }

    private void beginCapture(Job job) {
        active = job;
        workers.execute(() -> {
            try (HermesMediaClient.Transcription stream = job.media.beginTranscription(
                    job.operation, partial -> queuePartial(job, partial))) {
                HermesDeviceAudio.record(stream, job.operation, () -> job.finishing, () -> {
                    job.recording = true;
                    main.post(() -> {
                        if (displaying(job)) {
                            job.resolve(null);
                            if (!job.finishing) {
                                emit(TRANSCRIPTION, "listening", null, null, null);
                            }
                        }
                    });
                });
                main.post(() -> {
                    if (displaying(job) && !job.finishing) {
                        job.finishing = true;
                        emit(TRANSCRIPTION, "finishing", null, null, null);
                    }
                });
                String transcript = stream.finish();
                main.post(() -> finish(job, transcript, null));
            } catch (Exception error) {
                job.operation.cancel();
                main.post(() -> finish(job, null, error));
            }
        });
    }

    private void queuePartial(Job job, String partial) {
        job.partial.set(partial);
        if (job.partialQueued.compareAndSet(false, true)) {
            main.postDelayed(() -> {
                job.partialQueued.set(false);
                String updated = job.partial.getAndSet(null);
                if (updated != null && displaying(job)) {
                    emit(TRANSCRIPTION, job.finishing ? "finishing" : "listening", updated, null, null);
                }
            }, 100);
        }
    }

    private void finishPicker(int result, Intent data) {
        Job job = picking;
        picking = null;
        if (job == null) {
            return;
        }
        Uri uri = data == null ? null : data.getData();
        if (result != Activity.RESULT_OK || uri == null
                || !ContentResolver.SCHEME_CONTENT.equals(uri.getScheme()) || !currentLogin(job)) {
            rejectCancelled(job);
            return;
        }
        active = job;
        workers.execute(() -> {
            try {
                ContentResolver resolver = context.getContentResolver();
                String mime = audioMime(resolver, uri);
                String transcript = job.media.transcribe(mime, () -> resolver.openInputStream(uri),
                        job.operation);
                main.post(() -> finish(job, transcript, null));
            } catch (Exception error) {
                job.operation.cancel();
                main.post(() -> finish(job, null, error));
            }
        });
    }

    private static String audioMime(ContentResolver resolver, Uri uri) throws IOException {
        String mime = resolver.getType(uri);
        if (mime != null) {
            mime = mime.toLowerCase(Locale.ROOT);
        }
        try (Cursor metadata = resolver.query(uri,
                new String[] {OpenableColumns.SIZE, OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (metadata != null && metadata.moveToFirst()) {
                int size = metadata.getColumnIndex(OpenableColumns.SIZE);
                if (size >= 0 && !metadata.isNull(size)
                        && metadata.getLong(size) > HermesMediaClient.MAX_UPLOAD) {
                    throw new HermesMediaClient.AudioFailure("native.the_audio_file_is_larger_than_25_mb");
                }
                int name = metadata.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (!HermesMediaClient.supportedMime(mime) && name >= 0 && !metadata.isNull(name)) {
                    mime = mimeFromName(metadata.getString(name));
                }
            }
        }
        if ("application/ogg".equals(mime)) {
            return "audio/ogg";
        }
        return mime;
    }

    private static String mimeFromName(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        if (dot < 0) {
            return null;
        }
        switch (name.substring(dot + 1).toLowerCase(Locale.ROOT)) {
            case "mp3": return "audio/mpeg";
            case "m4a": return "audio/mp4";
            case "aac": return "audio/aac";
            case "flac": return "audio/flac";
            case "wav": return "audio/wav";
            case "opus":
            case "ogg": return "audio/ogg";
            case "webm": return "audio/webm";
            default: return null;
        }
    }

    private void playSpeech(Job job, String text) {
        try {
            boolean fallback;
            try {
                fallback = playStream(job, text);
            } catch (IOException error) {
                // An older server can serve POST speak without exposing the stream.
                int status = httpStatus(error);
                if (job.playing || status != 404 && status != 405 && status != 501) {
                    throw error;
                }
                fallback = true;
            }
            if (fallback) {
                File file = File.createTempFile("hermes-speech-", ".audio", context.getCacheDir());
                try {
                    try (FileOutputStream output = new FileOutputStream(file)) {
                        job.media.synthesize(text, job.operation, output);
                    }
                    HermesDeviceAudio.playFile(file, job.operation, () -> markPlaying(job));
                } finally {
                    file.delete();
                }
            }
            main.post(() -> finish(job, null, null));
        } catch (Exception error) {
            job.operation.cancel();
            main.post(() -> finish(job, null, error));
        }
    }

    private boolean playStream(Job job, String text) throws Exception {
        HermesDeviceAudio.PcmPlayer player = null;
        int rate = 0;
        long deadline = SystemClock.elapsedRealtime() + TimeUnit.MINUTES.toMillis(5);
        try (HermesMediaClient.Speech stream = job.media.beginSpeech(text, job.operation)) {
            while (SystemClock.elapsedRealtime() < deadline) {
                HermesMediaClient.SpeechFrame frame = stream.next(job.operation);
                if (frame.end) {
                    if (player != null) {
                        player.drain(job.operation);
                    } else if (!frame.fallback) {
                        throw new HermesMediaClient.AudioFailure("native.hermes_did_not_return_playable_audio");
                    }
                    return frame.fallback;
                }
                if (frame.sampleRate > 0) {
                    rate = frame.sampleRate;
                } else if (frame.audio != null) {
                    if (player == null) {
                        job.operation.checkActive();
                        player = new HermesDeviceAudio.PcmPlayer(rate);
                    }
                    player.write(frame.audio, job.operation, () -> markPlaying(job));
                }
            }
            throw new HermesMediaClient.AudioFailure("native.read_aloud_exceeded_the_playback_time_limit");
        } finally {
            if (player != null) {
                player.close();
            }
        }
    }

    private void markPlaying(Job job) {
        job.playing = true;
        main.post(() -> {
            if (displaying(job)) {
                emit(SPEECH, "playing", null, null, null);
            }
        });
    }

    private void finish(Job job, String transcript, Exception error) {
        if (active != job) {
            return;
        }
        active = null;
        if (!currentLogin(job)) {
            rejectCancelled(job);
            return;
        }
        if (error != null) {
            String message = message(error, job.kind == Kind.SPEECH
                    ? t("native.hermes_read_aloud_failed") : t("native.hermes_transcription_failed"));
            job.reject(message);
            if (job.kind != Kind.FILE) {
                emit(job.event(), "error", null, null, message);
            }
        } else if (job.kind == Kind.FILE) {
            WritableMap result = Arguments.createMap();
            result.putString("text", transcript);
            job.resolve(result);
        } else {
            job.resolve(null);
            emit(job.event(), "complete", null, transcript, null);
        }
    }

    private Job newJob(Kind kind, Promise promise) {
        ConnectionStore.LoginScope scope = login(promise);
        if (scope == null) {
            return null;
        }
        if (active != null || picking != null || granting != null) {
            promise.reject("MEDIA_BUSY", t("native.finish_the_current_audio_operation_first"));
            return null;
        }
        return new Job(scope, kind, promise);
    }

    private ConnectionStore.LoginScope login(Promise promise) {
        ConnectionStore.LoginScope scope = closed ? null : store.activeLogin();
        if (scope == null) {
            promise.reject("MEDIA_SIGN_IN", t("native.sign_in_to_hermes_first"));
        }
        return scope;
    }

    private Activity foreground(Promise promise) {
        if (isForeground()) {
            return context.getCurrentActivity();
        }
        promise.reject("MEDIA_BACKGROUND", t("native.use_audio_tools_in_the_foreground_workspace"));
        return null;
    }

    private boolean isForeground() {
        return !closed && context.getCurrentActivity() instanceof WorkspaceActivity
                && context.getLifecycleState() == LifecycleState.RESUMED;
    }

    private boolean currentLogin(Job job) {
        return !closed && job.scope.equals(store.activeLogin());
    }

    private boolean displaying(Job job) {
        return active == job && isForeground() && currentLogin(job);
    }

    private void cancelActive() {
        Job job = active;
        active = null;
        if (job != null) {
            rejectCancelled(job);
            if (job.kind != Kind.FILE) {
                emit(job.event(), "idle", null, null, null);
            }
        }
    }

    private void cancelStaleLogin() {
        if (active != null && !currentLogin(active)) {
            cancelActive();
        }
        if (statusJob != null && !currentLogin(statusJob)) {
            rejectCancelled(statusJob);
            statusJob = null;
        }
        if (picking != null && !currentLogin(picking)) {
            rejectCancelled(picking);
            picking = null;
        }
        if (granting != null && !currentLogin(granting)) {
            rejectCancelled(granting);
            granting = null;
        }
    }

    private static void rejectCancelled(Job job) {
        job.operation.cancel();
        job.reject(t("native.audio_operation_canceled"));
    }

    @Override
    public void onHostResume() {
        cancelStaleLogin();
    }

    @Override
    public void onHostPause() {
        // A document picker may temporarily pause the activity before delivering its URI.
        if (active != null && active.kind != Kind.FILE) {
            cancelActive();
        }
    }

    @Override
    public void onHostDestroy() {
        cancelAll();
    }

    private void cancelAll() {
        cancelActive();
        if (picking != null) {
            rejectCancelled(picking);
            picking = null;
        }
        if (granting != null) {
            rejectCancelled(granting);
            granting = null;
        }
        if (statusJob != null) {
            rejectCancelled(statusJob);
            statusJob = null;
        }
    }

    @Override
    public void invalidate() {
        closed = true;
        stopObservingLogin.run();
        context.removeActivityEventListener(documents);
        context.removeLifecycleEventListener(this);
        main.post(() -> {
            cancelAll();
            workers.shutdownNow();
        });
        super.invalidate();
    }

    private void emit(String event, String state, String partial, String text, String message) {
        if (closed || !context.hasActiveReactInstance()) {
            return;
        }
        WritableMap data = Arguments.createMap();
        data.putString("state", state);
        if (partial != null) {
            data.putString("partial", partial);
        }
        if (text != null) {
            data.putString("text", text);
        }
        if (message != null) {
            data.putString("message", message);
        }
        context.emitDeviceEvent(event, data);
    }

    private static WritableMap statusMap(HermesMediaClient.Status status) {
        WritableMap result = Arguments.createMap();
        putNullableBoolean(result, "sttStreaming", status.sttStreaming);
        result.putString("sttMode", status.sttMode);
        result.putString("ttsMode", status.ttsMode);
        result.putString("liveMode", status.liveMode);
        putNullableBoolean(result, "gptLiveAvailable", status.gptLiveAvailable);
        return result;
    }

    private static void putNullableBoolean(WritableMap map, String key, Boolean value) {
        if (value == null) {
            map.putNull(key);
        } else {
            map.putBoolean(key, value);
        }
    }

    private static String message(Exception error, String fallback) {
        if (error instanceof ConnectionStore.SignInRequiredException) {
            return t("native.sign_in_to_hermes_first");
        }
        int status = httpStatus(error);
        if (status == 401 || status == 4401) {
            return t("native.sign_in_to_hermes_first");
        }
        if (status == 403 || status == 4403) {
            return t("native.this_hermes_login_does_not_have_audio_permissions");
        }
        if (status == 413) {
            return t("native.the_audio_file_exceeds_the_hermes_size_limit");
        }
        if (error instanceof InterruptedIOException) {
            return fallback;
        }
        return error instanceof HermesMediaClient.AudioFailure
                || error instanceof HermesMediaClient.TransportError ? error.getMessage() : fallback;
    }

    private static int httpStatus(Exception error) {
        return error instanceof HermesClient.HttpException
                ? ((HermesClient.HttpException) error).statusCode
                : error instanceof HermesMediaClient.TransportError
                ? ((HermesMediaClient.TransportError) error).status : 0;
    }

    private enum Kind { STATUS, FILE, LIVE, SPEECH }

    private final class Job {
        final ConnectionStore.LoginScope scope;
        final Kind kind;
        final HermesMediaClient media;
        final HermesMediaClient.Operation operation = new HermesMediaClient.Operation();
        final AtomicReference<String> partial = new AtomicReference<>();
        final AtomicBoolean partialQueued = new AtomicBoolean();
        volatile boolean recording;
        volatile boolean finishing;
        volatile boolean playing;
        private Promise promise;

        Job(ConnectionStore.LoginScope scope, Kind kind, Promise promise) {
            this.scope = scope;
            this.kind = kind;
            this.promise = promise;
            media = new HermesMediaClient(new HermesClient(scope.url, store.authFor(scope)));
        }

        String event() {
            return kind == Kind.LIVE ? TRANSCRIPTION : SPEECH;
        }

        void resolve(Object result) {
            if (promise != null) {
                promise.resolve(result);
                promise = null;
            }
        }

        void reject(String message) {
            if (promise != null) {
                promise.reject("MEDIA_ERROR", message);
                promise = null;
            }
        }
    }
}
