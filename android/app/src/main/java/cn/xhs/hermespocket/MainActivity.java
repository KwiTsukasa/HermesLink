package cn.xhs.hermespocket;

import static cn.xhs.hermespocket.HermesText.t;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.InputType;
import android.text.InputFilter;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

public final class MainActivity extends Activity {
    private static final int SIGN_IN = 101;
    private static final int PICK_ATTACHMENT = 102;
    private static final int EXPORT_CHAT = 103;
    private static final String WORKSPACE_HANDOFF = "cn.xhs.hermespocket.chat_handoff";
    private static final AtomicReference<WorkspaceHandoff> HANDOFF = new AtomicReference<>();
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private enum Screen { SETUP, CONVERSATIONS, CHAT }

    private ConnectionStore connectionStore;
    private SharedPreferences selectionStore;
    private PocketStyle style;
    private ChatSession session;
    private LinearLayout root;
    private Screen screen;
    private String shownKey;
    private boolean resumed;
    private boolean setupOpen;
    private String addressDraft;
    private String setupError;
    private String shownLanguage;
    private Runnable stopObservingLocale;
    private Runnable stopObservingAppearance;
    private String presentedProfile;
    private boolean appearancePending = true;
    private OnBackInvokedCallback backCallback;
    private String attachmentPickKey;
    private int attachmentPickConnection;
    private String exportPickProfile;
    private String exportPickId;
    private int exportPickConnection;

    private TextView title;
    private TextView subtitle;
    private TextView notice;
    private Button noticeAction;
    private Button newConversationButton;
    private ImageButton archivedButton;
    private Button moreConversationsButton;
    private EditText conversationSearch;
    private ProgressBar activityIndicator;
    private ListView list;
    private LinearLayout empty;
    private TextView emptyTitle;
    private TextView emptyDetail;
    private EditText composer;
    private ImageButton sendButton;
    private ImageButton composerStop;
    private ImageButton attachButton;
    private LinearLayout attachmentList;
    private List<HermesClient.Attachment> shownAttachments = Collections.emptyList();
    private Button stopButton;
    private Button queuedButton;
    private Button pendingButton;
    private LinearLayout controlBar;
    private ImageButton latestButton;
    private ConversationAdapter conversationAdapter;
    private MessageAdapter messageAdapter;
    private ChatPresentation.Follow chatFollow;
    private boolean syncComposer;
    private String savedChatKey;
    private int chatScrollPosition = -1;
    private int chatScrollOffset;

    /** Called inside the app by the workspace bridge. Handoff intents carry only a token. */
    static void openConversation(Context context, String server, String profile, String sessionId) {
        String address = ConnectionStore.normalizeUrl(server);
        ConnectionStore connection = new ConnectionStore(context.getApplicationContext());
        if (!address.equals(connection.getUrl()) || !connection.hasCredentials()
                || profile == null || !profile.matches("[A-Za-z0-9_-]{1,64}")
                || sessionId == null || !sessionId.matches("[A-Za-z0-9_-]{1,256}")) {
            throw new HermesText.InvalidInput("native.sign_in_to_the_same_hermes_as_the_workspace_and");
        }
        launchWorkspaceHandoff(context, new WorkspaceHandoff(address, profile, sessionId));
    }

    static void openNewConversation(Context context) {
        ConnectionStore connection = new ConnectionStore(context.getApplicationContext());
        ConnectionStore.LoginScope scope = connection.activeLogin();
        if (scope == null) {
            throw new HermesText.InvalidInput("native.sign_in_to_hermes_first");
        }
        launchWorkspaceHandoff(context, new WorkspaceHandoff(scope.url, null, null));
    }

