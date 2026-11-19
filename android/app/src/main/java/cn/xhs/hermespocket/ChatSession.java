package cn.xhs.hermespocket;

import static cn.xhs.hermespocket.HermesText.t;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.IOException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.SSLException;

/** UI-thread state retained across Activity recreation. */
final class ChatSession {
    private static final long POLL_MS = 2200;
    private static final long POLL_WINDOW_MS = 180000;
    private static final long RECEIPT_RETRY_MS = 5000;
    private static final long HISTORY_POLL_MS = 4000;
    private static final long HISTORY_RETRY_MAX_MS = 60000;
    private static final long REPLY_RENDER_MS = 48;
    private static final long REPLY_SYNC_WINDOW_MS = 30000;
    private static final long CONTROL_ACTIVE_POLL_MS = 3500;
    private static final long CONTROL_IDLE_POLL_MS = 9000;
    private static final long CONTROL_RETRY_MS = 15000;
    private static final int MAX_SETTLING_TURNS = 4;
    private static final String NEW_CHAT = ":new:";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService requests = Executors.newFixedThreadPool(4);
    private final Map<String, String> drafts = new HashMap<>();
    private final Map<String, List<HermesClient.Attachment>> draftAttachments = new HashMap<>();
    private final Map<String, HermesClient.Conversation> newlyCreated = new LinkedHashMap<>();
    private final Map<String, Turn> settling = new LinkedHashMap<>();
    private final Runnable poll = this::checkReceipt;
    private final Runnable historyPoll = this::syncHistory;
    private final Runnable controlPoll = this::syncControls;
    private final Runnable listRefresh = this::refreshConversations;
    private HermesClient client;
    private String restoreKey;
    private String createRequestId;
    private int generation;
    private boolean historyVisible;
    private boolean fullHistoryPending;
    private boolean interactiveHistoryPending;
    private long historyPollDelay = HISTORY_POLL_MS;
    private HistoryRefresh historyInFlight;
    private HermesClient.RequestRead conversationRead;
    private HermesClient.RequestRead openingConversation;
    private HermesClient.RequestRead exportingConversation;
    private HermesClient.ControlRead controlRead;
    private boolean loadingControls;
    private boolean applyingControl;
    private boolean controlsChanged;
    private String queueSendingKey;
    private HermesClient.SendRead queueSendRead;
    private boolean updatingSidebar;
    private HermesClient.MessageHistory history;
    private boolean disposed;
    private Turn turn;
    private Turn lastToolTurn;
    private String sendNoticeKey;
    private String sendError;

    Runnable changed;
    String server;
    List<HermesClient.Conversation> conversations = Collections.emptyList();
    HermesClient.Conversation selected;
    private List<HermesClient.Message> messages = Collections.emptyList();
    String conversationError;
    String conversationNotice;
    String historyError;
    String controlNotice;
    String listQuery = "";
    boolean showingArchived;
    int nextConversationOffset;
    ChatControls controls;
    boolean loadingConversations;
    boolean loadingHistory;
    boolean needsLogin;
    boolean composingNew;

    void connect(String server, HermesClient client, String lastSelection) {
        generation++;
        main.removeCallbacks(poll);
        stopConversationRead();
        stopOpeningConversation();
        stopExport();
        clearControls();
        cancelTurnStream();
        this.server = server;
        this.client = client;
        this.restoreKey = lastSelection;
        createRequestId = null;
        composingNew = false;
        newlyCreated.clear();
        conversations = Collections.emptyList();
        nextConversationOffset = 0;
        showingArchived = false;
        listQuery = "";
        selected = null;
        resetHistory();
        conversationError = null;
        sendNoticeKey = null;
        sendError = null;
        drafts.clear();
        draftAttachments.clear();
        cancelQueueSend();
        queueSendingKey = null;
        updatingSidebar = false;
        turn = null;
        lastToolTurn = null;
        settling.clear();
        loadingConversations = false;
        needsLogin = false;
        if (client == null) {
            notifyChange();
        } else {
            refreshConversations();
        }
    }

    void disconnect() {
        connect(null, null, null);
    }

    void refresh() {
        if (turn != null && turn.receipt != null && turn.paused && !needsLogin) {
            turn.paused = false;
            turn.retryingReceipt = false;
            turn.deadline = SystemClock.elapsedRealtime() + POLL_WINDOW_MS;
            sendError = null;
            checkReceipt();
        }
        if (historyVisible && selected != null && !needsLogin) {
            scheduleControls(0);
            loadingHistory = true;
            historyError = null;
            if (historyInFlight != null && historyInFlight.full) {
                historyInFlight.interactive = true;
            } else {
                queueHistory(true);
            }
            notifyChange();
        }
        refreshConversations();
    }

    void localeChanged() {
        conversationError = null;
        conversationNotice = null;
        historyError = null;
        controlNotice = null;
        sendError = null;
    }

    void refreshConversations() {
        requestConversations(0);
    }

    void loadMoreConversations() {
        if (nextConversationOffset > 0 && listQuery.trim().isEmpty()) {
            requestConversations(nextConversationOffset);
        }
    }

