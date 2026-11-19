package cn.xhs.hermespocket;

import static cn.xhs.hermespocket.HermesText.t;

import java.io.IOException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Authenticated, seekable reads of the Webapp media contract. Credentials stay in OkHttp. */
final class HermesResourceClient implements AutoCloseable {
    static final int BLOCK_BYTES = 256 * 1024;
    private static final Pattern RANGE = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)");
    private final HermesClient client;
    private final HttpUrl url;
    private final HermesClient.RequestRead read = new HermesClient.RequestRead();
    private long length = -1;
    private String validator;
    private String validatorHeader;
    private byte[] block;
    private long blockAt;

    static final class Target {
        final String path;
        final String profile;
        final String sessionId;
        final boolean video;

        Target(String path, String profile, String sessionId) {
            if (path == null || path.length() > 4096) {
                throw invalidTarget();
            }
            WorkspaceClient.validateFilePath(path);
            String lower = path.toLowerCase(Locale.ROOT);
            video = lower.matches(".*\\.(?:avi|mkv|mov|mp4|webm)");
            if (!video && !lower.matches(".*\\.(?:flac|m4a|mp3|ogg|opus|wav)")) {
                throw invalidTarget();
            }
            if (profile != null && !profile.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")
                    || sessionId != null && (profile == null
                    || !sessionId.matches("[A-Za-z0-9_-]{1,256}"))) {
                throw invalidTarget();
            }
            this.path = path;
            this.profile = profile;
            this.sessionId = sessionId;
        }

        String name() {
            String path = this.path.replace('\\', '/');
            return path.substring(path.lastIndexOf('/') + 1);
        }

        private static IllegalArgumentException invalidTarget() {
            return new HermesText.InvalidInput("native.choose_a_hermes_audio_or_video_file");
        }
    }

    HermesResourceClient(String server, HermesClient.Auth auth, Target target) {
        HttpUrl base = HttpUrl.get(HermesClient.normalizeBaseUrl(server));
        client = new HermesClient(base.toString(), auth);
        HttpUrl.Builder resource = base.resolve("api/files/stream").newBuilder()
                .addQueryParameter("path", target.path);
        if (target.profile != null) {
            resource.addQueryParameter("profile", target.profile);
        }
        if (target.sessionId != null) {
            resource.addQueryParameter("session_id", target.sessionId);
        }
        url = resource.build();
    }

    synchronized long size() throws IOException {
        read.checkCancelled();
        if (length < 0) {
            try (Response response = client.executeMediaRangeRequest(url, null, null, read)) {
                requireMedia(response, 200);
                long size = positive(response.header("Content-Length"));
                if (size <= 0) {
                    throw new ResourceFailure("native.hermes_did_not_return_a_valid_media_file_size");
                }
                String tag = response.header("ETag");
                String modified = response.header("Last-Modified");
                if (tag != null && tag.matches("\"[!-~]{1,128}\"")) {
                    validator = tag;
                    validatorHeader = "ETag";
                } else if (modified != null && modified.matches("[A-Za-z0-9,: -]{20,64}")) {
                    validator = modified;
                    validatorHeader = "Last-Modified";
                }
                length = size;
            }
        }
        return length;
    }

    synchronized int readAt(long position, byte[] output, int offset, int requested)
            throws IOException {
        if (position < 0 || offset < 0 || requested < 0 || requested > output.length - offset) {
            throw new HermesText.InvalidInput("native.invalid_media_read_position");
        }
        read.checkCancelled();
        if (requested == 0) {
            return 0;
        }
        long total = size();
        if (position >= total) {
            return -1;
        }
        long start = position / BLOCK_BYTES * BLOCK_BYTES;
        if (block == null || blockAt != start) {
            block = fetch(start, (int) Math.min(BLOCK_BYTES, total - start), total);
            blockAt = start;
        }
        int within = (int) (position - blockAt);
        int count = Math.min(requested, block.length - within);
        read.checkCancelled();
        System.arraycopy(block, within, output, offset, count);
        return count;
    }

    private byte[] fetch(long start, int count, long total) throws IOException {
        long end = start + count - 1;
        String range = "bytes=" + start + "-" + end;
        try (Response response = client.executeMediaRangeRequest(url, range, validator, read)) {
            if (response.code() == 200) {
                throw new ResourceFailure("native.hermes_did_not_confirm_the_file_range_the_file_may");
            }
            requireMedia(response, 206);
            String returnedValidator = validatorHeader == null ? null : response.header(validatorHeader);
            if (returnedValidator != null && !validator.equals(returnedValidator)) {
                throw new ResourceFailure("native.the_media_file_changed_reopen_it");
            }
            String coordinates = response.header("Content-Range", "");
            Matcher match = RANGE.matcher(coordinates);
            ResponseBody body = response.body();
            if (!match.matches() || positive(match.group(1)) != start || positive(match.group(2)) != end
                    || positive(match.group(3)) != total || body == null
                    || body.contentLength() >= 0 && body.contentLength() != count
                    || body.source().request(count + 1L)) {
                throw new ResourceFailure("native.hermes_returned_an_invalid_media_range");
            }
            byte[] data = body.source().readByteArray();
            if (data.length != count) {
                throw new ResourceFailure("native.hermes_did_not_return_the_complete_requested_media_range");
            }
            return data;
        }
    }

    private static void requireMedia(Response response, int expected) throws IOException {
        int status = response.code();
        if (status != expected) {
            if (status == 401) {
                throw new ResourceFailure("native.your_hermes_session_expired_sign_in_again");
            }
            if (status == 403 || status == 404) {
                throw new ResourceFailure("native.this_file_no_longer_exists_or_this_login_cannot_access");
            }
            if (status == 415) {
                throw new ResourceFailure("native.this_file_type_cannot_be_streamed_by_hermes");
            }
            throw new ResourceFailure("native.hermes_could_not_read_the_media_file", status);
        }
        String encoding = response.header("Content-Encoding", "identity");
        MediaType type = MediaType.parse(response.header("Content-Type", ""));
        if (!"identity".equalsIgnoreCase(encoding) || type == null
                || !("audio".equals(type.type()) || "video".equals(type.type())
                || "application".equals(type.type()) && ("octet-stream".equals(type.subtype())
                || "ogg".equals(type.subtype())))) {
            throw new ResourceFailure("native.hermes_did_not_return_playable_media");
        }
    }

    private static long positive(String value) {
        if (value == null || !value.matches("\\d{1,19}")) {
            return -1;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException error) {
            return -1;
        }
    }

    @Override
    public void close() {
        read.cancel();
    }

    static final class ResourceFailure extends HermesText.Failure {
        ResourceFailure(String key, Object... values) {
            super(key, values);
        }
    }
}
