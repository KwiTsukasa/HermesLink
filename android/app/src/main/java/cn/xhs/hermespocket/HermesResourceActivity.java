package cn.xhs.hermespocket;

import static cn.xhs.hermespocket.HermesText.t;

import android.app.Activity;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaDataSource;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** MediaPlayer reads authorized file ranges through MediaDataSource. */
public final class HermesResourceActivity extends Activity {
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final String LAUNCH_TOKEN = "hermesResource";
    private static final AtomicReference<Launch> PENDING = new AtomicReference<>();

    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread thread = new HandlerThread("Hermes media preview");
    private Handler playback;
    private ConnectionStore store;
    private Runnable stopObservingLogin;
    private Launch launch;
    private Preview current;
    private boolean resumed;
    private boolean autoplay = true;
    private boolean scrubbing;
    private FrameLayout videoArea;
    private SurfaceView video;
    private SurfaceHolder display;
    private TextView status;
    private TextView time;
    private Button play;
    private SeekBar seek;

    static void open(Activity parent, ConnectionStore.LoginScope scope, HermesResourceClient.Target target) {
        Launch authorized = new Launch(scope, target);
        PENDING.set(authorized);
        try {
            parent.startActivity(new Intent(parent, HermesResourceActivity.class)
                    .putExtra(LAUNCH_TOKEN, authorized.token));
        } catch (RuntimeException error) {
            PENDING.compareAndSet(authorized, null);
            throw error;
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Object retained = getLastNonConfigurationInstance();
        Launch pending = PENDING.get();
        if (retained instanceof Launch) {
            launch = (Launch) retained;
        } else if (pending != null && pending.token.equals(getIntent().getStringExtra(LAUNCH_TOKEN))
                && PENDING.compareAndSet(pending, null)) {
            launch = pending;
        }
        store = new ConnectionStore(getApplicationContext());
        if (launch == null || !launch.scope.equals(store.activeLogin())) {
            finish();
            return;
        }
        thread.start();
        playback = new Handler(thread.getLooper());
        stopObservingLogin = store.observeLoginChanges(() -> main.post(() -> {
            if (!launch.scope.equals(store.activeLogin())) {
                releasePreview();
                finish();
            }
        }));
        buildView();
    }

    @Override
    public Object onRetainNonConfigurationInstance() {
        return launch;
    }

    private void buildView() {
        PocketStyle style = new PocketStyle(this);
        LinearLayout root = style.column();
        root.setPadding(style.dp(16), style.dp(8), style.dp(16), style.dp(16));
        root.setBackgroundColor(style.background);
        setContentView(root);
        style.applyWindow(getWindow(), root, false);

        LinearLayout titleBar = new LinearLayout(this);
        titleBar.setGravity(Gravity.CENTER_VERTICAL);
        titleBar.addView(style.iconButton(PocketStyle.Icon.BACK, t("native.back"), false, this::finish),
                new LinearLayout.LayoutParams(style.dp(48), style.dp(48)));
        TextView title = style.label(launch.target.name(), 17, style.text, true);
        titleBar.addView(title, new LinearLayout.LayoutParams(0, WRAP, 1));
        root.addView(titleBar, new LinearLayout.LayoutParams(MATCH, WRAP));

        if (launch.target.video) {
            videoArea = new FrameLayout(this);
            videoArea.setBackgroundColor(android.graphics.Color.BLACK);
            root.addView(videoArea, new LinearLayout.LayoutParams(MATCH, 0, 1));
            video = new SurfaceView(this);
            videoArea.addView(video, new FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER));
            video.getHolder().addCallback(new SurfaceHolder.Callback() {
                @Override
                public void surfaceCreated(SurfaceHolder holder) {
                    display = holder;
                    startInitial();
                }

                @Override
                public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

                @Override
                public void surfaceDestroyed(SurfaceHolder holder) {
                    display = null;
                    releasePreview();
                }
            });
        }

        status = style.label("", 14, style.secondary, false);
        status.setPadding(style.dp(4), style.dp(10), style.dp(4), style.dp(4));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        root.addView(status, new LinearLayout.LayoutParams(MATCH, WRAP));
        seek = new SeekBar(this);
        seek.setEnabled(false);
        seek.setContentDescription(t("native.playback_position"));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean user) {
                if (user) {
                    showTime(progress, seek.getMax());
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                scrubbing = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                scrubbing = false;
                Preview selected = current;
                if (selected != null) {
                    int position = bar.getProgress();
                    playback.post(() -> selected.seek(position));
                }
            }
        });
        root.addView(seek, new LinearLayout.LayoutParams(MATCH, WRAP));
        time = style.label("", 13, style.secondary, false);
        root.addView(time, new LinearLayout.LayoutParams(MATCH, WRAP));
        play = style.button(t("native.play"), true, this::togglePlayback);
        root.addView(play, new LinearLayout.LayoutParams(MATCH, WRAP));
        showTime(0, 0);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (play != null) {
            startInitial();
        }
    }

    private void startInitial() {
        if (autoplay && resumed && (!launch.target.video || display != null)) {
            autoplay = false;
            startPreview();
        }
    }

    @Override
    protected void onPause() {
        resumed = false;
        releasePreview();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (stopObservingLogin != null) {
            stopObservingLogin.run();
        }
        releasePreview();
        if (playback != null) {
            thread.quitSafely();
        }
        super.onDestroy();
    }

    private void startPreview() {
        if (!resumed || current != null || launch.target.video && display == null) {
            return;
        }
        if (!launch.scope.equals(store.activeLogin())) {
            finish();
            return;
        }
        status.setText(t("native.loading_hermes_media"));
        play.setEnabled(false);
        Preview selected = new Preview(display);
        current = selected;
        playback.post(selected::open);
    }

    private void togglePlayback() {
        Preview selected = current;
        if (selected == null) {
            startPreview();
        } else {
            playback.post(selected::toggle);
        }
    }

    private void releasePreview() {
        Preview prior = current;
        current = null;
        if (prior != null) {
            prior.cancelled = true;
            prior.resource.close(); // Unblock a pending read before the player is released.
            playback.post(prior::release);
        }
        if (play != null) {
            play.setEnabled(true);
            play.setText(t("native.play"));
            seek.setEnabled(false);
            status.setText("");
            showTime(0, 0);
        }
    }

    private void showTime(int position, int duration) {
        time.setText(timestamp(position) + " / " + timestamp(duration));
    }

    private static String timestamp(int millis) {
        int seconds = millis / 1000;
        return String.format(HermesText.locale(), "%d:%02d", seconds / 60, seconds % 60);
    }

    private void resizeVideo(int width, int height) {
        if (videoArea == null || width <= 0 || height <= 0 || videoArea.getWidth() <= 0
                || videoArea.getHeight() <= 0) {
            return;
        }
        double scale = Math.min((double) videoArea.getWidth() / width, (double) videoArea.getHeight() / height);
        video.setLayoutParams(new FrameLayout.LayoutParams((int) (width * scale),
                (int) (height * scale), Gravity.CENTER));
    }

    private final class Preview {
        final HermesResourceClient resource;
        final SurfaceHolder surface;
        volatile boolean cancelled;
        MediaPlayer player;
        boolean ready;
        boolean completed;
        private final Runnable timer = this::refreshProgress;

        Preview(SurfaceHolder display) {
            surface = display;
            resource = new HermesResourceClient(launch.scope.url, store.authFor(launch.scope), launch.target);
        }

        void open() {
            try {
                long size = resource.size();
                if (cancelled) {
                    return;
                }
                player = new MediaPlayer();
                player.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
                player.setDataSource(new MediaDataSource() {
                    @Override
                    public int readAt(long position, byte[] bytes, int offset, int requested) throws IOException {
                        return resource.readAt(position, bytes, offset, requested);
                    }

                    @Override
                    public long getSize() {
                        return size;
                    }

                    @Override
                    public void close() {
                        resource.close();
                    }
                });
                if (surface != null) {
                    player.setDisplay(surface);
                }
                player.setOnVideoSizeChangedListener((source, width, height) ->
                        main.post(() -> {
                            if (current == this) {
                                resizeVideo(width, height);
                            }
                        }));
                player.setOnPreparedListener(ignored -> {
                    if (cancelled) {
                        return;
                    }
                    ready = true;
                    start();
                });
                player.setOnCompletionListener(ignored -> {
                    completed = true;
                    refreshProgress();
                });
                player.setOnErrorListener((ignored, what, extra) -> {
                    fail(t("native.the_phone_could_not_play_this_media_or_the_file"));
                    return true;
                });
                player.prepareAsync();
            } catch (IOException | RuntimeException error) {
                String message = error instanceof HermesResourceClient.ResourceFailure ? error.getMessage()
                        : t("native.could_not_load_hermes_media_check_the_connection_and_try");
                fail(message);
            }
        }

        void toggle() {
            if (!ready || cancelled) {
                return;
            }
            try {
                if (player.isPlaying()) {
                    player.pause();
                    refreshProgress();
                } else {
                    start();
                }
            } catch (RuntimeException error) {
                fail(t("native.the_phone_could_not_resume_playback"));
            }
        }

        private void start() {
            try {
                if (completed) {
                    player.seekTo(0);
                    completed = false;
                }
                player.start();
                refreshProgress();
            } catch (RuntimeException error) {
                fail(t("native.the_phone_could_not_play_this_media"));
            }
        }

        void seek(int position) {
            if (ready && !cancelled) {
                try {
                    player.seekTo(position);
                    completed = false;
                    refreshProgress();
                } catch (RuntimeException error) {
                    fail(t("native.the_phone_could_not_seek_to_this_position"));
                }
            }
        }

        private void refreshProgress() {
            playback.removeCallbacks(timer);
            if (!ready || cancelled) {
                return;
            }
            try {
                int duration = Math.max(0, player.getDuration());
                int position = Math.max(0, player.getCurrentPosition());
                boolean playing = player.isPlaying();
                boolean ended = completed;
                main.post(() -> {
                    if (current != this) {
                        return;
                    }
                    status.setText("");
                    play.setEnabled(true);
                    play.setText(playing ? t("native.pause") : ended ? t("native.replay") : t("native.play"));
                    seek.setEnabled(duration > 0);
                    if (!scrubbing) {
                        seek.setMax(duration);
                        seek.setProgress(position);
                        showTime(position, duration);
                    }
                });
                if (playing) {
                    playback.postDelayed(timer, 400);
                }
            } catch (RuntimeException error) {
                fail(t("native.could_not_read_the_current_playback_position"));
            }
        }

        private void fail(String message) {
            release();
            main.post(() -> {
                if (current == this) {
                    current = null;
                    status.setText(message);
                    play.setEnabled(true);
                    play.setText(t("native.retry"));
                    seek.setEnabled(false);
                }
            });
        }

        void release() {
            playback.removeCallbacks(timer);
            resource.close();
            ready = false;
            if (player != null) {
                player.release();
                player = null;
            }
        }
    }

    private static final class Launch {
        final String token = UUID.randomUUID().toString();
        final ConnectionStore.LoginScope scope;
        final HermesResourceClient.Target target;

        Launch(ConnectionStore.LoginScope scope, HermesResourceClient.Target target) {
            this.scope = scope;
            this.target = target;
        }
    }
}