    private void requestConversations(int offset) {
        if (client == null || loadingConversations || needsLogin) {
            return;
        }
        loadingConversations = true;
        conversationError = null;
        notifyChange();

        HermesClient currentClient = client;
        HermesClient.RequestRead read = new HermesClient.RequestRead();
        conversationRead = read;
        String query = listQuery.trim();
        boolean archived = showingArchived;
        run(() -> query.isEmpty() ? currentClient.listConversationPage(archived, offset, read)
                : new HermesClient.ConversationPage(
                        currentClient.searchConversations(query, archived, read), 0), (page, error) -> {
            if (conversationRead != read) {
                return;
            }
            conversationRead = null;
            loadingConversations = false;
            if (error != null) {
                conversationError = readableError(error);
            } else {
                List<HermesClient.Conversation> rows = page.rows;
                for (HermesClient.Conversation row : rows) {
                    newlyCreated.remove(row.key);
                }
                nextConversationOffset = page.nextOffset;
                if (offset > 0) {
                    Map<String, HermesClient.Conversation> merged = new LinkedHashMap<>();
                    for (HermesClient.Conversation row : conversations) {
                        merged.put(row.key, row);
                    }
                    for (HermesClient.Conversation row : rows) {
                        merged.put(row.key, row);
                    }
                    conversations = new ArrayList<>(merged.values());
                } else {
                    conversations = !archived && query.isEmpty() ? includeNewlyCreated(rows) : rows;
                }
                if (!chatOpen() && restoreKey != null && !archived && query.isEmpty()) {
                    HermesClient.Conversation previous = conversation(restoreKey);
                    restoreKey = null;
                    if (previous != null) {
                        open(previous);
                        return;
                    }
                } else if (selected != null) {
                    HermesClient.Conversation current = conversation(selected.key);
                    if (current != null) {
                        boolean newOwner = !selected.id.equals(current.id);
                        selected = current;
                        if (newOwner) {
                            queueHistory(false);
                            clearControls();
                            scheduleControls(0);
                        }
                    }
                }
            }
            notifyChange();
        });
    }

    void setListFilter(String query, boolean archived) {
        if (Objects.equals(query, listQuery) && archived == showingArchived) {
            return;
        }
        listQuery = query;
        showingArchived = archived;
        stopConversationRead();
        conversations = Collections.emptyList();
        nextConversationOffset = 0;
        conversationError = null;
        main.postDelayed(listRefresh, 300);
        notifyChange();
    }

    private void stopConversationRead() {
        main.removeCallbacks(listRefresh);
        if (conversationRead != null) {
            conversationRead.cancel();
            conversationRead = null;
        }
        loadingConversations = false;
    }

    void updateSidebar(HermesClient.Conversation row, HermesClient.SidebarField field, Object value) {
        if (client == null || needsLogin || updatingSidebar || conversation(row.key) != row) {
            return;
        }
        HermesClient currentClient = client;
        updatingSidebar = true;
        run(() -> {
            currentClient.updateSidebar(row, field, value);
            return null;
        }, (ignored, error) -> {
            updatingSidebar = false;
            stopConversationRead();
            refreshConversations();
            if (error != null) {
                conversationError = readableError(error);
                notifyChange();
            }
        });
    }

    void open(HermesClient.Conversation conversation) {
        stopOpeningConversation();
        restoreKey = null;
        composingNew = false;
        resetHistory();
        clearControls();
        selected = conversation;
        stopStreamOutsideChat();
        queueHistory(true);
        scheduleControls(0);
        notifyChange();
    }

    void openNew() {
        if (client == null || needsLogin) {
            return;
        }
        restoreKey = null;
        stopOpeningConversation();
        closeHistoryState();
        composingNew = true;
        notifyChange();
    }

    void closeHistory() {
        stopOpeningConversation();
        closeHistoryState();
        notifyChange();
    }

    private void closeHistoryState() {
        selected = null;
        composingNew = false;
        resetHistory();
        clearControls();
        stopStreamOutsideChat();
    }

    void openFromWorkspace(String profile, String id) {
        HermesClient currentClient = client;
        if (currentClient == null || needsLogin) {
            return;
        }
        restoreKey = null;
        stopOpeningConversation();
        closeHistoryState();
        HermesClient.RequestRead read = new HermesClient.RequestRead();
        openingConversation = read;
        notifyChange();
        run(() -> currentClient.getConversation(profile, id, read), (found, error) -> {
            if (openingConversation != read) {
                return;
            }
            openingConversation = null;
            if (error == null) {
                open(found);
            } else {
                conversationError = readableError(error);
                notifyChange();
            }
        });
    }

    boolean openingFromWorkspace() {
        return openingConversation != null;
    }

    private void stopOpeningConversation() {
        if (openingConversation != null) {
            openingConversation.cancel();
            openingConversation = null;
        }
    }

    interface ExportDestination {
        OutputStream open() throws IOException;
    }

