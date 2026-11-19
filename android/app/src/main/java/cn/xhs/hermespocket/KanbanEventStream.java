package cn.xhs.hermespocket;

import static cn.xhs.hermespocket.HermesText.t;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.BooleanSupplier;

import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/** One read-only board viewer. Project only bounded event metadata into the bridge. */
final class KanbanEventStream {
    private static final int MAX_BATCH = 200;
    private static final int MAX_EVENTS = 80;
    private static final int MAX_FRAME_CHARS = 2 * 1024 * 1024;
    private static final int MAX_FRAME_DEPTH = 48;
    private final HermesClient client;
    private final BooleanSupplier currentLogin;
    private final String profile;
    private final String board;
    private final Deque<JSONObject> events = new ArrayDeque<>();
    private Connection connection;
    private Long cursor;
    private String state = "closed";

    KanbanEventStream(HermesClient client, BooleanSupplier currentLogin, String profile, String board) {
        if (profile == null || !profile.matches("[A-Za-z0-9_-]{0,64}")
                || board == null || !board.matches("[a-z0-9][a-z0-9_-]{0,63}")) {
            throw new HermesText.InvalidInput("native.select_a_valid_profile_and_board");
        }
        this.client = client;
        this.currentLogin = currentLogin;
        this.profile = profile;
        this.board = board;
    }

    synchronized boolean matches(String nextProfile, String nextBoard) {
        return profile.equals(nextProfile) && board.equals(nextBoard);
    }

    synchronized Runnable prepareStart() {
        if (connection != null || !currentLogin.getAsBoolean()) {
            return null;
        }
        state = "connecting";
        Connection start = new Connection();
        connection = start;
        return () -> connect(start);
    }

    private void connect(Connection start) {
        try {
            String ticket = client.mintKanbanEventTicket(start.request);
            synchronized (this) {
                if (isCurrent(start)) {
                    start.socket = client.openKanbanEvents(ticket, profile, board, cursor, start);
                }
            }
        } catch (Exception error) {
            finish(start, error instanceof HermesClient.HttpException
                    ? httpState(((HermesClient.HttpException) error).statusCode) : "failed");
        }
    }

    private static String httpState(int status) {
        if (status == 401) {
            return "signed_out";
        }
        return status == 403 || status == 404 ? "unavailable" : "failed";
    }

    synchronized String snapshot() throws JSONException {
        JSONArray rows = new JSONArray();
        events.descendingIterator().forEachRemaining(rows::put);
        return new JSONObject().put("state", state).put("events", rows).toString();
    }

    synchronized void stop() {
        Connection previous = connection;
        connection = null;
        state = "closed";
        if (previous != null) {
            previous.request.cancel();
            if (previous.socket != null) {
                previous.socket.cancel();
            }
        }
    }

    private synchronized boolean isCurrent(Connection candidate) {
        return connection == candidate && currentLogin.getAsBoolean();
    }

    private synchronized void finish(Connection ended, String next) {
        if (connection == ended) {
            connection = null;
            state = next;
        }
    }

    private synchronized void receive(Connection active, String message) throws JSONException {
        if (!isCurrent(active)) {
            return;
        }
        requireBoundedFrame(message);
        JSONObject frame = new JSONObject(message);
        long nextCursor = positiveLong(frame.opt("cursor"));
        JSONArray batch = frame.getJSONArray("events");
        if (batch.length() == 0 || batch.length() > MAX_BATCH) {
            throw new JSONException("Invalid Kanban event batch");
        }
        List<JSONObject> projected = new ArrayList<>(batch.length());
        long previousId = 0;
        for (int index = 0; index < batch.length(); index++) {
            JSONObject source = batch.getJSONObject(index);
            long id = positiveLong(source.opt("id"));
            if (id <= previousId) {
                throw new JSONException("Kanban events are not ordered");
            }
            previousId = id;
            if (cursor == null || id > cursor) {
                projected.add(new JSONObject().put("id", Long.toString(id))
                        .put("kind", identifier(source.opt("kind"), 64))
                        .put("taskId", identifier(source.opt("task_id"), 128))
                        .put("createdAt", positiveLong(source.opt("created_at"))));
            }
        }
        if (previousId != nextCursor) {
            throw new JSONException("Invalid Kanban event cursor");
        }
        if (cursor == null || nextCursor > cursor) {
            cursor = nextCursor;
            for (JSONObject event : projected) {
                events.addLast(event);
                if (events.size() > MAX_EVENTS) {
                    events.removeFirst();
                }
            }
        }
    }

    private static void requireBoundedFrame(String message) throws JSONException {
        if (message.length() > MAX_FRAME_CHARS) {
            throw new JSONException("Kanban event frame too large");
        }
        int depth = 0;
        char quote = 0;
        for (int index = 0; index < message.length(); index++) {
            char character = message.charAt(index);
            if (quote != 0) {
                if (character == '\\') {
                    index++;
                } else if (character == quote) {
                    quote = 0;
                }
            } else if (character == '"' || character == '\'') {
                quote = character;
            } else if (character == '{' || character == '[') {
                if (++depth > MAX_FRAME_DEPTH) {
                    throw new JSONException("Kanban event frame too deep");
                }
            } else if (character == '}' || character == ']') {
                if (--depth < 0) {
                    throw new JSONException("Invalid Kanban event frame");
                }
            }
        }
    }

    private static long positiveLong(Object value) throws JSONException {
        if ((value instanceof Integer || value instanceof Long) && ((Number) value).longValue() > 0) {
            return ((Number) value).longValue();
        }
        throw new JSONException("Invalid Kanban event number");
    }

    private static String identifier(Object value, int limit) throws JSONException {
        if (value instanceof String && ((String) value).matches("[A-Za-z0-9_.-]{1," + limit + "}")) {
            return (String) value;
        }
        throw new JSONException("Invalid Kanban event identifier");
    }

    private final class Connection extends WebSocketListener {
        private final HermesClient.RequestRead request = new HermesClient.RequestRead();
        private WebSocket socket;

        @Override
        public void onOpen(WebSocket socket, Response response) {
            synchronized (KanbanEventStream.this) {
                if (isCurrent(this)) {
                    state = "live";
                    return;
                }
            }
            socket.cancel();
        }

        @Override
        public void onMessage(WebSocket socket, String message) {
            try {
                receive(this, message);
            } catch (Exception error) {
                finish(this, "invalid_data");
                socket.cancel();
            }
        }

        @Override
        public void onMessage(WebSocket socket, ByteString bytes) {
            finish(this, "invalid_data");
            socket.cancel();
        }

        @Override
        public void onClosing(WebSocket socket, int code, String reason) {
            finish(this, code == 1008 ? "unavailable" : code == 4401 ? "signed_out" : "closed");
            socket.close(1000, null);
        }

        @Override
        public void onFailure(WebSocket socket, Throwable error, Response response) {
            finish(this, response == null ? "failed" : httpState(response.code()));
            if (response != null) {
                response.close();
            }
        }
    }
}
