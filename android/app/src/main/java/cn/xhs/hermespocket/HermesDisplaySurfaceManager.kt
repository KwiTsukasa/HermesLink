package cn.xhs.hermespocket

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.LifecycleEventListener
import com.facebook.react.bridge.WritableMap
import com.facebook.react.common.LifecycleState
import com.facebook.react.uimanager.SimpleViewManager
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.UIManagerHelper
import com.facebook.react.uimanager.annotations.ReactProp
import com.facebook.react.uimanager.events.Event
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.min

internal class HermesDisplaySurfaceManager : SimpleViewManager<HermesDisplaySurface>() {
    override fun getName() = "HermesDisplaySurface"

    override fun createViewInstance(context: ThemedReactContext) = HermesDisplaySurface(context)

    @ReactProp(name = "profile")
    fun setProfile(view: HermesDisplaySurface, profile: String?) = view.selectProfile(profile)

    override fun getExportedCustomDirectEventTypeConstants() =
        mapOf("topDisplayState" to mapOf("registrationName" to "onDisplayState"))

    override fun onDropViewInstance(view: HermesDisplaySurface) {
        view.dispose()
        super.onDropViewInstance(view)
    }
}

internal class HermesDisplaySurface(private val reactContext: ThemedReactContext) :
    View(reactContext), LifecycleEventListener {
    private val store = ConnectionStore(reactContext)
    private val stopObservingLogin = store.observeLoginChanges {
        post {
            if (scope != null && scope != store.activeLogin()) {
                stop()
                emit("signed_out")
            }
        }
    }
    private val worker = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1), ThreadPoolExecutor.DiscardOldestPolicy()
    )
    private val frameLock = Any()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val drawingBounds = RectF()
    private var frame: Bitmap? = null
    private var viewer: HermesDisplayViewer? = null
    private var scope: ConnectionStore.LoginScope? = null
    private var profile: String? = null
    private var disposed = false
    @Volatile private var generation = 0L

    init {
        reactContext.addLifecycleEventListener(this)
        setBackgroundColor(Color.BLACK)
        isFocusable = false
        isClickable = false
    }

    fun selectProfile(next: String?) {
        if (next != profile) {
            stop()
            profile = next
        }
        start()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post { start() }
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onHostResume() = start()

    override fun onHostPause() {
        stop()
        emit("paused")
    }

    override fun onHostDestroy() = stop()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        synchronized(frameLock) {
            val image = frame ?: return
            val scale = min(width.toFloat() / image.width, height.toFloat() / image.height)
            val visibleWidth = image.width * scale
            val visibleHeight = image.height * scale
            val left = (width - visibleWidth) / 2
            val top = (height - visibleHeight) / 2
            drawingBounds.set(left, top, left + visibleWidth, top + visibleHeight)
            canvas.drawBitmap(image, null, drawingBounds, paint)
        }
    }

    private fun start() {
        val selected = profile ?: return
        if (viewer != null || disposed || !isAttachedToWindow ||
            reactContext.lifecycleState != LifecycleState.RESUMED
        ) return
        val login = store.activeLogin()
        if (login == null) {
            emit("signed_out")
            return
        }
        val attempt = ++generation
        try {
            val connection = HermesDisplayViewer(
                HermesClient(login.url, store.authFor(login)),
                { generation == attempt && login == store.activeLogin() },
                selected,
                object : HermesDisplayViewer.Observer {
                    override fun state(state: String) {
                        post {
                            if (generation == attempt) {
                                if (state !in listOf("connecting", "waiting", "viewing")) {
                                    stop()
                                }
                                emit(state)
                            }
                        }
                    }

                    override fun resize(width: Int, height: Int) {
                        synchronized(frameLock) {
                            if (generation == attempt) {
                                frame = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
                            }
                        }
                    }

                    override fun row(x: Int, y: Int, argb: IntArray, length: Int) {
                        synchronized(frameLock) {
                            if (generation == attempt) {
                                frame?.setPixels(argb, 0, length, x, y, length, 1)
                            }
                        }
                    }

                    override fun frameReady() {
                        if (generation == attempt) {
                            postInvalidateOnAnimation()
                        }
                    }
                }
            )
            scope = login
            viewer = connection
            worker.execute { connection.connect() }
        } catch (_: IllegalArgumentException) {
            stop()
            emit("unavailable")
        }
    }

    private fun stop() {
        generation++
        viewer?.close()
        viewer = null
        scope = null
        worker.queue.clear()
        synchronized(frameLock) {
            frame = null
        }
        invalidate()
    }

    fun dispose() {
        disposed = true
        stop()
        stopObservingLogin.run()
        reactContext.removeLifecycleEventListener(this)
        worker.shutdownNow()
    }

    private fun emit(state: String) {
        if (!disposed && id != NO_ID) {
            UIManagerHelper.getEventDispatcher(reactContext)
                ?.dispatchEvent(DisplayStateEvent(UIManagerHelper.getSurfaceId(this), id, state))
        }
    }

    private class DisplayStateEvent(surfaceId: Int, id: Int, private val state: String) :
        Event<DisplayStateEvent>(surfaceId, id) {
        override fun getEventName() = "topDisplayState"

        override fun getEventData(): WritableMap = Arguments.createMap().apply {
            putString("state", state)
        }
    }
}