    void exportConversation(String profile, String id, File cacheDir, ExportDestination destination) {
        HermesClient currentClient = client;
        if (currentClient == null || needsLogin || exportingConversation != null) {
            return;
        }
        HermesClient.RequestRead read = new HermesClient.RequestRead();
        exportingConversation = read;
        conversationError = null;
        notifyChange();
        run(() -> {
            HermesClient.Conversation row = currentClient.getConversation(profile, id, read);
            File staged = File.createTempFile("hermes-chat-export-", ".json", cacheDir);
            try {
                try (OutputStream output = new FileOutputStream(staged)) {
                    currentClient.exportConversation(row, read, output);
                }
                read.checkCancelled();
                try (OutputStream output = destination.open(); FileInputStream input = new FileInputStream(staged)) {
                    if (output == null) {
                        throw new HermesText.Failure("native.could_not_write_the_export_to_the_selected_file");
                    }
                    HermesClient.copy(input, output);
                }
            } finally {
                staged.delete();
            }
            return null;
        }, (ignored, error) -> {
            if (exportingConversation != read) {
                return;
            }
            exportingConversation = null;
            if (error != null) {
                conversationError = readableError(error);
            } else {
                conversationNotice = t("native.conversation_json_saved");
            }
            notifyChange();
        });
    }

    boolean exporting() {
        return exportingConversation != null;
    }

    private void stopExport() {
        if (exportingConversation != null) {
            exportingConversation.cancel();
            exportingConversation = null;
        }
        conversationNotice = null;
    }

    private void resetHistory() {
        stopHistory();
        history = null;
        historyPollDelay = HISTORY_POLL_MS;
        messages = Collections.emptyList();
        historyError = null;
    }

    void setHistoryVisible(boolean visible) {
        if (historyVisible == visible) {
            return;
        }
        historyVisible = visible;
        if (visible) {
            historyPollDelay = HISTORY_POLL_MS;
            queueHistory(history == null && historyError == null);
            scheduleControls(0);
            if (turn != null && turnVisible(turn)) {
                turn.resumePreview();
            }
        } else {
            stopHistory();
            clearControls();
        }
    }

    private void clearControls() {
        main.removeCallbacks(controlPoll);
        if (controlRead != null) {
            controlRead.cancel();
            controlRead = null;
        }
        controls = null;
        controlNotice = null;
        loadingControls = false;
        applyingControl = false;
        controlsChanged = false;
    }

    private void scheduleControls(long delay) {
        main.removeCallbacks(controlPoll);
        if (historyVisible && !disposed && !needsLogin && selected != null
                && !selected.archived && !loadingControls) {
            main.postDelayed(controlPoll, delay);
        }
    }

    private void syncControls() {
        HermesClient currentClient = client;
        HermesClient.Conversation current = selected;
        if (current == null || current.archived || currentClient == null || !historyVisible || needsLogin) {
            return;
        }
        if (controlRead == null) {
            controlRead = newControlRead();
        }
        HermesClient.ControlRead read = controlRead;
        loadingControls = true;
        run(() -> currentClient.getChatControls(current, read), (snapshot, error) -> {
            if (read != controlRead) {
                return;
            }
            loadingControls = false;
            boolean refreshAgain = controlsChanged;
            controlsChanged = false;
            controls = snapshot;
            if (error != null && requiresLogin(error)) {
                controlNotice = readableError(error);
            }
            notifyChange();
            scheduleControls(error != null ? CONTROL_RETRY_MS : refreshAgain ? 100
                    : snapshot.running || !snapshot.queued.isEmpty() || turn != null
                    ? CONTROL_ACTIVE_POLL_MS : CONTROL_IDLE_POLL_MS);
        });
    }

    private HermesClient.ControlRead newControlRead() {
        return new HermesClient.ControlRead(read -> main.post(() -> {
            if (read == controlRead) {
                if (loadingControls) {
                    controlsChanged = true;
                } else {
                    scheduleControls(100);
                }
            }
        }));
    }

    void actOnControl(ChatControls snapshot, HermesClient.ControlAction action,
                      String admissionId, ChatControls.Prompt prompt, String response) {
        HermesClient currentClient = client;
        HermesClient.Conversation current = selected;
        if (applyingControl) {
            return;
        }
        if (controlRead == null || currentClient == null || current == null || needsLogin
                || !snapshot.sameAuthority(controls)) {
            controlNotice = t("native.conversation_status_is_updating_try_again_shortly");
            scheduleControls(0);
            notifyChange();
            return;
        }
        if (loadingControls) {
            controlRead.cancel();
            controlRead = newControlRead();
        }
        HermesClient.ControlRead read = controlRead;
        loadingControls = true;
        applyingControl = true;
        controlNotice = null;
        notifyChange();
        run(() -> {
            currentClient.actOnChat(current, read, snapshot, action, admissionId, prompt, response);
            return null;
        }, (ignored, error) -> {
            if (read != controlRead) {
                return;
            }
            loadingControls = false;
            applyingControl = false;
            controls = null;
            if (error != null) {
                controlNotice = readableError(error);
            }
            scheduleControls(0);
            queueHistory(false);
            notifyChange();
        });
    }

    private void stopHistory() {
        main.removeCallbacks(historyPoll);
        if (historyInFlight != null) {
            historyInFlight.read.cancel();
            historyInFlight = null;
        }
        fullHistoryPending = false;
        interactiveHistoryPending = false;
        loadingHistory = false;
    }

    boolean chatOpen() {
        return selected != null || composingNew;
    }

    int connectionGeneration() {
        return generation;
    }

