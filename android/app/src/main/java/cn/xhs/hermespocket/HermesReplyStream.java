package cn.xhs.hermespocket;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Projects the authority's reply frames into one temporary preview for this submission. */
final class HermesReplyStream {
    private static final int TEXT_LIMIT = 64 * 1024;
    private static final int TOOL_LIMIT = 16;

    private final String sessionId;
    private final String submissionId;
    private final String admissionId;
    private final long authorityEpoch;
    private final String replayEpoch;
    private final StringBuilder sealed = new StringBuilder();
    private final StringBuilder current = new StringBuilder();
    private final Map<String, ToolActivity> tools = new LinkedHashMap<>();
    private long generation;
    private long sequence;
    private String turnId = "";
    private int segment;
    private long offset;
    private boolean boundary;
    private boolean sealedTruncated;
    private boolean currentTruncated;

    HermesReplyStream(String sessionId, String submissionId, String admissionId,
                      long authorityEpoch, long generation, String replayEpoch, long sequence) {
        this.sessionId = sessionId;
        this.submissionId = submissionId;
        this.admissionId = admissionId;
        this.authorityEpoch = authorityEpoch;
        this.generation = generation;
        this.replayEpoch = replayEpoch;
        this.sequence = sequence;
    }

    static boolean isReplyEvent(JSONObject params, String sessionId) {
        if (!sessionId.equals(string(params, "session_id"))) {
            return false;
        }
        switch (string(params, "type")) {
            case "message.start":
            case "message.delta":
            case "message.interim":
            case "message.complete":
            case "tool.start":
            case "tool.progress":
            case "tool.complete":
                return !string(params, "admission_id").isEmpty();
            case "session.replay_gap":
                return true;
            default:
                return false;
        }
    }

    HermesClient.StreamUpdate accept(JSONObject params) {
        String type = string(params, "type");
        if (type.equals("session.replay_gap")) {
            return interrupted();
        }
        if (!admissionId.equals(string(params, "admission_id"))) {
            return null;
        }
        long eventAuthority = params.optLong("authority_epoch", -1);
        long eventGeneration = params.optLong("execution_generation", -1);
        String eventReplay = string(params, "replay_epoch");
        if ((authorityEpoch >= 0 && authorityEpoch != eventAuthority)
                || (generation >= 0 && generation != eventGeneration)) {
            return null;
        }
        if (!replayEpoch.isEmpty() && !eventReplay.isEmpty() && !replayEpoch.equals(eventReplay)) {
            return interrupted();
        }
        long eventSequence = params.optLong("seq", -1);
        if (eventSequence >= 0 && eventSequence <= sequence) {
            return null;
        }
        JSONObject payload = params.optJSONObject("payload");
        if (payload == null) {
            return null;
        }
        String eventTurn = string(payload, "turn_id");
        if (!turnId.isEmpty() && !eventTurn.isEmpty() && !turnId.equals(eventTurn)) {
            return null;
        }
        if (!eventTurn.isEmpty()) {
            turnId = eventTurn;
        }
        generation = eventGeneration;
        if (eventSequence >= 0) {
            sequence = eventSequence;
        }
        switch (type) {
            case "message.delta":
                return delta(payload, eventSequence >= 0);
            case "message.interim":
                return interim(payload, eventSequence >= 0);
            case "message.complete":
                return complete(payload);
            case "tool.start":
            case "tool.progress":
            case "tool.complete":
                return tool(type, payload);
            default:
                return null;
        }
    }

    static final class ToolActivity {
        final String name;
        final boolean completed;
        final int percent;
        final double duration;
        final String detail;

        ToolActivity(String name, boolean completed, int percent, double duration, String detail) {
            this.name = name;
            this.completed = completed;
            this.percent = percent;
            this.duration = duration;
            this.detail = detail;
        }
    }

    private HermesClient.StreamUpdate tool(String type, JSONObject payload) {
        String id = string(payload, "tool_id");
        String name = boundedText(payload, "name", 96);
        if (id.isEmpty() || id.length() > 256 || name.isEmpty()) {
            return null;
        }
        ToolActivity old = tools.get(id);
        if (old != null && (old.completed || !old.name.equals(name))) {
            return null;
        }
        boolean completed = type.equals("tool.complete");
        int percent = type.equals("tool.progress") ? payload.optInt("percent", -1) : -1;
        double duration = completed ? payload.optDouble("duration_s", -1) : -1;
        String detail = completed ? boundedText(payload, "summary", 640)
                : type.equals("tool.progress") ? boundedText(payload, "message", 320) : "";
        if (tools.size() >= TOOL_LIMIT && old == null) {
            tools.remove(tools.keySet().iterator().next());
        }
        tools.put(id, new ToolActivity(name, completed,
                percent >= 0 && percent <= 100 ? percent : -1,
                Double.isFinite(duration) ? duration : -1, detail));
        return snapshot(false, false, null, null);
    }

    private static String boundedText(JSONObject payload, String key, int limit) {
        String value = string(payload, key);
        int end = Math.min(value.length(), limit);
        if (end > 0 && end < value.length() && Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end).replace(String.valueOf((char) 0), "").trim();
    }

