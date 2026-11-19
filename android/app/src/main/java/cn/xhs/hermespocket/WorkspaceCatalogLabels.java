package cn.xhs.hermespocket;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.regex.Pattern;

/** Presents stable route identifiers and the corresponding official labels when they exist. */
final class WorkspaceCatalogLabels {
    private static final Pattern HAN = Pattern.compile("\\p{IsHan}");
    private static final String[][] GROUP_KEYS = {
            {"analytics", "commandCenter.sections.usage"},
            {"approval", "shell.statusbar.toggleApprovalMode"},
            {"audio", "settings.sections.voice"},
            {"billing", "settings.billing.title"},
            {"browser", "assistant.tool.prefixes.browser"},
            {"chat_workspaces", "sidebar.sessions"},
            {"chat_ws", "settings.sections.chat"},
            {"config", "commandCenter.settings"},
            {"config_env", "commandCenter.settings"},
            {"connectors", "settings.billing.freeTier.connectors"},
            {"cron", "sidebar.nav.cron"},
            {"dashboard_ui", "commandCenter.settings"},
            {"desktop_plugins", "settings.nav.plugins"},
            {"files", "sidebar.files"},
            {"gateway", "shell.statusbar.gateway"},
            {"gateway_connections", "shell.statusbar.gateway"},
            {"mcp", "settings.nav.mcp"},
            {"message", "settings.sections.chat"},
            {"messaging", "sidebar.nav.messaging"},
            {"messaging_relay", "sidebar.nav.messaging"},
            {"model", "settings.sections.model"},
            {"models", "modelVisibility.title"},
            {"oauth", "settings.nav.providerAccounts"},
            {"plugin_api", "settings.nav.plugins"},
            {"plugins", "settings.nav.plugins"},
            {"profiles", "profiles.title"},
            {"projects", "sidebar.projects.sectionLabel"},
            {"runtime", "updates.versionDetailsRuntime"},
            {"session", "sidebar.sessions"},
            {"sessions", "sidebar.sessions"},
            {"skills", "skills.tabSkills"},
            {"status", "sidebar.filter.status"},
            {"toolsets", "skills.tabToolsets"},
            {"tools", "catalog.tools"},
            {"uploads", "sidebar.files"},
            {"usage", "commandCenter.sections.usage"},
            {"voice", "settings.sections.voice"},
            {"webapp_access", "settings.gateway.webAccess.title"}
    };
    private static final String[][] LABEL_KEYS = {
            {"rest:GET:/api/status", "sidebar.filter.status"},
            {"rest:GET:/api/sessions", "sidebar.sessions"},
            {"rest:GET:/api/profiles/sessions", "sidebar.sessions"},
            {"rest:GET:/api/profiles", "profiles.title"},
            {"rest:GET:/api/model/options", "modelVisibility.title"},
            {"rest:GET:/api/skills", "skills.tabSkills"},
            {"rest:GET:/api/mcp/servers", "settings.nav.mcp"},
            {"rest:GET:/api/dashboard/plugins", "settings.nav.plugins"},
            {"rest:GET:/api/files", "sidebar.files"},
            {"rest:GET:/api/logs", "sidebar.logs"},
            {"rest:GET:/api/cron/jobs", "sidebar.nav.cron"},
            {"rest:GET:/api/providers/oauth", "settings.nav.providerAccounts"},
            {"rpc:session.create", "sidebar.nav.new-session"},
            {"rpc:session.list", "sidebar.sessions"},
            {"rpc:profiles.list", "profiles.title"},
            {"rpc:projects.list", "sidebar.projects.sectionLabel"},
            {"rpc:model.options", "modelVisibility.title"}
    };

    private WorkspaceCatalogLabels() {
    }

    static void present(Context context, JSONObject row, String locale) throws JSONException {
        String group = row.optString("groupId");
        String kind = row.getString("kind");
        String method = row.getString("method");
        String label = "rpc".equals(kind) ? method : method + " " + row.getString("path");
        localField(context, row, locale, "group", keyFor(GROUP_KEYS, group), group.isEmpty() ? kind : group);
        localField(context, row, locale, "label", keyFor(LABEL_KEYS, row.getString("id")), label);
        String description = row.optString("upstreamDescription");
        row.remove("description");
        if (!description.isEmpty() && ("zh-CN".equals(locale) || !HAN.matcher(description).find())) {
            row.put("description", description);
        } else if (!"zh-CN".equals(locale)) {
            row.remove("upstreamDescription");
        }
        if (!"zh-CN".equals(locale)) {
            clearGeneratedNotes(row);
        }
    }

    private static String keyFor(String[][] labels, String identifier) {
        for (String[] pair : labels) {
            if (pair[0].equals(identifier)) {
                return pair[1];
            }
        }
        return null;
    }

    private static void localField(Context context, JSONObject object, String locale, String field, String key,
            String fallback) throws JSONException {
        String value = key == null ? null : HermesLocaleStore.officialTextAt(
                context, locale, key, Collections.emptyMap());
        if (value != null) {
            object.put(field + "Key", key);
        }
        object.put(field, value == null ? fallback : value);
    }

    private static void clearGeneratedNotes(JSONObject row) throws JSONException {
        row.getJSONObject("constraints").remove("notes");
        row.getJSONObject("response").remove("description");
        JSONObject params = row.getJSONObject("params");
        for (String scope : new String[]{"path", "query", "body", "headers"}) {
            JSONArray fields = params.optJSONArray(scope);
            if (fields == null) {
                continue;
            }
            for (int index = 0; index < fields.length(); index++) {
                JSONObject field = fields.getJSONObject(index);
                if (HAN.matcher(field.optString("description")).find()) {
                    field.remove("description");
                }
            }
        }
    }
}