    String chatKey() {
        return composingNew ? NEW_CHAT : selected == null ? null : selected.key;
    }

    private void queueHistory(boolean interactive) {
        if (!historyVisible || selected == null || needsLogin) {
            return;
        }
        fullHistoryPending = true;
        interactiveHistoryPending |= interactive;
        scheduleHistory(0);
    }

    private void scheduleHistory(long delay) {
        main.removeCallbacks(historyPoll);
        if (!disposed && historyVisible && client != null && selected != null && !needsLogin
                && historyInFlight == null) {
            main.postDelayed(historyPoll, delay);
        }
    }

    private void syncHistory() {
        if (!fullHistoryPending && turn != null && turnVisible(turn) && !turn.needsHistory()) {
            // The receipt handler refreshes history when this phone's turn settles.
            return;
        }
        HermesClient currentClient = client;
        HermesClient.Conversation conversation = selected;
        HistoryRefresh request = new HistoryRefresh(fullHistoryPending || history == null,
                interactiveHistoryPending, settling.get(conversation.key));
        historyInFlight = request;
        fullHistoryPending = false;
        interactiveHistoryPending = false;
        if (request.interactive && !loadingHistory) {
            loadingHistory = true;
            notifyChange();
        }
        HermesClient.MessageHistory previous = request.full ? null : history;
        run(() -> currentClient.getMessageHistory(conversation, previous, request.read),
                (updated, error) -> finishHistory(request, updated, error));
    }

    private void finishHistory(HistoryRefresh request, HermesClient.MessageHistory updated,
                               Exception error) {
        if (historyInFlight != request) {
            return;
        }
        historyInFlight = null;
        boolean redraw = loadingHistory != interactiveHistoryPending;
        loadingHistory = interactiveHistoryPending;
        if (error == null) {
            history = updated;
            historyPollDelay = HISTORY_POLL_MS;
            if (!messages.equals(updated.messages)) {
                messages = updated.messages;
                redraw = true;
            }
            if (historyError != null) {
                historyError = null;
                redraw = true;
            }
            if (request.settlingTurn != null && reconcileReply(request.settlingTurn)) {
                redraw = true;
            }
        } else {
            historyPollDelay = Math.min(historyPollDelay * 2, HISTORY_RETRY_MAX_MS);
            if (request.interactive || requiresLogin(error)) {
                historyError = readableError(error);
                redraw = true;
            }
        }
        if (redraw) {
            notifyChange();
        }
        scheduleHistory(fullHistoryPending ? 0 : historyPollDelay);
    }

    String draft(String key) {
        return drafts.getOrDefault(key, "");
    }

    void setDraft(String key, String text) {
        if (text.isEmpty()) {
            drafts.remove(key);
        } else {
            drafts.put(key, text);
        }
    }

    List<HermesClient.Attachment> attachments(String key) {
        return draftAttachments.getOrDefault(key, Collections.emptyList());
    }

    boolean addAttachment(String key, HermesClient.Attachment attachment) {
        if (!Objects.equals(chatKey(), key) || !canEditDraft() || attachments(key).size() >= 4) {
            return false;
        }
        List<HermesClient.Attachment> next = new ArrayList<>(attachments(key));
        next.add(attachment);
        draftAttachments.put(key, next);
        notifyChange();
        return true;
    }

    void removeAttachment(String key, HermesClient.Attachment attachment) {
        if (!Objects.equals(chatKey(), key) || !canEditDraft()) {
            return;
        }
        List<HermesClient.Attachment> next = new ArrayList<>(attachments(key));
        next.remove(attachment);
        if (next.isEmpty()) {
            draftAttachments.remove(key);
        } else {
            draftAttachments.put(key, next);
        }
        notifyChange();
    }

    boolean canEditDraft() {
        String key = chatKey();
        return !needsLogin && (selected == null || !selected.archived)
                && (queueSendingKey == null || !queueSendingKey.equals(key))
                && (turn == null || !Objects.equals(turn.key, key) || turn.receipt != null);
    }

    boolean queuesNextMessage() {
        return selected != null && (turn != null && Objects.equals(chatKey(), turn.key)
                || controls != null && controls.running);
    }

    boolean canSend() {
        String key = chatKey();
        boolean canAdmit = turn == null ? !settling.containsKey(key)
                : selected != null && key.equals(turn.key) && turn.receipt != null && !turn.paused;
        return client != null && chatOpen() && canEditDraft() && canAdmit && queueSendingKey == null
                && (!draft(key).trim().isEmpty() || !attachments(key).isEmpty());
    }

    void send() {
        if (!canSend()) {
            return;
        }
        HermesClient currentClient = client;
        HermesClient.Conversation conversation = selected;
        String key = chatKey();
        String text = draft(key);
        List<HermesClient.Attachment> attachments = attachments(key);
        setDraft(key, "");
        draftAttachments.remove(key);
        if (turn != null) {
            enqueue(currentClient, conversation, key, text, attachments);
            return;
        }
        Turn pending = new Turn(key, text, attachments, conversation);
        turn = pending;
        sendNoticeKey = key;
        sendError = null;
        controlNotice = null;
        notifyChange();
        if (conversation != null) {
            submit(currentClient, conversation, pending);
        } else {
            createAndSubmit(currentClient, pending);
        }
    }