    private static void launchWorkspaceHandoff(Context context, WorkspaceHandoff handoff) {
        HANDOFF.set(handoff);
        try {
            Intent open = new Intent(context, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(WORKSPACE_HANDOFF, handoff.token);
            if (!(context instanceof Activity)) {
                open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            context.startActivity(open);
        } catch (RuntimeException error) {
            HANDOFF.compareAndSet(handoff, null);
            throw error;
        }
    }

    private static final class WorkspaceHandoff {
        final String token = UUID.randomUUID().toString();
        final String server;
        final String profile;
        final String id;

        WorkspaceHandoff(String server, String profile, String id) {
            this.server = server;
            this.profile = profile;
            this.id = id;
        }
    }

    @Override
    protected void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        setTitle(R.string.app_name);
        connectionStore = new ConnectionStore(getApplicationContext());
        selectionStore = getSharedPreferences("native_selection", MODE_PRIVATE);
        style = new PocketStyle(this);
        shownLanguage = HermesText.languageTag();
        ChatSession retained = (ChatSession) getLastNonConfigurationInstance();
        session = retained == null ? new ChatSession() : retained;
        if (savedState != null) {
            setupOpen = savedState.getBoolean("setupOpen");
            addressDraft = savedState.getString("addressDraft");
            setupError = savedState.getString("setupError");
            if (retained != null) {
                attachmentPickKey = savedState.getString("attachmentPickKey");
                attachmentPickConnection = savedState.getInt("attachmentPickConnection");
                exportPickProfile = savedState.getString("exportPickProfile");
                exportPickId = savedState.getString("exportPickId");
                exportPickConnection = savedState.getInt("exportPickConnection");
                savedChatKey = savedState.getString("savedChatKey");
                chatScrollPosition = savedState.getInt("chatScrollPosition", -1);
                chatScrollOffset = savedState.getInt("chatScrollOffset");
            }
        }

        root = style.column();
        root.setBackgroundColor(style.background);
        root.setFocusableInTouchMode(true);
        root.setLayoutParams(new ViewGroup.LayoutParams(MATCH, MATCH));
        setContentView(root);
        style.applyWindow(getWindow(), root, true);

        session.changed = this::render;
        stopObservingLocale = HermesLocaleStore.observe(this, () -> runOnUiThread(() -> {
            if (resumed) {
                render();
            }
        }));
        stopObservingAppearance = HermesAppearanceStore.observe(this, () -> {
            appearancePending = true;
            if (resumed) {
                render();
            }
        });
        if (retained == null) {
            connectSavedServer();
        }
        if (savedState == null) {
            acceptLegacyNavigation(getIntent());
        }
        acceptWorkspaceHandoff(getIntent());
        render();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        acceptLegacyNavigation(intent);
        acceptWorkspaceHandoff(intent);
        render();
    }

    private void acceptLegacyNavigation(Intent intent) {
        if (intent != null && intent.getBooleanExtra(OfficialWebappActivity.EXTRA_LEGACY_NATIVE, false)
                && connectionStore.getUrl() == null && addressDraft == null) {
            addressDraft = connectionStore.getWebappUrl();
            screen = null;
        }
    }

    private void returnToOfficial() {
        hideKeyboard();
        startActivity(new Intent(this, OfficialWebappActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }

    private void acceptWorkspaceHandoff(Intent intent) {
        WorkspaceHandoff handoff = HANDOFF.get();
        if (handoff == null || intent == null
                || !handoff.token.equals(intent.getStringExtra(WORKSPACE_HANDOFF))
                || !HANDOFF.compareAndSet(handoff, null)) {
            return;
        }
        if (!handoff.server.equals(connectionStore.getUrl()) || !connectionStore.hasCredentials()) {
            return;
        }
        if (!handoff.server.equals(session.server)) {
            connectSavedServer();
        }
        if (handoff.server.equals(session.server)) {
            setupOpen = false;
            if (handoff.id == null) {
                session.openNew();
            } else {
                session.openFromWorkspace(handoff.profile, handoff.id);
            }
        }
    }

    @Override
    public Object onRetainNonConfigurationInstance() {
        return session;
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putBoolean("setupOpen", setupOpen);
        state.putString("addressDraft", addressDraft);
        state.putString("setupError", setupError);
        state.putString("attachmentPickKey", attachmentPickKey);
        state.putInt("attachmentPickConnection", attachmentPickConnection);
        state.putString("exportPickProfile", exportPickProfile);
        state.putString("exportPickId", exportPickId);
        state.putInt("exportPickConnection", exportPickConnection);
        if (screen == Screen.CHAT && !chatFollow.followsLatest()) {
            state.putString("savedChatKey", shownKey);
            if (list.getChildCount() > 0) {
                state.putInt("chatScrollPosition", list.getFirstVisiblePosition());
                state.putInt("chatScrollOffset", firstMessageOffset());
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        appearancePending = true;
        HermesAppearanceStore.refresh(this, appearanceProfile());
        render();
    }

    @Override
    protected void onPause() {
        resumed = false;
        session.setHistoryVisible(false);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (stopObservingLocale != null) {
            stopObservingLocale.run();
        }
        if (stopObservingAppearance != null) {
            stopObservingAppearance.run();
        }
        updateBackCallback(false);
        session.setHistoryVisible(false);
        session.changed = null;
        if (!isChangingConfigurations()) {
            session.dispose();
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (!backWithinApp()) {
            super.onBackPressed();
        }
    }

    private boolean backWithinApp() {
        boolean editingConnection = screen == Screen.SETUP && session.server != null;
        if (screen != Screen.CHAT && !editingConnection) {
            return false;
        }
        if (keyboardVisible()) {
            hideKeyboard();
        } else if (editingConnection) {
            cancelSetup();
        } else {
            closeChat();
        }
        return true;
    }

    private void updateBackCallback(boolean enabled) {
        if (Build.VERSION.SDK_INT < 33) {
            return;
        }
        OnBackInvokedDispatcher dispatcher = getOnBackInvokedDispatcher();
        if (enabled && backCallback == null) {
            backCallback = this::backWithinApp;
            dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    backCallback);
        } else if (!enabled && backCallback != null) {
            dispatcher.unregisterOnBackInvokedCallback(backCallback);
            backCallback = null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent result) {
        super.onActivityResult(requestCode, resultCode, result);
        if (requestCode == PICK_ATTACHMENT) {
            String key = attachmentPickKey;
            attachmentPickKey = null;
            if (resultCode == RESULT_OK && result != null && key != null
                    && attachmentPickConnection == session.connectionGeneration()) {
                useAttachment(key, result.getData());
            }
            return;
        }
        if (requestCode == EXPORT_CHAT) {
            String profile = exportPickProfile;
            String id = exportPickId;
            exportPickProfile = null;
            exportPickId = null;
            Uri uri = result == null ? null : result.getData();
            if (resultCode == RESULT_OK && uri != null && profile != null && id != null
                    && ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())
                    && exportPickConnection == session.connectionGeneration()) {
                Context context = getApplicationContext();
                ContentResolver resolver = context.getContentResolver();
                session.exportConversation(profile, id, context.getCacheDir(),
                        () -> resolver.openOutputStream(uri, "wt"));
            }
            return;
        }
        if (requestCode != SIGN_IN) {
            return;
        }
        if (resultCode == RESULT_OK) {
            setupOpen = false;
            addressDraft = null;
            setupError = null;
            screen = null;
            connectSavedServer();
            render();
        } else {
            String message = result == null ? null : result.getStringExtra("error");
            setupError = TextUtils.isEmpty(message) ? t("native.sign_in_was_not_completed_try_again") : message;
            screen = null;
            render();
        }
    }

    private void connectSavedServer() {
        String stored = connectionStore.getUrl();
        if (stored == null || stored.trim().isEmpty()) {
            return;
        }
        try {
            String url = ConnectionStore.normalizeUrl(stored);
            if (!connectionStore.hasCredentials()) {
                setupOpen = true;
                setupError = t("native.sign_in_to_load_conversations");
                session.disconnect();
                return;
            }
            HermesClient client = new HermesClient(url, connectionStore.authFor(url));
            String key = url.equals(selectionStore.getString("server", null))
                    ? selectionStore.getString("conversation", null) : null;
            session.connect(url, client, key);
        } catch (IllegalArgumentException error) {
            setupOpen = true;
            setupError = error.getMessage();
        }
    }

    private void launchSignIn(EditText field) {
        try {
            String url = ConnectionStore.normalizeUrl(field.getText().toString());
            hideKeyboard();
            startActivityForResult(new Intent(this, LoginActivity.class).putExtra("url", url), SIGN_IN);
        } catch (IllegalArgumentException error) {
            field.setError(error.getMessage());
            field.requestFocus();
        }
    }

    private void openSetup() {
        hideKeyboard();
        addressDraft = session.server;
        setupError = session.needsLogin ? t("native.your_session_expired_sign_in_again") : null;
        setupOpen = true;
        render();
    }

    private void cancelSetup() {
        hideKeyboard();
        setupOpen = false;
        addressDraft = null;
        setupError = null;
        render();
    }

    private void signOut() {
        hideKeyboard();
        connectionStore.clear();
        selectionStore.edit().clear().apply();
        setupOpen = false;
        addressDraft = null;
        setupError = null;
        screen = null;
        session.disconnect();
    }

    private void closeChat() {
        hideKeyboard();
        selectionStore.edit().remove("conversation").apply();
        session.closeHistory();
    }

    private void hideKeyboard() {
        InputMethodManager keyboard = getSystemService(InputMethodManager.class);
        if (keyboard != null) {
            keyboard.hideSoftInputFromWindow(root.getWindowToken(), 0);
        }
    }

    private boolean keyboardVisible() {
        WindowInsets insets = root.getRootWindowInsets();
        if (insets == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= 30) {
            return insets.isVisible(WindowInsets.Type.ime());
        }
        return insets.getSystemWindowInsetBottom() > insets.getStableInsetBottom();
    }

    private void render() {
        String profile = appearanceProfile();
        boolean profileChanged = !TextUtils.equals(profile, presentedProfile);
        if (profileChanged) {
            presentedProfile = profile;
            if (resumed) {
                HermesAppearanceStore.refresh(this, profile);
            }
        }
        boolean appearanceChanged = false;
        if (appearancePending || profileChanged) {
            appearancePending = false;
            PocketStyle nextStyle = new PocketStyle(this, profile);
            if (!style.sameAppearance(nextStyle)) {
                style = nextStyle;
                root.setBackgroundColor(style.background);
                style.applyWindow(getWindow(), root, true);
                appearanceChanged = true;
            }
        }
        String nextLanguage = HermesText.languageTag();
        boolean languageChanged = !nextLanguage.equals(shownLanguage);
        if (appearanceChanged || languageChanged) {
            shownLanguage = nextLanguage;
            if (screen == Screen.CHAT && !chatFollow.followsLatest()) {
                savedChatKey = shownKey;
                if (list.getChildCount() > 0) {
                    chatScrollPosition = list.getFirstVisiblePosition();
                    chatScrollOffset = firstMessageOffset();
                }
            }
            if (languageChanged) {
                setupError = null;
                session.localeChanged();
            }
            screen = null;
        }
        Screen next = setupOpen || session.server == null ? Screen.SETUP
                : session.chatOpen() ? Screen.CHAT : Screen.CONVERSATIONS;
        String key = next == Screen.CHAT ? session.chatKey() : null;
        if (screen != next || !TextUtils.equals(shownKey, key)) {
            screen = next;
            shownKey = key;
            root.removeAllViews();
            if (screen == Screen.SETUP) {
                buildSetup();
            } else if (screen == Screen.CONVERSATIONS) {
                buildConversations();
            } else {
                if (session.selected != null) {
                    selectionStore.edit().putString("server", session.server)
                            .putString("conversation", session.selected.key).apply();
                }
                buildChat();
            }
            updateBackCallback(screen == Screen.CHAT || (screen == Screen.SETUP && session.server != null));
        }
        if (screen == Screen.CONVERSATIONS) {
            renderConversations();
        } else if (screen == Screen.CHAT) {
            renderChat();
        }
        session.setHistoryVisible(resumed && screen == Screen.CHAT);
    }

    private String appearanceProfile() {
        return session.selected == null ? null : session.selected.profile;
    }

    private void buildSetup() {
        boolean editing = session.server != null;
        if (editing) {
            LinearLayout toolbar = toolbar();
            addToolbarIcon(toolbar, PocketStyle.Icon.BACK, t("native.back_to", getString(R.string.app_name)),
                    this::cancelSetup);
            toolbar.addView(style.label(t("native.connection_settings"), 18, style.text, true),
                    new LinearLayout.LayoutParams(WRAP, WRAP));
            addToolbar(toolbar);
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setPadding(dp(24), dp(22), dp(24), dp(28));
        root.addView(scroll, new LinearLayout.LayoutParams(MATCH, 0, 1));
        FrameLayout frame = new FrameLayout(this);
        scroll.addView(frame, new ScrollView.LayoutParams(MATCH, WRAP));
        LinearLayout column = style.column();
        int formWidth = Math.min(dp(720), getResources().getDisplayMetrics().widthPixels - dp(48));
        FrameLayout.LayoutParams centered = new FrameLayout.LayoutParams(Math.max(dp(160), formWidth),
                WRAP, editing ? Gravity.TOP | Gravity.CENTER_HORIZONTAL : Gravity.CENTER);
        frame.addView(column, centered);
        column.setBackground(style.box(style.surface, 4, style.line));
        column.setPadding(dp(20), dp(20), dp(20), dp(20));
        if (!editing) {
            LinearLayout header = new LinearLayout(this);
            header.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams mark = new LinearLayout.LayoutParams(dp(24), dp(24));
            mark.setMarginEnd(dp(9));
            header.addView(style.mark(), mark);
            header.addView(style.wordmark(16), new LinearLayout.LayoutParams(0, WRAP, 1));
            column.addView(header, new LinearLayout.LayoutParams(MATCH, WRAP));
        }

        EditText address = new EditText(this);
        address.setId(View.generateViewId());
        TextView addressLabel = style.label(t("native.hermes_webapp_address"), 15, style.text, true);
        addressLabel.setLabelFor(address.getId());
        column.addView(addressLabel);
        fieldStyle(address);
        StateListDrawable addressShape = new StateListDrawable();
        addressShape.addState(new int[]{android.R.attr.state_focused}, style.box(style.surface, 9, style.accent));
        addressShape.addState(new int[0], style.box(style.surface, 9, style.line));
        address.setBackground(addressShape);
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setSingleLine(true);
        address.setTextDirection(View.TEXT_DIRECTION_LTR);
        address.setImeOptions(EditorInfo.IME_ACTION_GO);
        address.setHint(t("native.https_your_hermes_address"));
        if (addressDraft != null) {
            address.setText(addressDraft);
        } else if (connectionStore.getUrl() != null) {
            address.setText(connectionStore.getUrl());
        }
        address.addTextChangedListener(textChanged(value -> addressDraft = value));
        withMargin(column, address, 10, 0);
        if (setupError != null) {
            TextView problem = style.label(setupError, 14, style.problem, false);
            problem.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            withMargin(column, problem, 13, 0);
        }
        Button login = style.button(t("native.connect_and_sign_in"), true, () -> launchSignIn(address));
        withMargin(column, login, 16, 0);
        if (connectionStore.hasCredentials()) {
            withMargin(column, style.button(t("native.sign_out"), false, this::signOut), 10, 0);
        }
        withMargin(column, style.button(t("native.official_webapp_open"), false, this::returnToOfficial), 10, 0);
        address.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO) {
                launchSignIn(address);
                return true;
            }
            return false;
        });
        root.requestFocus();
    }

    private void buildConversations() {
        LinearLayout toolbar = toolbar();
        addToolbarText(toolbar, style.label(t("native.conversations"), 12, style.text, true));
        addToolbarMenu(toolbar, new String[]{t("native.refresh_conversations"), t("native.connection_settings"),
                        t("native.official_webapp_open")},
                new Runnable[]{session::refresh, this::openSetup, this::returnToOfficial});
        addToolbar(toolbar);
        addNotice();

        newConversationButton = sidebarAction(PocketStyle.Icon.NEW, t("native.new_conversation"), session::openNew);
        root.addView(newConversationButton, new LinearLayout.LayoutParams(MATCH, dp(48)));

        LinearLayout filters = new LinearLayout(this);
        filters.setGravity(Gravity.CENTER_VERTICAL);
        filters.setPadding(dp(12), dp(2), dp(8), dp(3));
        ImageView searchIcon = style.symbol(PocketStyle.Icon.SEARCH, style.secondary);
        filters.addView(searchIcon, new LinearLayout.LayoutParams(dp(16), dp(16)));
        conversationSearch = new EditText(this);
        fieldStyle(conversationSearch);
        conversationSearch.setTextSize(14);
        conversationSearch.setPaddingRelative(dp(9), 0, dp(4), 0);
        conversationSearch.setBackgroundColor(Color.TRANSPARENT);
        conversationSearch.setSingleLine(true);
        conversationSearch.setHint(t("native.search_conversations"));
        conversationSearch.setFilters(new InputFilter[] {new InputFilter.LengthFilter(200)});
        conversationSearch.setText(session.listQuery);
        conversationSearch.addTextChangedListener(textChanged(text ->
                session.setListFilter(text, session.showingArchived)));
        filters.addView(conversationSearch, new LinearLayout.LayoutParams(0, WRAP, 1));
        archivedButton = style.iconButton(PocketStyle.Icon.ARCHIVE,
                t("native.show_archived_conversations"), false, () -> {
            hideKeyboard();
            session.setListFilter(session.listQuery, !session.showingArchived);
        });
        filters.addView(archivedButton, new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(filters, new LinearLayout.LayoutParams(MATCH, WRAP));
        addList(false);
        conversationAdapter = new ConversationAdapter();
        moreConversationsButton = style.button(t("native.load_more_conversations"), false, session::loadMoreConversations);
        list.addFooterView(moreConversationsButton, null, false);
        list.setFooterDividersEnabled(false);
        list.setAdapter(conversationAdapter);
        list.setOnItemClickListener((parent, row, position, id) -> {
            if (!session.needsLogin && conversationAdapter.isSelectable(position)) {
                hideKeyboard();
                session.open(conversationAdapter.getItem(position));
            }
        });
        list.setOnItemLongClickListener((parent, row, position, id) -> {
            if (!session.needsLogin && conversationAdapter.isSelectable(position)) {
                manageConversation(conversationAdapter.getItem(position));
            }
            return true;
        });
        root.addView(style.rule(), new LinearLayout.LayoutParams(MATCH, dp(1)));
        root.addView(sidebarAction(PocketStyle.Icon.WORKSPACE, t("native.workspace"), this::openWorkspace),
                new LinearLayout.LayoutParams(MATCH, dp(48)));
        addServerStatus();
    }

    private Button sidebarAction(PocketStyle.Icon icon, String label, Runnable action) {
        Button button = style.button(label, false, action);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setTextSize(14);
        Drawable drawable = style.icon(icon, style.secondary);
        drawable.setBounds(0, 0, dp(18), dp(18));
        button.setCompoundDrawablesRelative(drawable, null, null, null);
        button.setCompoundDrawablePadding(dp(9));
        return button;
    }

    private void addServerStatus() {
        subtitle = style.metadata("", 11);
        subtitle.setSingleLine(true);
        subtitle.setEllipsize(TextUtils.TruncateAt.END);
        subtitle.setPadding(dp(14), dp(3), dp(14), dp(6));
        root.addView(subtitle, new LinearLayout.LayoutParams(MATCH, WRAP));
    }

    private void manageConversation(HermesClient.Conversation row) {
        hideKeyboard();
        String[] actions = {t("native.rename"), row.pinned ? t("native.unpin") : t("native.pin"),
                row.archived ? t("native.unarchive") : t("native.archive"), t("native.export_conversation_json")};
        new AlertDialog.Builder(this).setTitle(row.displayTitle()).setItems(actions, (dialog, choice) -> {
            if (choice == 0) {
                EditText title = new EditText(this);
                title.setSingleLine(true);
                title.setText(row.title);
                title.selectAll();
                AlertDialog rename = new AlertDialog.Builder(this).setTitle(t("native.rename_conversation"))
                        .setView(title).setNegativeButton(t("native.cancel"), null)
                        .setPositiveButton(t("native.save"), null).create();
                rename.show();
                rename.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                    String name = title.getText().toString().trim();
                    if (name.isEmpty()) {
                        title.setError(t("native.enter_a_conversation_name"));
                    } else {
                        session.updateSidebar(row, HermesClient.SidebarField.TITLE, name);
                        rename.dismiss();
                    }
                });
            } else if (choice == 1) {
                session.updateSidebar(row, HermesClient.SidebarField.PINNED, !row.pinned);
            } else if (choice == 2) {
                session.updateSidebar(row, HermesClient.SidebarField.ARCHIVED, !row.archived);
            } else {
                pickExportDestination(row);
            }
        }).show();
    }

    private void pickExportDestination(HermesClient.Conversation row) {
        if (session.exporting()) {
            return;
        }
        exportPickProfile = row.profile;
        exportPickId = row.id;
        exportPickConnection = session.connectionGeneration();
        Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("application/json")
                .putExtra(Intent.EXTRA_TITLE, "hermes-session.json");
        try {
            startActivityForResult(save, EXPORT_CHAT);
        } catch (ActivityNotFoundException error) {
            exportPickProfile = null;
            exportPickId = null;
            Toast.makeText(this, t("native.this_device_cannot_open_the_file_saver"), Toast.LENGTH_SHORT).show();
        }
    }

    private void openWorkspace() {
        Intent workspace = new Intent().setClassName(this, "cn.xhs.hermespocket.WorkspaceActivity");
        try {
            startActivity(workspace);
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this, t("native.workspace_unavailable"), Toast.LENGTH_SHORT).show();
        }
    }

    private void buildChat() {
        chatFollow = new ChatPresentation.Follow();
        if (TextUtils.equals(savedChatKey, shownKey)) {
            chatFollow.restoreDetached();
        } else {
            chatScrollPosition = -1;
        }
        savedChatKey = null;
        LinearLayout toolbar = toolbar();
        addToolbarIcon(toolbar, PocketStyle.Icon.WORKSPACE, t("native.back_to_conversations"), this::closeChat);
        addToolbarText(toolbar, style.label(chatTitle(), 12, style.text, true));
        addToolbarIcon(toolbar, PocketStyle.Icon.ATTACH, t("native.new_conversation"), session::openNew);
        addToolbarMenu(toolbar, new String[]{t("native.refresh_messages"), t("native.workspace"),
                        t("native.connection_settings")},
                new Runnable[]{session::refresh, this::openWorkspace, this::openSetup});
        addToolbar(toolbar);
        FrameLayout body = addList(true);
        list.setStackFromBottom(false);
        messageAdapter = new MessageAdapter();
        list.setAdapter(messageAdapter);
        latestButton = style.iconButton(PocketStyle.Icon.DOWN, t("native.jump_to_latest"), false, () -> {
            chatFollow.jumpToLatest();
            renderChatMessages();
            ((ChatListView) list).followLatest();
        });
        latestButton.setBackground(style.touch(style.surface, 24));
        latestButton.setId(R.id.jump_to_latest);
        latestButton.setVisibility(View.GONE);
        FrameLayout.LayoutParams latestSize = new FrameLayout.LayoutParams(dp(48), dp(48),
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        latestSize.bottomMargin = dp(8);
        body.addView(latestButton, latestSize);
        addNotice();

        controlBar = new LinearLayout(this);
        controlBar.setPadding(dp(14), 0, dp(14), 0);
        controlBar.setGravity(Gravity.END);
        stopButton = style.button(t("native.stop_reply"), false, this::confirmStop);
        queuedButton = style.button("", false, this::showQueue);
        pendingButton = style.button("", false, this::showPendingPrompts);
        controlBar.addView(stopButton);
        controlBar.addView(queuedButton);
        controlBar.addView(pendingButton);
        root.addView(controlBar, new LinearLayout.LayoutParams(MATCH, WRAP));

        LinearLayout footer = style.column();
        footer.setPadding(dp(16), dp(4), dp(16), dp(5));
        root.addView(footer, new LinearLayout.LayoutParams(MATCH, WRAP));
        attachmentList = style.column();
        shownAttachments = Collections.emptyList();
        footer.addView(attachmentList, new LinearLayout.LayoutParams(MATCH, WRAP));
        LinearLayout bottom = new LinearLayout(this);
        bottom.setGravity(Gravity.BOTTOM);
        bottom.setPadding(dp(2), 0, dp(2), 0);
        bottom.setBackground(style.box(style.background, 6, style.line));
        footer.addView(bottom, new LinearLayout.LayoutParams(MATCH, WRAP));
        attachButton = style.iconButton(PocketStyle.Icon.ATTACH, t("native.add_attachment"), false,
                this::pickAttachment);
        bottom.addView(attachButton, new LinearLayout.LayoutParams(dp(44), dp(48)));
        composer = new EditText(this);
        fieldStyle(composer);
        composer.setTextSize(15);
        composer.setPaddingRelative(dp(1), dp(12), dp(4), dp(12));
        composer.setBackgroundColor(Color.TRANSPARENT);
        composer.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        composer.setMaxLines(5);
        composer.setHint(t("native.enter_a_message_2"));
        composer.setGravity(Gravity.TOP | Gravity.START);
        composer.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        composer.setOnFocusChangeListener((view, focused) ->
                bottom.setBackground(style.box(style.background, 6, focused ? style.accent : style.line)));
        bottom.addView(composer, new LinearLayout.LayoutParams(0, WRAP, 1));
        String conversation = shownKey;
        composer.addTextChangedListener(textChanged(text -> {
            if (!syncComposer) {
                session.setDraft(conversation, text);
                renderChatControls();
            }
        }));
        sendButton = style.iconButton(PocketStyle.Icon.SEND, t("native.send_message"), true, session::send);
        composerStop = style.iconButton(PocketStyle.Icon.STOP, t("native.stop_reply"), true, this::confirmStop);
        FrameLayout actions = new FrameLayout(this);
        actions.addView(sendButton, new FrameLayout.LayoutParams(MATCH, MATCH));
        actions.addView(composerStop, new FrameLayout.LayoutParams(MATCH, MATCH));
        bottom.addView(actions, new LinearLayout.LayoutParams(dp(48), dp(48)));
        addServerStatus();
        root.requestFocus();
    }

    private void pickAttachment() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*");
        attachmentPickKey = shownKey;
        attachmentPickConnection = session.connectionGeneration();
        try {
            startActivityForResult(pick, PICK_ATTACHMENT);
        } catch (ActivityNotFoundException error) {
            attachmentPickKey = null;
            Toast.makeText(this, t("native.this_device_cannot_open_the_file_picker"), Toast.LENGTH_SHORT).show();
        }
    }

    private void useAttachment(String key, Uri uri) {
        if (uri == null || !ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
            return;
        }
        ContentResolver resolver = getApplicationContext().getContentResolver();
        try {
            String name = t("native.attachment");
            try (Cursor row = resolver.query(uri, new String[] {OpenableColumns.DISPLAY_NAME},
                    null, null, null)) {
                if (row != null && row.moveToFirst() && !row.isNull(0)) {
                    name = row.getString(0);
                }
            }
            String mime = resolver.getType(uri);
            boolean image = mime != null && mime.matches("image/(?:jpeg|png|gif|webp)")
                    || (mime == null || mime.equals("application/octet-stream"))
                    && name.matches("(?i).+\\.(?:jpe?g|png|gif|webp)");
            HermesClient.Attachment attachment = new HermesClient.Attachment(name, image,
                    () -> resolver.openInputStream(uri));
            if (!session.addAttachment(key, attachment)) {
                Toast.makeText(this, t("native.add_attachments_in_the_original_conversation_up_to_four_per"), Toast.LENGTH_SHORT).show();
            }
        } catch (RuntimeException error) {
            Toast.makeText(this, t("native.could_not_read_the_selected_attachment"), Toast.LENGTH_SHORT).show();
        }
    }

    private LinearLayout toolbar() {
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(4), 0, dp(4), 0);
        toolbar.setMinimumHeight(dp(48));
        toolbar.setBackgroundColor(style.surface);
        toolbar.setLayoutParams(new LinearLayout.LayoutParams(MATCH, WRAP));
        return toolbar;
    }

