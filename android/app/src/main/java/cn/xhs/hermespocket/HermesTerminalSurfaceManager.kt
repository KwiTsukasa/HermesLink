package cn.xhs.hermespocket

import cn.xhs.hermespocket.HermesText.t

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.view.ActionMode
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.WritableMap
import com.facebook.react.uimanager.SimpleViewManager
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.UIManagerHelper
import com.facebook.react.uimanager.events.Event
import org.json.JSONObject
import org.json.JSONTokener

internal class HermesTerminalSurfaceManager : SimpleViewManager<HermesTerminalSurface>() {
    private val commands = listOf("setInput", "reset", "clear", "focus", "copy", "paste", "key", "write")

    override fun getName() = "HermesTerminalSurface"

    override fun createViewInstance(context: ThemedReactContext) = HermesTerminalSurface(context)

    override fun getExportedCustomDirectEventTypeConstants() =
        mapOf("topTerminalMessage" to mapOf("registrationName" to "onTerminalMessage"))

    override fun getCommandsMap() = commands.withIndex().associate { (id, name) -> name to id }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun receiveCommand(view: HermesTerminalSurface, commandId: Int, args: ReadableArray?) {
        commands.getOrNull(commandId)?.let { receiveCommand(view, it, args) }
    }

    override fun receiveCommand(view: HermesTerminalSurface, command: String, args: ReadableArray?) {
        val name = command.toIntOrNull()?.let(commands::getOrNull) ?: command
        when (name) {
            "setInput" -> {
                val viewId = args?.getString(0)?.let(JSONObject::quote) ?: "null"
                view.runScript("setInput($viewId)")
            }
            "reset" -> view.runScript("reset()")
            "clear" -> view.runScript("clear()")
            "focus" -> view.focusKeyboard()
            "copy" -> view.copyText()
            "paste" -> view.pasteText()
            "key" -> {
                val key = args?.getString(0) ?: return
                view.runScript("key(${JSONObject.quote(key)})")
            }
            "write" -> {
                val data = args?.getString(0) ?: return
                val sequence = args.getInt(1)
                val viewId = JSONObject.quote(args.getString(2) ?: return)
                val replay = args.getBoolean(3)
                view.runScript("write(${JSONObject.quote(data)},$sequence,$viewId,$replay)")
            }
        }
    }

    override fun onDropViewInstance(view: HermesTerminalSurface) {
        super.onDropViewInstance(view)
        view.dispose()
    }
}

internal class HermesTerminalSurface(private val reactContext: ThemedReactContext) : WebView(reactContext) {
    private var started = false
    private var ready = false
    private var disposed = false
    private val rendererTimeout = Runnable { if (!ready) reportError() }

    @Suppress("DEPRECATION", "SetJavaScriptEnabled")
    private fun configureSettings() {
        settings.apply {
            javaScriptEnabled = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            blockNetworkLoads = true
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            domStorageEnabled = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
    }

    init {
        configureSettings()
        setBackgroundColor(Color.rgb(23, 21, 27))
        isFocusableInTouchMode = true
        overScrollMode = View.OVER_SCROLL_NEVER
        addJavascriptInterface(TerminalMessages(), "HermesTerminalSurface")
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), null)

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                reportError()
                dispose()
                return true
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (started || disposed) return
        started = true
        post {
            if (disposed) return@post
            // Android serves this bundled asset even with filesystem and network access disabled.
            loadUrl("file:///android_asset/hermes-terminal/index.html")
            postDelayed(rendererTimeout, 10_000L)
        }
    }

    override fun startActionMode(callback: ActionMode.Callback?): ActionMode? = null

    override fun startActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? = null

    fun runScript(command: String) {
        if (ready && !disposed) evaluateJavascript("window.hermesTerminal.$command;", null)
    }

    fun focusKeyboard() {
        if (!ready || disposed || !hasWindowFocus()) return
        requestFocus()
        evaluateJavascript("window.hermesTerminal.focus();") {
            if (!disposed && hasWindowFocus()) {
                context.getSystemService(InputMethodManager::class.java)
                    ?.showSoftInput(this, 0)
            }
        }
    }

    fun copyText() {
        if (!ready || disposed || !hasWindowFocus()) return
        evaluateJavascript("window.hermesTerminal.copyText();") { result ->
            if (disposed || !hasWindowFocus()) return@evaluateJavascript
            val text = runCatching { JSONTokener(result).nextValue() as? String }.getOrNull()
            when {
                text.isNullOrEmpty() -> toast(t("native.no_terminal_output_to_copy"))
                text.length > 32_768 -> toast(t("native.the_selection_is_too_long_select_less_text"))
                else -> clipboard()?.setPrimaryClip(ClipData.newPlainText(t("native.terminal_output"), text))
            }
        }
    }

    fun pasteText() {
        if (!ready || disposed || !hasWindowFocus()) return
        val clip = clipboard()?.primaryClip
        val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).text else null
        when {
            text.isNullOrEmpty() -> toast(t("native.the_clipboard_has_no_text"))
            text.length > 8192 -> toast(t("native.the_clipboard_text_is_too_long_paste_it_in_parts"))
            else -> runScript("paste(${JSONObject.quote(text.toString())})")
        }
    }

    private fun clipboard() = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    private fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

    fun dispose() {
        if (disposed) return
        disposed = true
        removeCallbacks(rendererTimeout)
        removeJavascriptInterface("HermesTerminalSurface")
        destroy()
    }

    private fun reportError() = emit("{\"kind\":\"error\"}")

    private fun emit(message: String) {
        if (disposed || id == View.NO_ID) return
        UIManagerHelper.getEventDispatcher(reactContext)?.dispatchEvent(
            TerminalMessageEvent(UIManagerHelper.getSurfaceId(this), id, message),
        )
    }

    private inner class TerminalMessages {
        @JavascriptInterface
        fun postMessage(message: String) {
            if (message.length > 65_536) return
            post {
                if (message.startsWith("{\"kind\":\"size\",")) {
                    ready = true
                    removeCallbacks(rendererTimeout)
                } else if (message == "{\"kind\":\"error\"}") {
                    removeCallbacks(rendererTimeout)
                }
                emit(message)
            }
        }
    }

    private class TerminalMessageEvent(surfaceId: Int, id: Int, private val message: String) :
        Event<TerminalMessageEvent>(surfaceId, id) {
        override fun getEventName() = "topTerminalMessage"

        override fun canCoalesce() = false

        override fun getEventData(): WritableMap = Arguments.createMap().apply {
            putString("message", message)
        }
    }
}
