package cn.xhs.hermespocket;

import static cn.xhs.hermespocket.HermesText.t;

import android.annotation.SuppressLint;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.SystemClock;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import okio.ByteString;

/** Foreground device capture and playback. Each call owns its hardware until finally. */
final class HermesDeviceAudio {
    private static final int RATE = HermesMediaClient.INPUT_RATE;
    private static final int PCM = AudioFormat.ENCODING_PCM_16BIT;
    private static final long RECORD_LIMIT_MS = TimeUnit.MINUTES.toMillis(3);
    private static final long PLAYBACK_STALL_MS = 10000;
    private static final AudioAttributes SPEECH = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build();

    private HermesDeviceAudio() {}

    @SuppressLint("MissingPermission")
    static void record(HermesMediaClient.Transcription stream, HermesMediaClient.Operation operation,
                       BooleanSupplier finishing, Runnable started) throws IOException {
        operation.checkActive();
        int minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, PCM);
        if (minimum <= 0) {
            throw new HermesMediaClient.AudioFailure("native.this_device_cannot_provide_the_microphone_format_needed_for_live");
        }
        AudioRecord recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE,
                AudioFormat.CHANNEL_IN_MONO, PCM, Math.max(minimum, RATE / 5));
        try {
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new HermesMediaClient.AudioFailure("native.could_not_open_the_microphone");
            }
            operation.checkActive();
            recorder.startRecording();
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new HermesMediaClient.AudioFailure("native.the_microphone_did_not_start_recording");
            }
            started.run();
            long expires = SystemClock.elapsedRealtime() + RECORD_LIMIT_MS;
            short[] samples = new short[RATE / 25];
            byte[] bytes = new byte[samples.length * 2];
            while (!finishing.getAsBoolean() && SystemClock.elapsedRealtime() < expires) {
                operation.checkActive();
                stream.checkHealthy();
                int count = recorder.read(samples, 0, samples.length, AudioRecord.READ_NON_BLOCKING);
                if (count < 0) {
                    throw new HermesMediaClient.AudioFailure("native.could_not_read_the_microphone");
                }
                if (count == 0) {
                    SystemClock.sleep(15);
                    continue;
                }
                for (int i = 0; i < count; i++) {
                    bytes[i * 2] = (byte) samples[i];
                    bytes[i * 2 + 1] = (byte) (samples[i] >>> 8);
                }
                operation.checkActive();
                stream.push(bytes, count * 2);
            }
            operation.checkActive();
        } finally {
            try {
                if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    recorder.stop();
                }
            } finally {
                recorder.release();
            }
        }
    }

    static final class PcmPlayer implements AutoCloseable {
        private final AudioTrack track;
        private final ByteBuffer buffer = ByteBuffer.allocateDirect(8192);
        private boolean drained;
        private boolean playing;
        private long framesWritten;

        PcmPlayer(int sampleRate) throws IOException {
            int minimum = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, PCM);
            if (minimum <= 0) {
                throw new HermesMediaClient.AudioFailure("native.this_device_does_not_support_the_audio_format_returned_by");
            }
            track = new AudioTrack.Builder()
                    .setAudioAttributes(SPEECH)
                    .setAudioFormat(new AudioFormat.Builder().setEncoding(PCM).setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(Math.max(minimum, sampleRate / 5))
                    .build();
            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                track.release();
                throw new HermesMediaClient.AudioFailure("native.could_not_open_the_audio_player");
            }
            if (Build.VERSION.SDK_INT >= 31) {
                try {
                    track.setStartThresholdInFrames(Math.min(track.getBufferSizeInFrames(), sampleRate / 100));
                } catch (RuntimeException error) {
                    track.release();
                    throw error;
                }
            }
        }

        void write(ByteString audio, HermesMediaClient.Operation operation, Runnable started)
                throws IOException {
            byte[] bytes = audio.toByteArray();
            int offset = 0;
            long lastWrite = SystemClock.elapsedRealtime();
            while (offset < bytes.length) {
                int length = Math.min(bytes.length - offset, buffer.capacity());
                buffer.clear();
                buffer.put(bytes, offset, length).flip();
                while (buffer.hasRemaining()) {
                    operation.checkActive();
                    int written = track.write(buffer, buffer.remaining(), AudioTrack.WRITE_NON_BLOCKING);
                    if (written < 0 || (written & 1) != 0) {
                        throw new HermesMediaClient.AudioFailure("native.could_not_play_the_hermes_read_aloud_audio");
                    }
                    if (written > 0) {
                        framesWritten += written / 2;
                        lastWrite = SystemClock.elapsedRealtime();
                        // Prime the first buffer before starting the speaker to reduce underruns.
                        if (!playing) {
                            track.play();
                            playing = true;
                            started.run();
                        }
                    } else if (SystemClock.elapsedRealtime() - lastWrite >= PLAYBACK_STALL_MS) {
                        throw new HermesMediaClient.AudioFailure("native.playback_timed_out_on_this_device");
                    } else {
                        SystemClock.sleep(15);
                    }
                }
                offset += length;
            }
        }

        void drain(HermesMediaClient.Operation operation) throws IOException {
            long lastProgress = SystemClock.elapsedRealtime();
            long previous = 0;
            while (previous < framesWritten) {
                operation.checkActive();
                long played = Integer.toUnsignedLong(track.getPlaybackHeadPosition());
                if (played != previous) {
                    previous = played;
                    lastProgress = SystemClock.elapsedRealtime();
                } else if (SystemClock.elapsedRealtime() - lastProgress >= PLAYBACK_STALL_MS) {
                    throw new HermesMediaClient.AudioFailure("native.playback_did_not_finish_on_this_device");
                }
                SystemClock.sleep(15);
            }
            drained = true;
        }

        @Override
        public void close() {
            try {
                if (track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                    if (drained) {
                        track.stop();
                    } else {
                        track.pause();
                        track.flush();
                    }
                }
            } finally {
                track.release();
            }
        }
    }

    static void playFile(File file, HermesMediaClient.Operation operation, Runnable started)
            throws IOException, InterruptedException {
        MediaPlayer player = new MediaPlayer();
        CountDownLatch prepared = new CountDownLatch(1);
        CountDownLatch complete = new CountDownLatch(1);
        AtomicBoolean failed = new AtomicBoolean();
        try (FileInputStream input = new FileInputStream(file)) {
            player.setAudioAttributes(SPEECH);
            player.setOnPreparedListener(ignored -> prepared.countDown());
            player.setOnCompletionListener(ignored -> complete.countDown());
            player.setOnErrorListener((ignored, type, details) -> {
                failed.set(true);
                prepared.countDown();
                complete.countDown();
                return true;
            });
            player.setDataSource(input.getFD());
            player.prepareAsync();
            awaitPlayer(prepared, operation, 20000);
            if (failed.get()) {
                throw new HermesMediaClient.AudioFailure("native.could_not_decode_the_hermes_read_aloud_audio");
            }
            operation.checkActive();
            player.start();
            started.run();
            awaitPlayer(complete, operation, Math.max(15000, (long) player.getDuration() + 15000));
            if (failed.get()) {
                throw new HermesMediaClient.AudioFailure("native.could_not_play_the_hermes_read_aloud_audio");
            }
        } finally {
            player.release();
        }
    }

    private static void awaitPlayer(CountDownLatch signal, HermesMediaClient.Operation operation,
                                    long timeout) throws IOException, InterruptedException {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        while (!signal.await(60, TimeUnit.MILLISECONDS)) {
            operation.checkActive();
            if (SystemClock.elapsedRealtime() >= deadline) {
                throw new HermesMediaClient.AudioFailure("native.playback_timed_out_on_this_device");
            }
        }
        operation.checkActive();
    }
}