    private void enqueue(HermesClient currentClient, HermesClient.Conversation conversation,
                         String key, String text, List<HermesClient.Attachment> attachments) {
        queueSendingKey = key;
        HermesClient.SendRead read = new HermesClient.SendRead(ignored -> {});
        read.stopStreaming();
        queueSendRead = read;
        controlNotice = null;
        notifyChange();
        run(() -> currentClient.sendMessage(conversation, text, attachments, read, true),
                (receipt, error) -> {
                    if (queueSendRead != read) {
                        return;
                    }
                    queueSendRead = null;
                    queueSendingKey = null;
                    if (error != null) {
                        setDraft(key, text);
                        if (!attachments.isEmpty()) {
                            draftAttachments.put(key, attachments);
                        }
                        String message = readableError(error);
                        if (Objects.equals(key, chatKey())) {
                            controlNotice = message;
                        }
                    }
                    if (Objects.equals(key, chatKey())) {
                        scheduleControls(0);
                        queueHistory(false);
                    }
                    notifyChange();
                });
    }

    private void cancelQueueSend() {
        if (queueSendRead != null) {
            queueSendRead.cancel();
            queueSendRead = null;
        }
    }

    private void createAndSubmit(HermesClient currentClient, Turn pending) {
        if (createRequestId == null) {
            createRequestId = UUID.randomUUID().toString();
        }
        String requestId = createRequestId;
        run(() -> currentClient.createConversation(requestId), (created, error) -> {
            if (error != null) {
                failSend(pending, error);
                return;
            }
            createRequestId = null;
            newlyCreated.put(created.key, created);
            if (!showingArchived && listQuery.trim().isEmpty()) {
                conversations = includeNewlyCreated(conversations);
            }
            String draft = draft(NEW_CHAT);
            setDraft(NEW_CHAT, "");
            setDraft(created.key, draft);
            pending.key = created.key;
            sendNoticeKey = created.key;
            if (composingNew) {
                composingNew = false;
                selected = created;
                queueHistory(false);
                scheduleControls(0);
            }
            notifyChange();
            submit(currentClient, created, pending);
        });
    }

    private void submit(HermesClient currentClient, HermesClient.Conversation conversation,
                        Turn pending) {
        pending.read = new HermesClient.SendRead(pending::bufferReply);
        if (!turnVisible(pending)) {
            stopStream(pending);
        }
        run(() -> currentClient.sendMessage(conversation, pending.text, pending.attachments,
                pending.read, false), (receipt, error) -> {
            if (turn != pending) {
                return;
            }
            if (error != null) {
                failSend(pending, error);
                return;
            }
            pending.receipt = receipt;
            pending.submittedText = receipt.submittedText;
            scheduleControls(0);
            pending.deadline = SystemClock.elapsedRealtime() + POLL_WINDOW_MS;
            if (receipt.isTerminal()) {
                finishTurn(receipt);
            } else {
                main.postDelayed(poll, POLL_MS);
                notifyChange();
            }
        });
    }

    private void failSend(Turn pending, Exception error) {
        stopStream(pending);
        turn = null;
        if (draft(pending.key).isEmpty()) {
            setDraft(pending.key, pending.text);
        }
        if (!pending.attachments.isEmpty()) {
            draftAttachments.put(pending.key, pending.attachments);
        }
        sendError = readableError(error);
        refreshAfterTurn(pending.key);
    }

    String turnStatus() {
        if (queueSendingKey != null && queueSendingKey.equals(chatKey())) {
            return t("native.submitting_the_next_message");
        }
        if (turn != null) {
            if (!Objects.equals(chatKey(), turn.key)) {
                return turn.paused ? t("native.refresh_to_check_the_reply_in_another_conversation") : t("native.another_conversation_is_waiting_for_a_reply");
            }
            if (turn.paused) {
                return sendError;
            }
            if (turn.update != null && turn.update.interrupted) {
                return t("native.live_connection_interrupted_syncing_the_sent_message");
            }
            if (turn.update != null && turn.update.completed) {
                return t("native.syncing_the_reply");
            }
            if (turn.retryingReceipt) {
                return turn.previewEmpty() ? t("native.checking_message_status")
                        : t("native.hermes_is_replying_and_checking_message_status");
            }
            if (!turn.previewEmpty()) {
                return t("native.hermes_is_replying");
            }
            if (turn.receipt == null) {
                return composingNew ? t("native.creating_conversation") : t("native.sending");
            }
            switch (turn.receipt.status) {
                case "queued":
                    return t("native.waiting_in_the_hermes_queue");
                case "unknown":
                    return t("native.checking_message_status");
                default:
                    return t("native.hermes_is_replying");
            }
        }
        Turn finished = settling.get(chatKey());
        if (finished != null && !finished.failed()) {
            return t("native.syncing_the_reply");
        }
        return chatOpen() && Objects.equals(chatKey(), sendNoticeKey) ? sendError : null;
    }

    boolean turnActive() {
        Turn finished = settling.get(chatKey());
        return queueSendingKey != null && queueSendingKey.equals(chatKey())
                || turn != null && !turn.paused || finished != null && !finished.failed();
    }

