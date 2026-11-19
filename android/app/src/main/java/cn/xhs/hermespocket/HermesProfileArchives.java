package cn.xhs.hermespocket;

import static cn.xhs.hermespocket.HermesText.t;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Adapts the Webapp's host-path profile routes to user-selected Android files. */
final class HermesProfileArchives {
    static final long MAX_ARCHIVE = 100L * 1024 * 1024;
    private static final long MAX_EXPANDED = 512L * 1024 * 1024;
    private static final int MAX_MEMBERS = 50000;
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final MediaType GZIP = MediaType.get("application/gzip");
    private static final Set<String> PRIVATE_MEMBERS = Set.of("credentials", "auth.lock", "vault.key",
            "vault.json.enc", "mcp-tokens", "bot-desktop", ".anthropic_oauth.json", ".config/gh",
            ".npmrc", "google_token.json", "google_oauth.json", "google_oauth_pending.json",
            "google_client_secret.json", "webhook_subscriptions.json", "sign-in-registration.json");

    private final HttpUrl base;
    private final HermesClient client;

    HermesProfileArchives(String server, HermesClient.Auth auth) {
        base = HttpUrl.get(HermesClient.normalizeBaseUrl(server));
        client = new HermesClient(base.toString(), auth);
    }

    static boolean validProfile(String name, boolean clone) {
        return name != null && name.matches("[a-z0-9][a-z0-9_-]{0,63}")
                && (!clone || !"default".equals(name));
    }

    void exportArchive(String name, File temporary, HermesClient.RequestRead read)
            throws IOException, JSONException {
        if (!validProfile(name, false)) {
            throw new ArchiveFailure("native.select_a_valid_hermes_profile");
        }
        HttpUrl export = base.resolve("api/profiles/").newBuilder().addPathSegment(name)
                .addPathSegment("export").build();
        JSONObject generated = json("POST", export, RequestBody.create("{}", JSON), read);
        String archive = field(generated, "archive");
        if (!Boolean.TRUE.equals(generated.opt("ok")) || !isGeneratedExport(name, archive)) {
            throw new ArchiveFailure("native.hermes_did_not_return_the_generated_profile_archive");
        }
        HttpUrl fetch = base.resolve("api/fs/download").newBuilder().addQueryParameter("path", archive).build();
        try (Response response = client.executeArchiveRequest(fetch, "GET",
                "application/gzip, application/octet-stream", null, read)) {
            ResponseBody body = response.body();
            if (body == null || body.contentLength() > MAX_ARCHIVE) {
                throw sizeError();
            }
            try (InputStream input = body.byteStream(); OutputStream output = new FileOutputStream(temporary)) {
                transfer(input, output, MAX_ARCHIVE, read);
            }
        }
        validateArchive(temporary, read);
    }

    void importArchive(String name, File archive, HermesClient.RequestRead read)
            throws IOException, JSONException {
        if (!validProfile(name, true)) {
            throw new ArchiveFailure("native.enter_a_new_profile_name_importing_into_default_is_disabled");
        }
        validateArchive(archive, read);
        JSONObject root = json("GET", base.resolve("api/files"), null, read);
        String directory = field(root, "path");
        verifyManagedPath(directory);
        String suffix = "hermes-phone-" + UUID.randomUUID() + ".tar.gz";
        String path = directory.replaceFirst("[/\\\\]+$", "") + (directory.contains("\\") ? "\\" : "/") + suffix;
        verifyManagedPath(path);
        RequestBody upload = new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("path", path)
                .addFormDataPart("overwrite", "false")
                .addFormDataPart("file", suffix, RequestBody.create(archive, GZIP))
                .build();
        JSONObject stored = json("POST", base.resolve("api/files/upload-stream"), upload, read);
        String staged = field(stored, "path");
        if (!Boolean.TRUE.equals(stored.opt("ok")) || !comparable(path).equals(comparable(staged))) {
            throw new ArchiveFailure("native.hermes_did_not_confirm_the_temporary_archive_location");
        }
        boolean acknowledged = false;
        try {
            JSONObject request = new JSONObject().put("archive", staged).put("name", name);
            JSONObject imported = json("POST", base.resolve("api/profiles/import"),
                    RequestBody.create(request.toString(), JSON), read);
            acknowledged = true;
            if (!Boolean.TRUE.equals(imported.opt("ok")) || !name.equals(field(imported, "name"))) {
                throw new ArchiveFailure("native.could_not_confirm_the_import_result_refresh_the_profile_list");
            }
        } catch (HermesClient.HttpException refused) {
            acknowledged = true;
            throw refused;
        } finally {
            // The upload used a fresh random name and overwrite=false. Never delete a caller path.
            // A lost POST response can leave its server-side work still reading the archive.
            if (acknowledged) {
                try {
                    HttpUrl cleanup = base.resolve("api/files").newBuilder()
                            .addQueryParameter("path", staged).build();
                    json("DELETE", cleanup, null, new HermesClient.RequestRead());
                } catch (IOException | JSONException ignored) {
                    // Successful profile import is independent of the temporary upload cleanup.
                }
            }
        }
    }

