package cn.xhs.hermespocket;

import static cn.xhs.hermespocket.HermesText.t;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pending controls projected by the authority for one attached conversation. */
final class ChatControls {
    final String sessionId;
    final long authorityEpoch;
    final long executionGeneration;
    final boolean running;
    final List<Queued> queued;
    final List<Prompt> prompts;

    ChatControls(JSONObject snapshot, String sessionId) {
        this.sessionId = sessionId;
        authorityEpoch = snapshot.optLong("authority_epoch", -1);
        executionGeneration = snapshot.optLong("execution_generation", -1);
        running = snapshot.optBoolean("running", false) && authorityEpoch >= 0
                && executionGeneration >= 0;
        List<Queued> pendingRows = new ArrayList<>();
        List<Prompt> currentPrompts = new ArrayList<>();
        JSONArray pending = snapshot.optJSONArray("pending");
        for (int i = 0; authorityEpoch >= 0 && pending != null && i < pending.length(); i++) {
            JSONObject row = pending.optJSONObject(i);
            JSONObject ref = row == null ? null : row.optJSONObject("ref");
            if (ref == null || !sessionId.equals(string(ref, "session_id"))
                    || authorityEpoch != row.optLong("authority_epoch", -1)) {
                continue;
            }
            String id = string(row, "admission_id");
            if (!id.isEmpty() && "queued".equals(string(row, "status"))) {
                pendingRows.add(new Queued(id, string(row, "text"),
                        strings(row.optJSONArray("controls")).contains("steer")));
            }
        }
        JSONArray prompts = snapshot.optJSONArray("prompts");
        for (int i = 0; running && prompts != null && i < prompts.length(); i++) {
            JSONObject row = prompts.optJSONObject(i);
            if (row == null || row.optLong("execution_generation", -1) != executionGeneration) {
                continue;
            }
            String id = string(row, "prompt_id");
            String kind = string(row, "kind");
            if (!id.isEmpty() && (kind.equals("approval") || kind.equals("clarify"))) {
                currentPrompts.add(new Prompt(id, kind, row));
            }
        }
        queued = Collections.unmodifiableList(pendingRows);
        this.prompts = Collections.unmodifiableList(currentPrompts);
    }

    boolean sameAuthority(ChatControls other) {
        return other != null && authorityEpoch >= 0 && authorityEpoch == other.authorityEpoch
                && sessionId.equals(other.sessionId);
    }

    boolean sameTurn(ChatControls other) {
        return running && sameAuthority(other) && other.running
                && executionGeneration == other.executionGeneration;
    }

    Queued queued(String id) {
        for (Queued row : queued) {
            if (row.id.equals(id)) {
                return row;
            }
        }
        return null;
    }

    Prompt prompt(Prompt previous) {
        for (Prompt row : prompts) {
            if (row.id.equals(previous.id) && row.kind.equals(previous.kind)
                    && row.body.equals(previous.body) && row.choices.equals(previous.choices)
                    && row.edit == previous.edit && row.multiSelect == previous.multiSelect) {
                return row;
            }
        }
        return null;
    }

    static final class Queued {
        final String id;
        final String text;
        final boolean canSteer;

        Queued(String id, String text, boolean canSteer) {
            this.id = id;
            this.text = text;
            this.canSteer = canSteer;
        }
    }

    static final class Prompt {
        private static final int EDIT_REVIEW_LIMIT = 16000;
        final String id;
        final String kind;
        final String body;
        final List<String> choices;
        final boolean edit;
        final boolean multiSelect;
        private final boolean reviewable;

        Prompt(String id, String kind, JSONObject row) {
            this.id = id;
            this.kind = kind;
            String command = string(row, "command");
            String description = string(row, "description");
            JSONObject proposal = row.optJSONObject("edit");
            this.edit = proposal != null;
            String review = proposal == null ? "" : editReview(proposal);
            reviewable = proposal == null ? !command.trim().isEmpty() : !review.isEmpty();
            body = approval() ? approvalBody(command, description, edit, review)
                    : string(row, "question");
            choices = Collections.unmodifiableList(strings(row.optJSONArray("choices")));
            multiSelect = row.optBoolean("multi_select", false);
        }

        boolean approval() {
            return kind.equals("approval");
        }

        boolean canAnswer(String response) {
            if (response == null || response.length() > 16384) {
                return false;
            }
            if (!approval()) {
                return true;
            }
            return choices.contains(response) && ("deny".equals(response)
                    || "once".equals(response) && reviewable);
        }

        private static String editReview(JSONObject proposal) {
            String tool = string(proposal, "tool_name");
            String path = string(proposal, "path");
            Object oldText = proposal.opt("old_text");
            Object newText = proposal.opt("new_text");
            if (!(tool.equals("write_file") || tool.equals("patch")) || path.isEmpty()
                    || !(oldText instanceof String || oldText == JSONObject.NULL)
                    || !(newText instanceof String)) {
                return "";
            }
            String before = oldText == JSONObject.NULL ? t("native.new_file") : (String) oldText;
            String after = (String) newText;
            if ((long) path.length() + before.length() + after.length() > EDIT_REVIEW_LIMIT) {
                return "";
            }
            return path + t("native.before") + before + t("native.proposed_changes") + after;
        }

        private static String approvalBody(String command, String description, boolean edit,
                                           String review) {
            List<String> lines = new ArrayList<>();
            if (!description.isEmpty()) {
                lines.add(description);
            }
            if (!command.isEmpty() && !command.equals(description)) {
                lines.add(command);
            }
            if (edit) {
                lines.add(review.isEmpty() ? t("native.review_the_full_file_change_in_hermes_desktop") : review);
            }
            return String.join("\n\n", lines);
        }
    }

    private static List<String> strings(JSONArray items) {
        List<String> result = new ArrayList<>();
        for (int i = 0; items != null && i < items.length(); i++) {
            Object value = items.opt(i);
            if (value instanceof String && !((String) value).isEmpty()) {
                result.add((String) value);
            }
        }
        return result;
    }

    private static String string(JSONObject object, String key) {
        Object value = object.opt(key);
        return value instanceof String ? (String) value : "";
    }
}