    private void checkReceipt() {
        if (turn == null || turn.receipt == null || turn.paused || needsLogin) {
            return;
        }
        if (SystemClock.elapsedRealtime() >= turn.deadline) {
            pauseReceipt(turn.retryingReceipt ? t("native.hermes_is_temporarily_unreachable_refresh_to_check_the_reply")
                    : t("native.hermes_is_still_working_refresh_to_check_progress"));
            return;
        }
        Turn pending = turn;
        HermesClient currentClient = client;
        HermesClient.SendReceipt known = pending.receipt;
        run(() -> currentClient.getSendStatus(known), (receipt, error) -> {
            if (turn != pending || pending.receipt != known) {
                return;
            }
            if (error != null) {
                if (requiresLogin(error)) {
                    pauseReceipt(readableError(error));
                } else {
                    if (!pending.retryingReceipt && turnVisible(pending)) {
                        scheduleHistory(0);
                    }
                    pending.retryingReceipt = true;
                    main.postDelayed(poll, RECEIPT_RETRY_MS);
                    notifyChange();
                }
                return;
            }
            pending.retryingReceipt = false;
            pending.receipt = receipt;
            if (receipt.isTerminal()) {
                finishTurn(receipt);
            } else {
                main.postDelayed(poll, POLL_MS);
                notifyChange();
            }
        });
    }

    private void finishTurn(HermesClient.SendReceipt receipt) {
        Turn finished = turn;
        stopStream(finished);
        if (finished.update != null && !finished.update.tools.isEmpty()) {
            lastToolTurn = finished;
        }
        finished.deadline = SystemClock.elapsedRealtime() + REPLY_SYNC_WINDOW_MS;
        finished.receipt = receipt;
        settling.put(finished.key, finished);
        if (settling.size() > MAX_SETTLING_TURNS) {
            settling.remove(settling.keySet().iterator().next());
        }
        turn = null;
        if (!"completed".equals(receipt.outcome)) {
            sendError = t("native.hermes_stopped_processing_this_message");
        }
        refreshAfterTurn(finished.key);
    }

    private void pauseReceipt(String error) {
        turn.paused = true;
        stopStream(turn);
        sendError = error;
        notifyChange();
        scheduleHistory(fullHistoryPending ? 0 : HISTORY_POLL_MS);
    }

    private void refreshAfterTurn(String key) {
        if (selected != null && selected.key.equals(key)) {
            queueHistory(false);
            scheduleControls(0);
        }
        refreshConversations();
        notifyChange();
    }

    List<HermesClient.Message> visibleMessages() {
        String key = chatKey();
        Turn displayed = turn != null && Objects.equals(key, turn.key) ? turn : settling.get(key);
        if (displayed == null) {
            return messages;
        }
        int user = storedUser(displayed);
        int assistant = storedAssistant(displayed, user);
        boolean showReply = assistant < 0 && (!displayed.failed() || displayed.keepsPartialReply());
        if (assistant >= 0 || user >= 0 && !showReply) {
            return messages;
        }
        List<HermesClient.Message> rows = new ArrayList<>(messages);
        if (user < 0) {
            user = rows.size();
            rows.add(new HermesClient.Message(displayed.id + ":user", "user", displayed.displayText(),
                    displayed.sentAt));
        }
        if (showReply) {
            String body = displayed.revealedReply;
            int insertion = user + 1;
            // History can already contain commentary from completed tool rounds.
            // Keep those saved rows and project only any additional live text.
            while (insertion < rows.size() && rows.get(insertion).role.equals("assistant")) {
                String saved = rows.get(insertion++).content;
                if (!body.isEmpty() && saved.startsWith(body)) {
                    return rows;
                }
                if (body.startsWith(saved + "\n\n")) {
                    body = body.substring(saved.length() + 2);
                }
            }
            if (body.isEmpty()) {
                body = displayed.paused || displayed.retryingReceipt
                        || displayed.update != null && displayed.update.interrupted
                        ? t("native.waiting_for_the_reply_to_sync") : t("native.waiting_for_a_reply");
            }
            rows.add(insertion, new HermesClient.Message(displayed.id + ":assistant", "assistant",
                    body, 0));
        }
        return rows;
    }

    List<HermesReplyStream.ToolActivity> visibleTools() {
        Turn owner = visibleToolTurn();
        return owner != null && owner.update != null
                ? owner.update.tools : Collections.emptyList();
    }

    String visibleToolRowId() {
        Turn owner = visibleToolTurn();
        if (owner == null || owner.update == null || owner.update.tools.isEmpty()) {
            return null;
        }
        int assistant = storedAssistant(owner, storedUser(owner));
        return assistant >= 0 ? messages.get(assistant).id : owner.id + ":assistant";
    }

    boolean toolTurnEnded() {
        Turn owner = visibleToolTurn();
        return owner != null && (owner != turn || owner.update != null && owner.update.completed);
    }

    private Turn visibleToolTurn() {
        String key = chatKey();
        Turn owner = turn != null && Objects.equals(key, turn.key) ? turn : lastToolTurn;
        return owner != null && Objects.equals(key, owner.key) ? owner : null;
    }