    private void addToolbar(LinearLayout toolbar) {
        root.addView(toolbar);
        root.addView(style.rule(), new LinearLayout.LayoutParams(MATCH, dp(1)));
    }

    private void addToolbarIcon(LinearLayout toolbar, PocketStyle.Icon icon, String label, Runnable action) {
        toolbar.addView(style.iconButton(icon, label, false, action),
                new LinearLayout.LayoutParams(dp(48), dp(48)));
    }

    private void addToolbarMenu(LinearLayout toolbar, String[] labels, Runnable[] actions) {
        ImageButton button = style.iconButton(PocketStyle.Icon.MORE, t("native.more_actions"),
                false, () -> {});
        button.setOnClickListener(anchor -> {
            int theme = style.dark ? android.R.style.Theme_Material : android.R.style.Theme_Material_Light;
            PopupMenu menu = new PopupMenu(new android.view.ContextThemeWrapper(this, theme), anchor);
            for (int index = 0; index < labels.length; index++) {
                menu.getMenu().add(0, index + 1, index, labels[index]);
            }
            menu.setOnMenuItemClickListener(item -> {
                actions[item.getItemId() - 1].run();
                return true;
            });
            menu.show();
        });
        toolbar.addView(button, new LinearLayout.LayoutParams(dp(48), dp(48)));
    }