    private static boolean isGeneratedExport(String profile, String path) throws ArchiveFailure {
        verifyManagedPath(path);
        String[] parts = path.replace('\\', '/').split("/");
        return parts.length >= 2 && parts[parts.length - 2].matches(
                "(?:profile-exports|\\.hermes-profile-exports|hermes-profile-exports(?:-[0-9]+)?)")
                && parts[parts.length - 1].matches(java.util.regex.Pattern.quote(profile)
                + "-[0-9]{8}-[0-9]{6}\\.tar\\.gz");
    }

    private static void verifyManagedPath(String path) throws ArchiveFailure {
        if (path.length() > 4096 || !path.replace('\\', '/').matches("(?:/|[A-Za-z]:/).*")) {
            throw new ArchiveFailure("native.hermes_did_not_return_an_accessible_host_archive_path");
        }
        try {
            WorkspaceClient.validateFilePath(path);
        } catch (IllegalArgumentException error) {
            throw new ArchiveFailure("native.the_host_archive_path_returned_by_hermes_is_unsupported");
        }
    }

    private static String comparable(String path) {
        String normalized = path.replace('\\', '/');
        return normalized.matches("^[A-Z]:/.*")
                ? normalized.substring(0, 1).toLowerCase(Locale.ROOT) + normalized.substring(1) : normalized;
    }

    private JSONObject json(String method, HttpUrl url, RequestBody request,
                            HermesClient.RequestRead read) throws IOException, JSONException {
        try (Response response = client.executeArchiveRequest(url, method, "application/json", request, read)) {
            ResponseBody body = response.body();
            MediaType type = body == null ? null : body.contentType();
            if (type == null || !"application".equals(type.type()) || !"json".equals(type.subtype())
                    || body.source().request(1024 * 1024 + 1L)) {
                throw new ArchiveFailure("native.hermes_returned_an_invalid_archive_result");
            }
            return new JSONObject(body.source().readUtf8());
        }
    }

    private static String field(JSONObject json, String name) {
        Object value = json.opt(name);
        return value instanceof String ? (String) value : "";
    }

    static void transfer(InputStream input, OutputStream output, long max, HermesClient.RequestRead read)
            throws IOException {
        byte[] bytes = new byte[32768];
        long total = 0;
        int readBytes;
        while ((readBytes = input.read(bytes)) != -1) {
            read.checkCancelled();
            total += readBytes;
            if (total > max) {
                throw sizeError();
            }
            output.write(bytes, 0, readBytes);
        }
        if (total == 0) {
            throw invalidArchive();
        }
    }

    static void validateArchive(File archive, HermesClient.RequestRead read) throws IOException {
        if (archive.length() <= 0 || archive.length() > MAX_ARCHIVE) {
            throw sizeError();
        }
        long expanded = 0;
        int members = 0;
        String root = null;
        try (InputStream file = new FileInputStream(archive);
             InputStream gzip = new GZIPInputStream(file);
             TarArchiveInputStream tar = new TarArchiveInputStream(gzip)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                read.checkCancelled();
                long size = entry.getSize();
                if (++members > MAX_MEMBERS || size < 0 || size > MAX_EXPANDED - expanded) {
                    throw sizeError();
                }
                expanded += size;
                String path = entry.getName().replaceFirst("/$", "");
                if (!entry.isCheckSumOK() || !entry.isDirectory() && !entry.isFile()
                        || entry.isSparse() || path.length() > 4096) {
                    throw invalidArchive();
                }
                checkArchivePath(path);
                String first = path.split("/", 2)[0];
                if (root == null) {
                    root = first;
                } else if (!root.equals(first)) {
                    throw invalidArchive();
                }
            }
            int remaining;
            int padding = 0;
            while ((remaining = gzip.read()) != -1) {
                read.checkCancelled();
                if (remaining != 0 || ++padding > 65536) {
                    throw invalidArchive();
                }
            }
        } catch (ArchiveFailure | java.io.InterruptedIOException error) {
            throw error;
        } catch (IOException | IllegalArgumentException error) {
            throw invalidArchive();
        }
        if (members < 2 || !validProfile(root, false)) {
            throw invalidArchive();
        }
    }

    private static void checkArchivePath(String path) throws ArchiveFailure {
        try {
            WorkspaceClient.validateFilePath(path);
        } catch (IllegalArgumentException error) {
            throw new ArchiveFailure("native.the_archive_includes_credentials_invalid_paths_or_unsupported_names");
        }
        if (path.startsWith("/") || path.indexOf('\\') >= 0) {
            throw invalidArchive();
        }
        for (String part : path.toLowerCase(Locale.ROOT).split("/")) {
            if (PRIVATE_MEMBERS.contains(part)) {
                throw new ArchiveFailure("native.the_archive_includes_credential_files_use_an_archive_exported_and");
            }
        }
        if (path.toLowerCase(Locale.ROOT).contains("/.config/gh/")) {
            throw new ArchiveFailure("native.the_archive_includes_credential_files_use_an_archive_exported_and");
        }
    }

    private static ArchiveFailure invalidArchive() {
        return new ArchiveFailure("native.choose_a_hermes_tar_gz_archive_with_one_profile_directory");
    }

    private static ArchiveFailure sizeError() {
        return new ArchiveFailure("native.the_archive_exceeds_the_mobile_limits_of_100_mib_uploaded");
    }

    static final class ArchiveFailure extends HermesText.Failure {
        ArchiveFailure(String key, Object... values) {
            super(key, values);
        }
    }
}