    private boolean reconcileReply(Turn finished) {
        if (finished != settling.get(chatKey())) {
            return false;
        }
        int user = storedUser(finished);
        int assistant = storedAssistant(finished, user);
        if ((!finished.failed() || finished.keepsPartialReply()) && assistant < 0
                && SystemClock.elapsedRealtime() < finished.deadline) {
            return false;
        }
        settling.remove(finished.key);
        if (!finished.failed() && assistant < 0 && (user < 0 || !finished.previewEmpty())
                && Objects.equals(sendNoticeKey, finished.key)) {
            sendError = t("native.the_reply_has_not_synced_yet_refresh_to_view_the");
        }
        return true;
    }

    private int storedUser(Turn pending) {
        String rowId = pending.update == null ? null : pending.update.userRowId;
        if (rowId != null) {
            return rowIndex(rowId, "user");
        }
        // Older servers omit row IDs from stream events. Restrict text matching
        // to records after the last visible row known before this submission.
        int baseline = rowIndex(pending.historyTailId, null);
        for (int index = messages.size() - 1; index > baseline; index--) {
            HermesClient.Message row = messages.get(index);
            if (row.role.equals("user") && pending.matchesText(row.content)
                    && (baseline >= 0 || pending.emptyHistory
                    || row.timestampMillis > pending.historyTimestamp)) {
                return index;
            }
        }
        return -1;
    }

    private int storedAssistant(Turn pending, int user) {
        String rowId = pending.update == null ? null : pending.update.assistantRowId;
        if (rowId != null) {
            return rowIndex(rowId, "assistant");
        }
        if (user < 0 || pending.receipt == null || !pending.receipt.isTerminal()) {
            return -1;
        }
        int assistant = -1;
        for (int index = user + 1; index < messages.size(); index++) {
            if (messages.get(index).role.equals("user")) {
                break;
            }
            if (messages.get(index).role.equals("assistant")) {
                assistant = index;
            }
        }
        return assistant;
    }

    private int rowIndex(String id, String role) {
        if (id != null) {
            for (int index = messages.size() - 1; index >= 0; index--) {
                HermesClient.Message row = messages.get(index);
                if (id.equals(row.id) && (role == null || role.equals(row.role))) {
                    return index;
                }
            }
        }
        return -1;
    }

    private boolean turnVisible(Turn pending) {
        return historyVisible && !needsLogin && !disposed
                && turn == pending && Objects.equals(pending.key, chatKey());
    }

    private void stopStreamOutsideChat() {
        if (turn != null && !turnVisible(turn)) {
            stopStream(turn);
        }
    }

    private void stopStream(Turn pending) {
        if (pending != null && pending.read != null) {
            pending.closeReplyBuffer();
            pending.read.stopStreaming();
        }
    }

    private void cancelTurnStream() {
        if (turn != null && turn.read != null) {
            turn.closeReplyBuffer();
            turn.read.cancel();
        }
    }

    private HermesClient.Conversation conversation(String key) {
        for (HermesClient.Conversation row : conversations) {
            if (row.key.equals(key)) {
                return row;
            }
        }
        return null;
    }

    private List<HermesClient.Conversation> includeNewlyCreated(List<HermesClient.Conversation> rows) {
        if (newlyCreated.isEmpty()) {
            return rows;
        }
        List<HermesClient.Conversation> merged = new ArrayList<>(newlyCreated.values());
        for (HermesClient.Conversation row : rows) {
            if (!newlyCreated.containsKey(row.key)) {
                merged.add(row);
            }
        }
        return merged;
    }

    private String readableError(Exception error) {
        // Log types and call sites only; network exceptions may contain private URLs.
        Throwable cause = error;
        for (int n = 0; cause != null && n < 3; n++, cause = cause.getCause()) {
            StackTraceElement[] stack = cause.getStackTrace();
            android.util.Log.w("HermesPocket", cause.getClass().getSimpleName() + " at "
                    + (stack.length == 0 ? "unknown" : stack[0].toString()));
        }
        if (requiresLogin(error)) {
            needsLogin = true;
            stopHistory();
            clearControls();
            stopStreamOutsideChat();
            return t("native.your_session_expired_sign_in_to_hermes_again");
        }
        if (error instanceof HermesClient.HttpException
                && ((HermesClient.HttpException) error).statusCode == 403) {
            return t("native.this_account_cannot_perform_this_hermes_action");
        }
        if (error instanceof SSLException) {
            return t("native.could_not_verify_the_https_connection_check_the_hermes_address");
        }
        if (error instanceof ConnectException || error instanceof UnknownHostException
                || error instanceof SocketTimeoutException) {
            return t("native.could_not_connect_to_hermes_check_your_network_and_the");
        }
        if (error instanceof HermesText.MessageError || error instanceof HermesClient.RpcException) {
            return error.getMessage();
        }
        return t("native.could_not_complete_the_hermes_request_refresh_and_try_again");
    }

    private static boolean requiresLogin(Exception error) {
        return error instanceof ConnectionStore.SignInRequiredException
                || error instanceof HermesClient.HttpException
                && ((HermesClient.HttpException) error).statusCode == 401;
    }

    private void notifyChange() {
        if (changed != null) {
            changed.run();
        }
    }