    private void addToolbarText(LinearLayout toolbar, TextView value) {
        LinearLayout tab = style.column();
        title = value;
        title.setTextLocale(HermesText.locale());
        title.setAllCaps(true);
        title.setTextSize(12);
        title.setSingleLine();
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setMaxWidth(dp(Math.max(100, getResources().getConfiguration().screenWidthDp - 160)));
        title.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        title.setPaddingRelative(dp(10), 0, dp(8), 0);
        if (Build.VERSION.SDK_INT >= 28) {
            title.setAccessibilityHeading(true);
        }
        tab.addView(title, new LinearLayout.LayoutParams(MATCH, 0, 1));
        View indicator = new View(this);
        indicator.setBackgroundColor(style.accent);
        tab.addView(indicator, new LinearLayout.LayoutParams(MATCH, dp(2)));
        toolbar.addView(tab, new LinearLayout.LayoutParams(WRAP, dp(48)));
        toolbar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
    }

    private void addNotice() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(4), dp(4), dp(4));
        LinearLayout.LayoutParams barSize = new LinearLayout.LayoutParams(MATCH, WRAP);
        barSize.setMargins(dp(14), dp(9), dp(14), 0);
        root.addView(bar, barSize);
        notice = style.label("", 13, style.secondary, false);
        notice.setLineSpacing(dp(2), 1);
        notice.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        bar.addView(notice, new LinearLayout.LayoutParams(0, WRAP, 1));
        noticeAction = style.button(t("native.refresh"), false, this::noticeAction);
        bar.addView(noticeAction, new LinearLayout.LayoutParams(WRAP, WRAP));
        activityIndicator = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
        activityIndicator.setIndeterminateTintList(ColorStateList.valueOf(style.accent));
        LinearLayout.LayoutParams indicatorSize = new LinearLayout.LayoutParams(dp(19), dp(19));
        indicatorSize.setMargins(dp(7), dp(5), dp(7), dp(5));
        bar.addView(activityIndicator, indicatorSize);
    }

    private void noticeAction() {
        if (session.needsLogin) {
            openSetup();
        } else {
            session.refresh();
        }
    }

    private FrameLayout addList(boolean chat) {
        FrameLayout body = new FrameLayout(this);
        root.addView(body, new LinearLayout.LayoutParams(MATCH, 0, 1));
        list = chat ? new ChatListView() : new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setClipToPadding(false);
        list.setPadding(chat ? dp(24) : dp(6), dp(4), chat ? dp(24) : dp(6), dp(8));
        list.setSelector(style.touch(Color.TRANSPARENT, 0));
        list.setDrawSelectorOnTop(true);
        list.setVerticalScrollBarEnabled(false);
        body.addView(list, new FrameLayout.LayoutParams(MATCH, MATCH));

        empty = style.column();
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(26), dp(9), dp(26), dp(18));
        body.addView(empty, new FrameLayout.LayoutParams(MATCH, MATCH));
        ImageView symbol = chat ? style.mark()
                : style.symbol(PocketStyle.Icon.CONVERSATION, style.accent);
        empty.addView(symbol, new LinearLayout.LayoutParams(dp(34), dp(34)));
        emptyTitle = style.label("", 17, style.text, true);
        emptyTitle.setGravity(Gravity.CENTER);
        withMargin(empty, emptyTitle, 12, 0);
        emptyDetail = style.label("", 14, style.secondary, false);
        emptyDetail.setGravity(Gravity.CENTER);
        emptyDetail.setLineSpacing(dp(3), 1);
        withMargin(empty, emptyDetail, 7, 0);
        return body;
    }

    private void renderConversations() {
        subtitle.setText(serverLabel());
        newConversationButton.setVisibility(session.needsLogin ? View.GONE : View.VISIBLE);
        String archiveLabel = session.showingArchived
                ? t("native.show_recent_conversations") : t("native.show_archived_conversations");
        archivedButton.setContentDescription(archiveLabel);
        archivedButton.setTooltipText(archiveLabel);
        archivedButton.setImageTintList(ColorStateList.valueOf(session.showingArchived ? style.accent : style.secondary));
        archivedButton.setEnabled(!session.needsLogin);
        moreConversationsButton.setVisibility(session.nextConversationOffset > 0
                && !session.needsLogin ? View.VISIBLE : View.GONE);
        moreConversationsButton.setEnabled(!session.loadingConversations);
        conversationSearch.setEnabled(!session.needsLogin);
        if (!TextUtils.equals(conversationSearch.getText(), session.listQuery)) {
            conversationSearch.setText(session.listQuery);
        }
        conversationAdapter.showConversations(session.conversations);
        String error = session.needsLogin ? t("native.your_session_expired_sign_in_to_hermes_again") : session.conversationError;
        String notice = session.openingFromWorkspace() ? t("native.opening_workspace_conversation")
                : session.exporting() ? t("native.exporting_conversation") : session.conversationNotice;
        showNotice(error == null ? notice : error, error != null, session.openingFromWorkspace()
                || session.exporting() || session.loadingConversations && !session.conversations.isEmpty());
        String emptyHeading = !session.listQuery.trim().isEmpty() ? t("native.no_matching_conversations")
                : session.showingArchived ? t("native.no_archived_conversations_yet") : t("native.no_conversations_yet");
        showEmpty(session.conversations.isEmpty(),
                session.loadingConversations ? t("native.loading_conversations") : error == null ? emptyHeading : t("native.could_not_load_conversations"),
                "");
    }

    private void renderChat() {
        title.setText(chatTitle());
        subtitle.setText(serverLabel());
        renderChatMessages();
        String turn = session.turnStatus();
        String error = session.needsLogin ? t("native.your_session_expired_sign_in_to_hermes_again") : session.historyError;
        if (error == null) {
            error = session.controlNotice;
        }
        if (error == null && turn != null && !session.turnActive()) {
            error = turn;
        }
        boolean loading = session.loadingHistory || session.turnActive();
        showNotice(error != null ? error : turn, error != null, loading && error == null);
        String emptyHeading = session.composingNew ? t("native.enter_your_first_message")
                : session.loadingHistory ? t("native.loading_messages")
                : session.historyError == null ? t("native.no_messages_yet") : t("native.could_not_load_messages");
        showEmpty(messageAdapter.getCount() == 0, emptyHeading,
                session.composingNew || session.loadingHistory || error != null ? "" : t("native.enter_a_message_and_send_it"));
        String draft = session.draft(shownKey);
        if (!TextUtils.equals(composer.getText(), draft)) {
            syncComposer = true;
            composer.setText(draft);
            composer.setSelection(draft.length());
            syncComposer = false;
        }
        composer.setEnabled(session.canEditDraft());
        boolean queues = session.queuesNextMessage();
        composer.setHint(queues ? t("native.enter_the_next_message") : t("native.enter_a_message_2"));
        sendButton.setContentDescription(queues ? t("native.queue_message") : t("native.send_message"));
        List<HermesClient.Attachment> attachments = session.attachments(shownKey);
        attachButton.setEnabled(session.canEditDraft() && attachments.size() < 4);
        renderAttachments(attachments);
        renderChatControls();
    }

    private void showToolActivity() {
        List<HermesReplyStream.ToolActivity> tools = session.visibleTools();
        if (tools.isEmpty()) {
            return;
        }
        List<String> lines = new ArrayList<>();
        for (HermesReplyStream.ToolActivity tool : tools) {
            String state;
            if (tool.completed) {
                state = tool.duration >= 0
                        ? t("native.finished_1f_s", tool.duration)
                        : t("native.finished");
            } else if (session.toolTurnEnded()) {
                state = t("native.ended_with_this_turn");
            } else {
                state = tool.percent >= 0 ? t("native.tool_running_percent", tool.percent) : t("native.tool_running_status");
            }
            lines.add(tool.name + " · " + state + (tool.detail.isEmpty() ? "" : "\n" + tool.detail));
        }
        new AlertDialog.Builder(this).setTitle(t("native.tool_activity"))
                .setMessage(String.join("\n\n", lines)).setPositiveButton(t("native.close"), null).show();
    }

    private void renderAttachments(List<HermesClient.Attachment> attachments) {
        if (shownAttachments.equals(attachments)) {
            return;
        }
        shownAttachments = attachments;
        attachmentList.removeAllViews();
        for (HermesClient.Attachment attachment : attachments) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = style.label(attachment.name, 13, style.secondary, false);
            name.setSingleLine();
            name.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(name, new LinearLayout.LayoutParams(0, WRAP, 1));
            String key = shownKey;
            Button remove = style.button(t("native.remove"), false, () -> session.removeAttachment(key, attachment));
            remove.setContentDescription(t("native.remove_attachment", attachment.name));
            row.addView(remove);
            attachmentList.addView(row, new LinearLayout.LayoutParams(MATCH, WRAP));
        }
    }

    private void renderChatControls() {
        ChatControls controls = session.controls;
        boolean running = controls != null && controls.running;
        boolean draftPresent = !session.draft(shownKey).trim().isEmpty() || !session.attachments(shownKey).isEmpty();
        boolean stopInComposer = running && !draftPresent;
        int queued = controls == null ? 0 : controls.queued.size();
        int pending = controls == null ? 0 : controls.prompts.size();
        controlBar.setVisibility(running && draftPresent || queued > 0 || pending > 0 ? View.VISIBLE : View.GONE);
        stopButton.setVisibility(running && draftPresent ? View.VISIBLE : View.GONE);
        composerStop.setVisibility(stopInComposer ? View.VISIBLE : View.GONE);
        sendButton.setVisibility(stopInComposer ? View.GONE : View.VISIBLE);
        composerStop.setEnabled(!session.needsLogin);
        sendButton.setEnabled(session.canSend());
        queuedButton.setVisibility(queued > 0 ? View.VISIBLE : View.GONE);
        pendingButton.setVisibility(pending > 0 ? View.VISIBLE : View.GONE);
        queuedButton.setText(t("native.queue", queued));
        pendingButton.setText(t("native.pending", pending));
    }

    private void confirmStop() {
        ChatControls snapshot = session.controls;
        if (snapshot != null && snapshot.running) {
            new AlertDialog.Builder(this).setTitle(t("native.stop_the_current_reply"))
                    .setMessage(t("native.hermes_will_interrupt_the_current_turn_in_this_conversation"))
                    .setNegativeButton(t("native.back"), null)
                    .setPositiveButton(t("native.stop"), (dialog, button) ->
                            session.actOnControl(snapshot, HermesClient.ControlAction.STOP,
                                    null, null, null)).show();
        }
    }

    private void showQueue() {
        ChatControls snapshot = session.controls;
        if (snapshot == null || snapshot.queued.isEmpty()) {
            return;
        }
        List<String> labels = new ArrayList<>();
        for (ChatControls.Queued row : snapshot.queued) {
            labels.add(row.text.isEmpty() ? t("native.queued_messages") : row.text);
        }
        new AlertDialog.Builder(this).setTitle(t("native.queued_messages"))
                .setItems(labels.toArray(new String[0]), (dialog, index) ->
                        manageQueued(snapshot, snapshot.queued.get(index)))
                .setNegativeButton(t("native.close"), null).show();
    }

    private void manageQueued(ChatControls snapshot, ChatControls.Queued row) {
        boolean steer = snapshot.running && row.canSteer;
        String[] actions = steer ? new String[] {t("native.add_to_current_reply"), t("native.withdraw_queued_message")}
                : new String[] {t("native.withdraw_queued_message")};
        new AlertDialog.Builder(this).setTitle(t("native.manage_queued_message"))
                .setItems(actions, (dialog, index) -> session.actOnControl(snapshot,
                        steer && index == 0 ? HermesClient.ControlAction.STEER_QUEUE
                                : HermesClient.ControlAction.CANCEL_QUEUE,
                        row.id, null, null))
                .setNegativeButton(t("native.back"), null).show();
    }

    private void showPendingPrompts() {
        ChatControls snapshot = session.controls;
        if (snapshot == null || snapshot.prompts.isEmpty()) {
            return;
        }
        if (snapshot.prompts.size() == 1) {
            showPrompt(snapshot, snapshot.prompts.get(0));
            return;
        }
        List<String> labels = new ArrayList<>();
        for (ChatControls.Prompt prompt : snapshot.prompts) {
            labels.add(prompt.approval() ? t("native.approval_request") : prompt.body);
        }
        new AlertDialog.Builder(this).setTitle(t("native.pending_requests"))
                .setItems(labels.toArray(new String[0]), (dialog, index) ->
                        showPrompt(snapshot, snapshot.prompts.get(index)))
                .setNegativeButton(t("native.close"), null).show();
    }

    private void showPrompt(ChatControls snapshot, ChatControls.Prompt prompt) {
        if (prompt.approval()) {
            AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle(t("native.approval_request"))
                    .setMessage(prompt.body).setNegativeButton(t("native.close"), null);
            if (prompt.canAnswer("deny")) {
                dialog.setNeutralButton(t("native.deny"), (window, button) -> answer(snapshot, prompt, "deny"));
            }
            if (prompt.canAnswer("once")) {
                dialog.setPositiveButton(t("native.allow_once"), (window, button) ->
                        answer(snapshot, prompt, "once"));
            }
            dialog.show();
            return;
        }
        if (prompt.choices.isEmpty()) {
            typeClarification(snapshot, prompt);
        } else if (prompt.multiSelect) {
            chooseClarifications(snapshot, prompt);
        } else {
            new AlertDialog.Builder(this).setTitle(prompt.body)
                    .setItems(prompt.choices.toArray(new String[0]), (dialog, index) ->
                            answer(snapshot, prompt, prompt.choices.get(index)))
                    .setNegativeButton(t("native.close"), null)
                    .setNeutralButton(t("native.skip"), (dialog, button) -> answer(snapshot, prompt, ""))
                    .setPositiveButton(t("native.other"), (dialog, button) ->
                            typeClarification(snapshot, prompt)).show();
        }
    }

    private void chooseClarifications(ChatControls snapshot, ChatControls.Prompt prompt) {
        boolean[] checked = new boolean[prompt.choices.size()];
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(prompt.body)
                .setMultiChoiceItems(prompt.choices.toArray(new String[0]), checked,
                        (window, index, selected) -> checked[index] = selected)
                .setNeutralButton(t("native.skip"), (window, button) -> answer(snapshot, prompt, ""))
                .setNegativeButton(t("native.close"), null).setPositiveButton(t("native.submit"), null).create();
        dialog.show();
        Button submit = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        submit.setOnClickListener(view -> {
            JSONArray choices = new JSONArray();
            for (int i = 0; i < checked.length; i++) {
                if (checked[i]) {
                    choices.put(prompt.choices.get(i));
                }
            }
            if (choices.length() > 0) {
                answer(snapshot, prompt, choices.toString());
                dialog.dismiss();
            }
        });
    }

    private void typeClarification(ChatControls snapshot, ChatControls.Prompt prompt) {
        EditText input = new EditText(this);
        input.setHint(t("native.enter_an_answer"));
        input.setFilters(new InputFilter[] {new InputFilter.LengthFilter(16384)});
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(prompt.body)
                .setView(input).setNegativeButton(t("native.close"), null)
                .setNeutralButton(t("native.skip"), (window, button) -> answer(snapshot, prompt, ""))
                .setPositiveButton(t("native.submit"), null).create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String value = input.getText().toString().trim();
            if (value.isEmpty()) {
                input.setError(t("native.enter_an_answer_2"));
                return;
            }
            answer(snapshot, prompt, value);
            dialog.dismiss();
        });
    }

    private void answer(ChatControls snapshot, ChatControls.Prompt prompt, String value) {
        session.actOnControl(snapshot, HermesClient.ControlAction.RESPOND, null, prompt, value);
    }

    private void renderChatMessages() {
        // Changing ListView's dataset interrupts an active drag or fling.
        if (chatFollow.dragging()) {
            latestButton.setVisibility(chatFollow.followsLatest() || messageAdapter.getCount() == 0
                    ? View.GONE : View.VISIBLE);
            return;
        }
        List<HermesClient.Message> rows = session.visibleMessages();
        boolean following = chatFollow.followsLatest();
        int position = list.getFirstVisiblePosition();
        boolean hasAnchor = !following && list.getChildCount() > 0
                && position < messageAdapter.getCount();
        String anchor = hasAnchor ? messageAdapter.getItem(position).id : null;
        int offset = hasAnchor ? firstMessageOffset() : 0;
        list.setTranscriptMode(following ? AbsListView.TRANSCRIPT_MODE_ALWAYS_SCROLL
                : AbsListView.TRANSCRIPT_MODE_DISABLED);
        boolean toolsChanged = messageAdapter.setTools(session.visibleToolRowId(), session.visibleTools());
        boolean messagesChanged = messageAdapter.replace(rows);
        if (toolsChanged && !messagesChanged) {
            messageAdapter.notifyDataSetChanged();
        }
        if ((messagesChanged || toolsChanged) && !rows.isEmpty()) {
            if (chatScrollPosition >= 0) {
                list.setSelectionFromTop(Math.min(chatScrollPosition, rows.size() - 1), chatScrollOffset);
                chatScrollPosition = -1;
            } else if (anchor != null) {
                int target = Math.min(position, rows.size() - 1);
                for (int index = 0; index < rows.size(); index++) {
                    if (anchor.equals(rows.get(index).id)) {
                        target = index;
                        break;
                    }
                }
                list.setSelectionFromTop(target, offset);
            }
        }
        latestButton.setVisibility(following || rows.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private int firstMessageOffset() {
        return list.getChildAt(0).getTop() - list.getPaddingTop();
    }

    private String chatTitle() {
        return session.selected == null ? t("native.new_conversation") : session.selected.displayTitle();
    }

    private static String relativeTime(long timestamp) {
        java.util.Locale locale = HermesText.locale();
        long minutes = (timestamp - System.currentTimeMillis()) / 60000;
        long distance = Math.abs(minutes);
        if (distance >= 7 * 24 * 60) {
            return java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, locale)
                    .format(new Date(timestamp));
        }
        android.icu.text.RelativeDateTimeFormatter formatter =
                android.icu.text.RelativeDateTimeFormatter.getInstance(locale);
        if (minutes == 0) {
            return formatter.format(android.icu.text.RelativeDateTimeFormatter.Direction.PLAIN,
                    android.icu.text.RelativeDateTimeFormatter.AbsoluteUnit.NOW);
        }
        if (distance >= 24 * 60) {
            return formatter.formatNumeric(minutes / (24 * 60),
                    android.icu.text.RelativeDateTimeFormatter.RelativeDateTimeUnit.DAY);
        }
        if (distance >= 60) {
            return formatter.formatNumeric(minutes / 60,
                    android.icu.text.RelativeDateTimeFormatter.RelativeDateTimeUnit.HOUR);
        }
        return formatter.formatNumeric(minutes,
                android.icu.text.RelativeDateTimeFormatter.RelativeDateTimeUnit.MINUTE);
    }

    private void showNotice(String message, boolean error, boolean loading) {
        View bar = (View) notice.getParent();
        bar.setVisibility(message == null && !loading ? View.GONE : View.VISIBLE);
        int background = error ? style.problemTint : style.tint;
        bar.setBackground(style.box(background, 8, 0));
        String detail = message != null ? message : t("native.syncing");
        if (!TextUtils.equals(notice.getText(), detail)) {
            notice.setText(detail);
        }
        notice.setTextColor(error ? style.problem : style.accent);
        noticeAction.setText(session.needsLogin ? t("native.sign_in_again") : t("native.refresh"));
        noticeAction.setVisibility(error ? View.VISIBLE : View.GONE);
        activityIndicator.setVisibility(loading ? View.VISIBLE : View.GONE);
    }

    private void showEmpty(boolean visible, String heading, String detail) {
        empty.setVisibility(visible ? View.VISIBLE : View.GONE);
        list.setVisibility(visible ? View.INVISIBLE : View.VISIBLE);
        emptyTitle.setText(heading);
        emptyDetail.setText(detail);
        emptyDetail.setVisibility(detail.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private String serverLabel() {
        Uri server = Uri.parse(session.server);
        String host = server.getHost();
        if (host == null) {
            return session.server;
        }
        return server.getPort() == -1 ? host : host + ":" + server.getPort();
    }

    private void fieldStyle(EditText input) {
        input.setTextSize(16);
        input.setTextColor(style.text);
        input.setHintTextColor(style.secondary);
        input.setHighlightColor(style.tint);
        if (Build.VERSION.SDK_INT >= 29) {
            Drawable cursor = input.getTextCursorDrawable();
            if (cursor != null) {
                cursor = cursor.mutate();
                cursor.setTint(style.accent);
                input.setTextCursorDrawable(cursor);
            }
        }
        input.setSelectAllOnFocus(false);
        input.setPadding(dp(13), dp(12), dp(13), dp(12));
        input.setMinimumHeight(dp(48));
    }

    private void withMargin(LinearLayout parent, View child, int top, int bottom) {
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(MATCH, WRAP);
        layout.topMargin = dp(top);
        layout.bottomMargin = dp(bottom);
        parent.addView(child, layout);
    }

    private int dp(int value) {
        return style.dp(value);
    }

    private interface TextUpdate {
        void update(String value);
    }

    private static TextWatcher textChanged(TextUpdate update) {
        return new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
                update.update(text.toString());
            }

            @Override
            public void afterTextChanged(Editable text) {}
        };
    }

    private final class ChatListView extends ListView {
        private final int touchSlop = ViewConfiguration.get(MainActivity.this).getScaledTouchSlop();
        private int scrollState = OnScrollListener.SCROLL_STATE_IDLE;
        private int touchPointer = -1;
        private float touchStartX;
        private float touchStartY;
        private boolean settlePosted;
        private final Runnable settleScroll = this::checkScrollSettled;
        private final Runnable renderAfterGesture = () -> {
            if (screen == Screen.CHAT && list == this && isAttachedToWindow()) {
                renderChatMessages();
            }
        };

        ChatListView() {
            super(MainActivity.this);
            setOnScrollListener(new OnScrollListener() {
                @Override
                public void onScrollStateChanged(AbsListView view, int state) {
                    scrollState = state;
                    if (state == SCROLL_STATE_IDLE) {
                        chatFollow.scrollIdle(atBottom());
                    }
                    updateAfterGesture();
                }

                @Override
                public void onScroll(AbsListView view, int first, int visible, int total) {
                    scheduleScrollSettle();
                }
            });
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                chatFollow.touchStarted();
                cancelScrollSettle();
                touchPointer = event.getPointerId(0);
                touchStartX = event.getX(0);
                touchStartY = event.getY(0);
                setTranscriptMode(TRANSCRIPT_MODE_DISABLED);
            } else if (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP
                    || action == MotionEvent.ACTION_POINTER_UP) {
                trackTouchDirection(event);
            }
            boolean handled = super.dispatchTouchEvent(event);
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                    || action == MotionEvent.ACTION_DOWN && !handled) {
                boolean scrolling = scrollState != OnScrollListener.SCROLL_STATE_IDLE;
                if (action == MotionEvent.ACTION_UP) {
                    chatFollow.touchEnded(scrolling, atBottom());
                } else {
                    chatFollow.touchCancelled(scrolling);
                }
                touchPointer = -1;
                scheduleScrollSettle();
                updateAfterGesture();
            }
            return handled;
        }

        private void trackTouchDirection(MotionEvent event) {
            int pointer = event.findPointerIndex(touchPointer);
            if (pointer < 0) {
                return;
            }
            float deltaX = event.getX(pointer) - touchStartX;
            float deltaY = event.getY(pointer) - touchStartY;
            boolean following = chatFollow.followsLatest();
            boolean atBottom = atBottom();
            boolean scrollable = getChildCount() > 0 && (canScrollList(-1) || !atBottom);
            chatFollow.touchMoved(deltaX, deltaY, touchSlop, scrollable, atBottom);
            if (following && !chatFollow.followsLatest()) {
                setTranscriptMode(TRANSCRIPT_MODE_DISABLED);
                updateAfterGesture();
            }
            if (event.getActionMasked() == MotionEvent.ACTION_POINTER_UP
                    && event.getActionIndex() == pointer) {
                int replacement = pointer == 0 ? 1 : 0;
                touchPointer = event.getPointerId(replacement);
                touchStartX = event.getX(replacement) - deltaX;
                touchStartY = event.getY(replacement) - deltaY;
            }
        }

        @Override
        protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            boolean hasAnchor = getChildCount() > 0;
            int position = getFirstVisiblePosition();
            int offset = hasAnchor ? getChildAt(0).getTop() - getPaddingTop() : 0;
            super.onSizeChanged(width, height, oldWidth, oldHeight);
            if (hasAnchor && list == this && !chatFollow.dragging()) {
                if (chatFollow.followsLatest()) {
                    followLatest();
                } else if (chatScrollPosition < 0) {
                    setSelectionFromTop(position, offset);
                }
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            cancelScrollSettle();
            removeCallbacks(renderAfterGesture);
            super.onDetachedFromWindow();
        }

        private void scheduleScrollSettle() {
            if (!settlePosted && list == this && isAttachedToWindow()
                    && chatFollow.awaitingScrollIdle()) {
                settlePosted = true;
                postOnAnimation(settleScroll);
            }
        }

        private void cancelScrollSettle() {
            if (settlePosted) {
                removeCallbacks(settleScroll);
                settlePosted = false;
            }
        }

        private void checkScrollSettled() {
            settlePosted = false;
            if (list != this || !isAttachedToWindow() || !chatFollow.awaitingScrollIdle()) {
                return;
            }
            // At a fling boundary the platform can leave its scroll state in FLING.
            // Compare consecutive animation frames to release rebinds once motion stops.
            if (getChildCount() == 0 || chatFollow.scrollPositionStable(
                    getFirstVisiblePosition(), getChildAt(0).getTop())) {
                scrollState = OnScrollListener.SCROLL_STATE_IDLE;
                chatFollow.scrollIdle(atBottom());
                updateAfterGesture();
            } else {
                scheduleScrollSettle();
            }
        }

        private void updateAfterGesture() {
            removeCallbacks(renderAfterGesture);
            post(renderAfterGesture);
        }

        private boolean atBottom() {
            return getChildCount() > 0 && !canScrollList(1);
        }

        void followLatest() {
            if (messageAdapter.getCount() > 0) {
                setTranscriptMode(TRANSCRIPT_MODE_ALWAYS_SCROLL);
                messageAdapter.notifyDataSetChanged();
            }
        }
    }

    private abstract class Rows<T> extends BaseAdapter {
        private List<T> rows = Collections.emptyList();

        boolean replace(List<T> replacement) {
            if (rows.equals(replacement)) {
                return false;
            }
            rows = replacement;
            notifyDataSetChanged();
            return true;
        }

        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public T getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }
    }

    private final class ConversationAdapter extends Rows<HermesClient.Conversation> {
        private int pinnedHeading = -1;

        void showConversations(List<HermesClient.Conversation> conversations) {
            List<HermesClient.Conversation> displayed = new ArrayList<>();
            pinnedHeading = -1;
            if (!session.showingArchived) {
                for (HermesClient.Conversation conversation : conversations) {
                    if (conversation.pinned) {
                        if (pinnedHeading < 0) {
                            pinnedHeading = displayed.size();
                            displayed.add(null);
                        }
                        displayed.add(conversation);
                    }
                }
            }
            boolean heading = false;
            for (HermesClient.Conversation conversation : conversations) {
                if (session.showingArchived || !conversation.pinned) {
                    if (!heading) {
                        displayed.add(null);
                        heading = true;
                    }
                    displayed.add(conversation);
                }
            }
            replace(displayed);
        }

        boolean isSelectable(int position) {
            return position >= 0 && position < getCount() && getItem(position) != null;
        }

        @Override
        public boolean areAllItemsEnabled() {
            return false;
        }

        @Override
        public boolean isEnabled(int position) {
            return isSelectable(position);
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            return isSelectable(position) ? 0 : 1;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (!isSelectable(position)) {
                TextView heading = convertView == null
                        ? style.metadata("", 11) : (TextView) convertView;
                heading.setText(position == pinnedHeading ? t("native.pinned_group")
                        : session.showingArchived ? t("native.archived_group") : t("native.conversations"));
                heading.setTextLocale(HermesText.locale());
                heading.setAllCaps(true);
                heading.setLetterSpacing(0.10f);
                heading.setTextColor(style.accent);
                heading.setPaddingRelative(dp(12), dp(10), dp(8), dp(4));
                heading.setAccessibilityHeading(true);
                return heading;
            }
            ConversationRow row = convertView == null
                    ? new ConversationRow() : (ConversationRow) convertView;
            HermesClient.Conversation conversation = getItem(position);
            row.value = conversation;
            row.name.setText(conversation.displayTitle());
            String time = conversation.lastActiveMillis > 0
                    ? relativeTime(conversation.lastActiveMillis) : "";
            row.time.setText(conversation.running ? t("native.in_progress") : time);
            row.preview.setText(conversation.preview);
            row.preview.setVisibility(session.listQuery.trim().isEmpty() || row.preview.length() == 0
                    ? View.GONE : View.VISIBLE);
            row.dot.setBackground(style.box(conversation.running ? style.accent : style.secondary, 3, 0));
            row.dot.setVisibility(conversation.pinned ? View.GONE : View.VISIBLE);
            row.pin.setVisibility(conversation.pinned ? View.VISIBLE : View.GONE);
            return row;
        }
    }

    private final class ConversationRow extends LinearLayout {
        HermesClient.Conversation value;
        final TextView name;
        final TextView preview;
        final TextView time;
        final View dot;
        final ImageView pin;

        ConversationRow() {
            super(MainActivity.this);
            setOrientation(VERTICAL);
            setLayoutParams(new AbsListView.LayoutParams(MATCH, WRAP));
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), 0, dp(2), 0);
            row.setMinimumHeight(dp(48));
            row.setBackground(style.touch(Color.TRANSPARENT, 4));
            addView(row, new LinearLayout.LayoutParams(MATCH, WRAP));

            FrameLayout lead = new FrameLayout(MainActivity.this);
            LinearLayout.LayoutParams leadingSize = new LinearLayout.LayoutParams(dp(13), dp(16));
            leadingSize.setMarginEnd(dp(7));
            row.addView(lead, leadingSize);
            dot = new View(MainActivity.this);
            lead.addView(dot, new FrameLayout.LayoutParams(dp(4), dp(4), Gravity.CENTER));
            pin = style.symbol(PocketStyle.Icon.PIN, style.secondary);
            lead.addView(pin, new FrameLayout.LayoutParams(dp(13), dp(13), Gravity.CENTER));
            LinearLayout names = style.column();
            row.addView(names, new LinearLayout.LayoutParams(0, WRAP, 1));

            LinearLayout headline = new LinearLayout(MainActivity.this);
            headline.setGravity(Gravity.CENTER_VERTICAL);
            names.addView(headline, new LinearLayout.LayoutParams(MATCH, WRAP));
            name = style.label("", 14, style.text, false);
            name.setSingleLine();
            name.setEllipsize(TextUtils.TruncateAt.END);
            headline.addView(name, new LinearLayout.LayoutParams(0, WRAP, 1));
            time = style.label("", 11, style.secondary, false);
            time.setSingleLine();
            time.setMaxWidth(dp(105));
            time.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams timeSize = new LinearLayout.LayoutParams(WRAP, WRAP);
            timeSize.setMarginStart(dp(9));
            headline.addView(time, timeSize);
            preview = style.label("", 12, style.secondary, false);
            preview.setSingleLine();
            preview.setEllipsize(TextUtils.TruncateAt.END);
            withMargin(names, preview, 4, 0);

            ImageButton more = style.iconButton(PocketStyle.Icon.MORE, t("native.more_actions"), false, () -> {
                if (value != null && !session.needsLogin) {
                    manageConversation(value);
                }
            });
            more.setFocusable(false);
            row.addView(more, new LinearLayout.LayoutParams(dp(48), dp(48)));
        }
    }

    private final class MessageAdapter extends Rows<HermesClient.Message> {
        private String toolRowId;
        private List<HermesReplyStream.ToolActivity> tools = Collections.emptyList();

        boolean setTools(String id, List<HermesReplyStream.ToolActivity> activities) {
            if (TextUtils.equals(id, toolRowId) && tools.equals(activities)) {
                return false;
            }
            toolRowId = id;
            tools = activities;
            return true;
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            return "user".equals(getItem(position).role) ? 1 : 0;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            MessageRow row = convertView == null
                    ? new MessageRow(getItemViewType(position) == 1) : (MessageRow) convertView;
            HermesClient.Message message = getItem(position);
            row.body.setText(message.content);
            row.showTools(TextUtils.equals(message.id, toolRowId) ? tools : Collections.emptyList());
            if (message.timestampMillis > 0) {
                java.util.Locale locale = HermesText.locale();
                String skeleton = android.text.format.DateFormat.is24HourFormat(MainActivity.this)
                        ? "Hm" : "hm";
                String pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton);
                row.time.setText(new java.text.SimpleDateFormat(pattern, locale)
                        .format(new Date(message.timestampMillis)));
                row.time.setVisibility(View.VISIBLE);
            } else {
                row.time.setVisibility(View.GONE);
            }
            return row;
        }

        @Override
        public boolean isEnabled(int position) {
            return false;
        }

        @Override
        public boolean areAllItemsEnabled() {
            return false;
        }
    }

    private final class MessageRow extends LinearLayout {
        final TextView body;
        final TextView time;
        final Button tool;

        MessageRow(boolean mine) {
            super(MainActivity.this);
            setLayoutParams(new AbsListView.LayoutParams(MATCH, WRAP));
            setOrientation(VERTICAL);
            setPaddingRelative(0, dp(4), 0, dp(3));

            LinearLayout message = style.column();
            if (mine) {
                message.setPadding(dp(12), dp(9), dp(12), dp(9));
                message.setBackground(style.box(style.surface, 4, style.line));
                tool = null;
            } else {
                message.setPadding(dp(12), 0, dp(12), dp(4));
                tool = sidebarAction(PocketStyle.Icon.TOOL, "", MainActivity.this::showToolActivity);
                tool.setSingleLine(true);
                tool.setEllipsize(TextUtils.TruncateAt.END);
                tool.setTextSize(12);
                tool.setTextColor(style.secondary);
                tool.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                tool.setPaddingRelative(0, 0, dp(6), 0);
                tool.setVisibility(View.GONE);
                message.addView(tool, new LinearLayout.LayoutParams(MATCH, dp(44)));
            }
            addView(message, new LinearLayout.LayoutParams(MATCH, WRAP));

            body = style.label("", 15, style.text, false);
            body.setLineSpacing(dp(3), 1.0f);
            body.setTextIsSelectable(true);
            message.addView(body, new LinearLayout.LayoutParams(MATCH, WRAP));
            time = style.label("", 11, style.secondary, false);
            time.setSingleLine();
            time.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams timeSize = new LinearLayout.LayoutParams(WRAP, WRAP);
            timeSize.gravity = Gravity.END;
            timeSize.setMarginEnd(dp(1));
            timeSize.topMargin = dp(3);
            addView(time, timeSize);
        }

        void showTools(List<HermesReplyStream.ToolActivity> activities) {
            if (tool == null) {
                return;
            }
            tool.setVisibility(activities.isEmpty() ? View.GONE : View.VISIBLE);
            if (activities.size() == 1) {
                HermesReplyStream.ToolActivity activity = activities.get(0);
                String firstDetail = activity.detail.split("\n", 2)[0].trim();
                tool.setText(firstDetail.isEmpty() ? activity.name : activity.name + " · " + firstDetail);
            } else if (activities.size() > 1) {
                tool.setText(t("native.tool_activity_count", activities.size()));
            }
        }
    }
}