    private HermesClient.StreamUpdate delta(JSONObject payload, boolean sequenced) {
        Object value = payload.opt("text");
        if (value == JSONObject.NULL) {
            // Local routes can emit a break without advancing the server's segment cursor.
            boundary = current.length() > 0;
            return null;
        }
        if (!(value instanceof String) || ((String) value).isEmpty()) {
            return null;
        }
        String chunk = (String) value;
        int eventSegment = payload.optInt("segment", -1);
        long eventOffset = payload.optLong("offset", -1);
        if (eventSegment < 0 || eventOffset < 0) {
            // Managed workers publish deltas with a session sequence, before segment stamping.
            if (!sequenced) {
                return interrupted();
            }
            eventSegment = segment;
            eventOffset = offset;
        }
        if (eventSegment < segment) {
            return null;
        }
        if (eventSegment > segment || eventOffset > offset) {
            return interrupted();
        }
        // Java String indexes match the authority's UTF-16 code-unit cursor.
        long applied = offset - eventOffset;
        if (applied >= chunk.length()) {
            return null;
        }
        if (boundary) {
            sealCurrent();
        }
        int previousLength = current.length();
        appendCurrent(chunk, (int) applied);
        offset = eventOffset + chunk.length();
        return current.length() == previousLength ? null : snapshot(false, false, null, null);
    }

    private HermesClient.StreamUpdate interim(JSONObject payload, boolean sequenced) {
        int eventSegment = payload.optInt("segment", -1);
        if (eventSegment < 0 && sequenced) {
            eventSegment = segment;
        }
        if (eventSegment < segment) {
            return null;
        }
        if (eventSegment > segment) {
            return interrupted();
        }
        String text = string(payload, "text");
        if (!text.isEmpty()) {
            replaceCurrent(text);
        }
        sealCurrent();
        segment++;
        offset = 0;
        return snapshot(false, false, null, null);
    }

    private HermesClient.StreamUpdate complete(JSONObject payload) {
        String text = string(payload, "text");
        String status = string(payload, "status");
        if (!text.isEmpty() && !payload.optBoolean("response_reused", false)
                && (status.equals("complete") || status.equals("interrupted"))) {
            if (boundary && !text.trim().equals(current.toString().trim())) {
                sealCurrent();
            }
            // Completion carries authoritative text for the active segment, including rewrites.
            replaceCurrent(text);
        }
        JSONObject persisted = payload.optJSONObject("persisted_turn");
        boolean matches = persisted != null && submissionId.equals(string(persisted, "submission_id"));
        return snapshot(true, false, matches ? rowId(persisted, "user_row_id") : null,
                matches ? rowId(persisted, "final_assistant_row_id") : null);
    }

    HermesClient.StreamUpdate interrupted() {
        return snapshot(false, true, null, null);
    }

    private void appendCurrent(String text, int start) {
        if (sealedTruncated || currentTruncated) {
            return;
        }
        int available = TEXT_LIMIT - sealed.length() - current.length() - (sealed.length() == 0 ? 0 : 2);
        int end = start + Math.max(0, Math.min(available, text.length() - start));
        if (end > start && end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        currentTruncated = end < text.length();
        current.append(text, start, end);
    }

    private void replaceCurrent(String text) {
        current.setLength(0);
        currentTruncated = false;
        appendCurrent(text, 0);
    }

    private void sealCurrent() {
        if (current.length() > 0) {
            if (sealed.length() > 0) {
                sealed.append(separator());
            }
            sealed.append(current);
            current.setLength(0);
        }
        sealedTruncated |= currentTruncated;
        currentTruncated = false;
        boundary = false;
    }

    private HermesClient.StreamUpdate snapshot(boolean complete, boolean interrupted,
                                               String userRowId, String assistantRowId) {
        String text = sealed.length() == 0 ? current.toString() : sealed.toString();
        if (sealed.length() > 0 && current.length() > 0) {
            text += separator() + current;
        }
        return new HermesClient.StreamUpdate(text, userRowId, assistantRowId, complete, interrupted,
                Collections.unmodifiableList(new ArrayList<>(tools.values())));
    }

    private String separator() {
        // Hermes can already put a paragraph break on the first delta after a tool round.
        int newlines = 0;
        for (int index = sealed.length() - 1;
             index >= 0 && newlines < 2 && sealed.charAt(index) == '\n'; index--) {
            newlines++;
        }
        for (int index = 0; index < current.length() && newlines < 2
                && current.charAt(index) == '\n'; index++) {
            newlines++;
        }
        if (newlines == 0) {
            return "\n\n";
        }
        return newlines == 1 ? "\n" : "";
    }

    private static String rowId(JSONObject payload, String key) {
        Object value = payload.opt(key);
        return (value instanceof Integer || value instanceof Long) && ((Number) value).longValue() > 0
                ? value.toString() : null;
    }

    private static String string(JSONObject object, String key) {
        Object value = object.opt(key);
        return value instanceof String ? (String) value : "";
    }
}
