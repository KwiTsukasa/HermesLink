package cn.xhs.hermespocket;

import static cn.xhs.hermespocket.HermesText.t;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Describes operations from the Webapp contract bundled with the application. */
public final class WorkspaceCatalog {
    public static final String ASSET_NAME = "webapp-contract.json";
    private static final int MAX_CATALOG_SIZE = 4 * 1024 * 1024;

    public final String version;
    public final List<Entry> entries;
    private final Map<String, Entry> byId;

    private WorkspaceCatalog(JSONObject source) throws JSONException {
        if (source.getInt("schemaVersion") != 1) {
            throw new JSONException("Unsupported Workspace catalog schema");
        }
        version = source.getJSONObject("upstream").getString("version");
        JSONArray rows = source.getJSONArray("entries");
        Map<String, Entry> parsed = new LinkedHashMap<>();
        for (int index = 0; index < rows.length(); index++) {
            Entry entry = new Entry(rows.getJSONObject(index));
            if (parsed.put(entry.id, entry) != null) {
                throw new JSONException("Duplicate Workspace operation");
            }
        }
        byId = Collections.unmodifiableMap(parsed);
        entries = Collections.unmodifiableList(new ArrayList<>(parsed.values()));
    }

    /** Read only from a trusted, build-time asset. The caller closes the input. */
    public static WorkspaceCatalog load(InputStream source) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int size; (size = source.read(buffer)) != -1;) {
            if (size > MAX_CATALOG_SIZE - bytes.size()) {
                throw new HermesText.Failure("native.the_hermes_api_catalog_is_too_large");
            }
            bytes.write(buffer, 0, size);
        }
        try {
            return new WorkspaceCatalog(new JSONObject(
                    new String(bytes.toByteArray(), StandardCharsets.UTF_8)));
        } catch (JSONException error) {
            throw new HermesText.Failure(error, "native.could_not_read_the_bundled_hermes_api_catalog");
        }
    }

    public Entry find(String id) {
        return byId.get(id);
    }

    public static final class Entry {
        public final String id;
        public final String kind;
        public final String method;
        public final String path;
        public final String group;
        public final String label;
        public final String description;
        public final String access;
        public final String auth;
        public final String sessionBinding;
        public final boolean mutating;
        public final boolean confirmationRequired;
        public final boolean sensitiveResponse;
        public final String contentType;
        public final String responseFormat;
        public final List<Field> pathFields;
        public final List<Field> queryFields;
        public final List<Field> bodyFields;
        public final List<Field> headerFields;

        private Entry(JSONObject row) throws JSONException {
            id = row.getString("id");
            kind = row.getString("kind");
            method = row.getString("method");
            path = row.getString("path");
            group = row.optString("group");
            label = row.optString("label", id);
            description = row.optString("description");
            JSONObject params = row.getJSONObject("params");
            contentType = params.isNull("contentType") ? "" : params.optString("contentType");
            pathFields = fields(params.optJSONArray("path"));
            queryFields = fields(params.optJSONArray("query"));
            bodyFields = fields(params.optJSONArray("body"));
            headerFields = fields(params.optJSONArray("headers"));
            JSONObject constraints = row.getJSONObject("constraints");
            access = constraints.getString("access");
            auth = constraints.optString("auth");
            sessionBinding = constraints.optString("sessionBinding");
            mutating = constraints.getBoolean("mutating");
            confirmationRequired = constraints.getBoolean("confirmationRequired");
            sensitiveResponse = constraints.optBoolean("sensitiveResponse");
            responseFormat = row.getJSONObject("response").getString("format");
        }
    }

    public static final class Field {
        public final String name;
        public final String type;
        public final boolean required;
        public final boolean nullable;
        public final Object defaultValue;
        public final String description;
        final JSONObject schema;

        private Field(JSONObject row) throws JSONException {
            name = row.getString("name");
            type = row.getString("type");
            required = row.optBoolean("required");
            nullable = row.optBoolean("nullable");
            defaultValue = row.isNull("default") ? null : row.opt("default");
            description = row.optString("description");
            schema = row.optJSONObject("schema");
        }

        boolean isFile() {
            return type.equals("file") || isFileArray();
        }

        boolean isFileArray() {
            JSONObject items = schema == null ? null : schema.optJSONObject("items");
            return type.equals("array") && items != null && items.optString("type").equals("file");
        }
    }

    private static List<Field> fields(JSONArray rows) throws JSONException {
        if (rows == null) {
            return Collections.emptyList();
        }
        List<Field> fields = new ArrayList<>();
        for (int index = 0; index < rows.length(); index++) {
            fields.add(new Field(rows.getJSONObject(index)));
        }
        return Collections.unmodifiableList(fields);
    }
}