    private <T> void run(Callable<T> request, Result<T> result) {
        int connection = generation;
        requests.execute(() -> {
            try {
                T response = request.call();
                main.post(() -> deliver(connection, result, response, null));
            } catch (Exception error) {
                main.post(() -> deliver(connection, result, null, error));
            }
        });
    }

    private <T> void deliver(int connection, Result<T> result, T response, Exception error) {
        if (!disposed && connection == generation) {
            result.accept(response, error);
        }
    }

    void dispose() {
        disposed = true;
        changed = null;
        stopHistory();
        stopConversationRead();
        stopOpeningConversation();
        stopExport();
        clearControls();
        cancelQueueSend();
        cancelTurnStream();
        main.removeCallbacksAndMessages(null);
        requests.shutdownNow();
    }

    private interface Result<T> {
        void accept(T response, Exception error);
    }

    private static final class HistoryRefresh {
        final HermesClient.HistoryRead read = new HermesClient.HistoryRead();
        final boolean full;
        final Turn settlingTurn;
        boolean interactive;

        HistoryRefresh(boolean full, boolean interactive, Turn settlingTurn) {
            this.full = full;
            this.interactive = interactive;
            this.settlingTurn = settlingTurn;
        }
    }

    private final class Turn {
        final String id = UUID.randomUUID().toString();
        final long sentAt = System.currentTimeMillis();
        String key;
        final String text;
        final String normalizedText;
        final List<HermesClient.Attachment> attachments;
        final String historyTailId;
        final long historyTimestamp;
        final boolean emptyHistory;
        final Runnable renderReply = this::publishReply;
        HermesClient.SendRead read;
        HermesClient.StreamUpdate update;
        String revealedReply = "";
        String submittedText;
        HermesClient.SendReceipt receipt;
        long deadline;
        boolean paused;
        boolean retryingReceipt;
        private HermesClient.StreamUpdate bufferedReply;
        private boolean renderScheduled;
        private boolean replyBufferClosed;

        Turn(String key, String text, List<HermesClient.Attachment> attachments,
             HermesClient.Conversation conversation) {
            this.key = key;
            this.text = text;
            this.attachments = attachments;
            normalizedText = text.trim().isEmpty() ? t("native.please_review_the_attachments") : text.trim();
            HermesClient.Message latest = messages.isEmpty() ? null : messages.get(messages.size() - 1);
            historyTailId = latest == null ? null : latest.id;
            historyTimestamp = latest != null ? latest.timestampMillis
                    : conversation == null ? 0 : conversation.lastActiveMillis;
            emptyHistory = latest == null && (history != null || conversation == null);
        }

        String displayText() {
            if (attachments.isEmpty()) {
                return text;
            }
            List<String> names = new ArrayList<>();
            for (HermesClient.Attachment attachment : attachments) {
                names.add(attachment.name);
            }
            return normalizedText + t("native.attachments", String.join(t("native.list_separator"), names));
        }

        boolean matchesText(String stored) {
            String body = stored.trim();
            return normalizedText.equals(body)
                    || submittedText != null && body.equals(submittedText.trim());
        }

        synchronized void resumePreview() {
            if (bufferedReply != null) {
                scheduleReplyRender();
            }
        }

        boolean failed() {
            return receipt != null && receipt.isTerminal() && !"completed".equals(receipt.outcome);
        }

        boolean keepsPartialReply() {
            return receipt != null && "interrupted".equals(receipt.outcome) && !previewEmpty();
        }

        boolean previewEmpty() {
            return update == null || update.text.isEmpty();
        }

        boolean needsHistory() {
            return paused || retryingReceipt || replyBufferClosed
                    || update != null && (update.interrupted || update.completed);
        }

        synchronized void bufferReply(HermesClient.StreamUpdate incoming) {
            if (replyBufferClosed) {
                return;
            }
            bufferedReply = incoming;
            scheduleReplyRender();
        }

        void publishReply() {
            HermesClient.StreamUpdate current;
            synchronized (this) {
                renderScheduled = false;
                current = bufferedReply;
            }
            if (turnVisible(this) && current != null) {
                boolean refresh = !needsHistory() && (current.completed || current.interrupted);
                boolean changed = update != current;
                update = current;
                String target = current.text;
                int previousEnd = target.startsWith(revealedReply) ? revealedReply.length() : 0;
                int end = ChatPresentation.revealEnd(target, previousEnd, current.completed);
                if (end < target.length()) {
                    synchronized (this) {
                        scheduleReplyRender();
                    }
                }
                if (changed || end != previousEnd || !target.startsWith(revealedReply)) {
                    revealedReply = target.substring(0, end);
                    notifyChange();
                }
                if (refresh) {
                    scheduleHistory(0);
                }
            }
        }

        private void scheduleReplyRender() {
            if (!renderScheduled && !replyBufferClosed) {
                renderScheduled = true;
                main.postDelayed(renderReply, REPLY_RENDER_MS);
            }
        }

        synchronized void closeReplyBuffer() {
            replyBufferClosed = true;
            main.removeCallbacks(renderReply);
            if (bufferedReply != null) {
                update = bufferedReply;
                revealedReply = update.text;
                bufferedReply = null;
            }
        }
    }
}
